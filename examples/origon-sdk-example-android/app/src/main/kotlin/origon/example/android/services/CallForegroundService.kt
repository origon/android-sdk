package origon.example.android.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import origon.example.android.MainActivity
import origon.example.android.OrigonExampleApp
import origon.example.android.R
import kotlin.coroutines.resume

/**
 * App-owned microphone foreground host. Promotion and audio focus complete before
 * [start] acknowledges; only then may the UI ask [CallService] to enter native code.
 */
class CallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readiness = CompletableDeferred<Boolean>()
    private val binder = LocalBinder()
    private var focus: AudioFocus? = null
    private var observing = false

    internal inner class LocalBinder : Binder(), PromotedCallHost {
        suspend fun awaitPromotion(): PromotedCallHost? =
            if (readiness.await()) this else null

        override fun beginCall() {
            if (focus == null) {
                focus = AudioFocus.forCall(this@CallForegroundService).also { it.request() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (callHostCommand(intent?.action)) {
            CallHostCommand.HangUp -> {
                (application as OrigonExampleApp).sdk.call.endCall()
                stopSelf()
                return START_NOT_STICKY
            }
            CallHostCommand.Orphan -> {
                stopSelf()
                return START_NOT_STICKY
            }
            CallHostCommand.Start -> Unit
        }
        val promoted = promote()
        if (!readiness.isCompleted) readiness.complete(promoted)
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        observeCallPhase()
        return START_NOT_STICKY
    }

    private fun observeCallPhase() {
        if (observing) return
        observing = true
        val call = (application as OrigonExampleApp).sdk.call
        scope.launch {
            val began = withTimeoutOrNull(START_GRACE_MS) {
                call.phase.first { it !is CallService.Phase.Idle && it !is CallService.Phase.Ended }
            }
            if (began != null) {
                call.phase.first { it is CallService.Phase.Idle || it is CallService.Phase.Ended }
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!readiness.isCompleted) readiness.complete(false)
        focus?.abandon()
        focus = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promote(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
        true
    } catch (error: Exception) {
        Log.w(TAG, "foreground promotion refused: ${error.message}")
        false
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW,
            )
                .setName(getString(R.string.call_channel_name))
                .setDescription(getString(R.string.call_channel_description))
                .build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallForegroundService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_channel)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    Person.Builder().setName(getString(R.string.call_notification_title)).build(),
                    hangUp,
                ),
            )
            .build()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val CHANNEL_ID = "origon.example.call"
        private const val NOTIFICATION_ID = 1001
        internal const val ACTION_HANG_UP = "origon.example.android.HANG_UP"
        internal const val ACTION_START = "origon.example.android.START_CALL_HOST"
        private const val START_GRACE_MS = 5_000L
        internal const val GATE_TIMEOUT_MS = 5_000L

        internal suspend fun start(context: Context): CallHostGateResult {
            val app = context.applicationContext
            val intent = Intent(app, CallForegroundService::class.java).setAction(ACTION_START)
            return awaitPromotedCallHost(
                timeoutMillis = GATE_TIMEOUT_MS,
                connect = {
                    ContextCompat.startForegroundService(app, intent)
                    bind(app, intent)
                },
                unwind = { app.stopService(intent) },
            )
        }

        private suspend fun bind(context: Context, intent: Intent): PromotedCallHost? =
            suspendCancellableCoroutine { continuation ->
                lateinit var connection: ServiceConnection
                fun finish(value: PromotedCallHost?) {
                    runCatching { context.unbindService(connection) }
                    if (continuation.isActive) continuation.resume(value)
                }
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val local = service as? CallForegroundService.LocalBinder
                        if (local == null) {
                            finish(null)
                        } else {
                            CoroutineScope(continuation.context).launch {
                                finish(local.awaitPromotion())
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = finish(null)
                    override fun onNullBinding(name: ComponentName?) = finish(null)
                }
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    finish(null)
                }
                continuation.invokeOnCancellation {
                    runCatching { context.unbindService(connection) }
                }
            }
    }
}

internal enum class CallHostCommand { Start, HangUp, Orphan }

internal fun callHostCommand(action: String?): CallHostCommand = when (action) {
    CallForegroundService.ACTION_HANG_UP -> CallHostCommand.HangUp
    CallForegroundService.ACTION_START -> CallHostCommand.Start
    null -> CallHostCommand.Orphan
    else -> CallHostCommand.Orphan
}
