package ai.origon.sdk

import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Process-wide coordinator for push registration.
 *
 * Push registration is a device/app-level concern that can race
 * [OrigonClient] creation (FCM may deliver a token via
 * `onNewToken` before the app has built its client), so the state lives
 * here rather than on a client instance. A single-thread executor runs
 * the blocking JNI calls, keeping registration ordered and off the main
 * thread; the small amount of mutable state is guarded by [lock].
 */
internal object PushRegistrar {
    private const val TAG = "OrigonSDK"

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "origon-push").apply { isDaemon = true }
    }

    /** Most recently created client, weakly held so we never keep it alive. */
    private var client: WeakReference<OrigonClient>? = null
    /** Latest token awaiting (re-)send, retained so a client created after the
     *  token arrives can still register. */
    private var bufferedToken: String? = null
    private var authoritySuspended: Boolean = false
    private var authorityEpoch: Long = 0

    // ── Client lifecycle (called by OrigonClient) ────────────────────

    /** Record the active client and flush any token buffered before init. */
    fun attach(client: OrigonClient) {
        val token: String?
        synchronized(lock) {
            this.client = WeakReference(client)
            authoritySuspended = false
            authorityEpoch += 1
            token = bufferedToken ?: PushAuthorityStore.load(client.appContext)?.token
        }
        if (token != null) {
            sendRegister(client, token)
        }
    }

    /** Drop the active-client reference when that client is closed. */
    fun detach(client: OrigonClient) {
        synchronized(lock) {
            if (this.client?.get() === client) {
                this.client = null
            }
        }
    }

    // ── Registration (called by the public API) ──────────────────────

    fun register(token: String) {
        val target: OrigonClient?
        synchronized(lock) {
            if (authoritySuspended) {
                Log.d(TAG, "push authority suspended; dropping token callback")
                return
            }
            bufferedToken = token
            target = client?.get()
        }
        if (target == null) {
            Log.d(TAG, "no active client; buffering push token until init")
            return
        }
        sendRegister(target, token)
    }

    fun unregister() {
        val target: OrigonClient?
        synchronized(lock) {
            authoritySuspended = true
            authorityEpoch += 1
            bufferedToken = null
            target = client?.get()
        }
        if (target == null) {
            Log.d(TAG, "no active client; retaining any logout retry state")
            return
        }
        executor.execute {
            try {
                val registration = PushAuthorityStore.load(target.appContext)
                if (registration == null) {
                    Log.d(TAG, "no persisted push registration; nothing to unregister")
                    return@execute
                }
                target.unregisterPush(
                    token = registration.token,
                    provider = "fcm",
                    generation = registration.generation,
                )
                PushAuthorityStore.clear(target.appContext)
            } catch (e: Throwable) {
                Log.e(TAG, "unregisterForPushNotifications failed", e)
            }
        }
    }

    /** Ordered logout gate for a host that will close the client immediately. */
    fun unregisterBlocking(target: OrigonClient) {
        synchronized(lock) {
            authoritySuspended = true
            authorityEpoch += 1
            bufferedToken = null
        }
        val future = executor.submit {
            val registration = PushAuthorityStore.load(target.appContext) ?: return@submit
            try {
                target.unregisterPush(
                    token = registration.token,
                    provider = "fcm",
                    generation = registration.generation,
                )
            } finally {
                PushAuthorityStore.clear(target.appContext)
            }
        }
        try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun sendRegister(client: OrigonClient, token: String) {
        val epoch = synchronized(lock) { authorityEpoch }
        executor.execute {
            try {
                val generation = client.registerPush(
                    token = token,
                    provider = "fcm",
                    environment = null,
                )
                val mayPersist = synchronized(lock) {
                    !authoritySuspended && authorityEpoch == epoch && this.client?.get() === client
                }
                if (mayPersist) {
                    PushAuthorityStore.save(client.appContext, PushRegistration(token, generation))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "registerForPushNotifications failed", e)
            }
        }
    }

    fun clearAuthority(context: android.content.Context) {
        synchronized(lock) {
            authoritySuspended = true
            authorityEpoch += 1
            bufferedToken = null
        }
        PushAuthorityStore.clear(context.applicationContext)
    }
}
