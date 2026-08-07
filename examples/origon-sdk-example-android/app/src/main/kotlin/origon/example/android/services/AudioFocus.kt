package origon.example.android.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * A held audio-focus request — the app's half of audio ownership.
 *
 * **The app owns focus; the SDK owns everything else.** The SDK sets
 * `MODE_IN_COMMUNICATION`, picks the communication device and runs the audio
 * streams, and deliberately requests no focus — the native audio APIs have no
 * focus API at all, it exists only on the Java/Kotlin `AudioManager`. So this
 * is not a second manager of something the SDK already handles; it is the half
 * the SDK cannot reach. Corollary: **never call
 * `setMode`/`setSpeakerphoneOn`/`setCommunicationDevice` from the app** — that
 * races the SDK's own save/restore and can strand the device in communication
 * mode after a call.
 *
 * Written by hand rather than through `AudioFocusRequestCompat`, which lives in
 * `androidx.media` — deprecated in favour of media3, so consuming it would mean
 * adding a *new and already-dead* dependency for two branches.
 */
class AudioFocus(
    context: Context,
    private val usage: Int,
    private val contentType: Int,
    private val gain: Int,
    private val onLoss: () -> Unit = {},
) {

    private val manager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Held so the API 26+ abandon can present the same request object. */
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> onLoss()
        }
    }

    /**
     * @return true when focus was granted. A refusal is not fatal — audio still
     * plays; focus is advisory — so the result is logged rather than thrown.
     */
    fun request(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val built = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = built
            manager.requestAudioFocus(built)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Log.w(TAG, "audio focus not granted: $result")
        return granted
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { manager.abandonAudioFocusRequest(it) }
            request = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }
    }

    companion object {
        private const val TAG = "AudioFocus"

        /**
         * Focus for attachment playback. Yields to a call rather than talking
         * over it — [onLoss] is where the caller pauses.
         */
        fun forMediaPlayback(context: Context, onLoss: () -> Unit) = AudioFocus(
            context = context,
            usage = AudioAttributes.USAGE_MEDIA,
            contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
            gain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            onLoss = onLoss,
        )
    }
}
