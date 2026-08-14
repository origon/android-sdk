package ai.origon.sdk

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Properties

internal data class PushRegistration(val token: String, val generation: String)

internal object PushAuthorityStore {
    private fun file(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "ai.origon.sdk/push-registration"))

    @Synchronized
    fun load(context: Context): PushRegistration? {
        return runCatching {
            val properties = Properties()
            file(context).openRead().use(properties::load)
            val token = properties.getProperty("token")?.takeIf(String::isNotBlank)
                ?: error("missing token")
            val generation = properties.getProperty("generation")?.takeIf(String::isNotBlank)
                ?: error("missing generation")
            PushRegistration(token = token, generation = generation)
        }.getOrNull()
    }

    @Synchronized
    fun save(context: Context, registration: PushRegistration) {
        val file = file(context)
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            Properties().apply {
                setProperty("token", registration.token)
                setProperty("generation", registration.generation)
            }.store(output, null)
            output.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}

data class OrigonNotificationPayload(
    val sessionId: String,
    val clientId: String?,
    val messageId: String?,
    val preview: String?,
) {
    var title: String? = null
        private set

    internal constructor(
        sessionId: String,
        clientId: String?,
        messageId: String?,
        preview: String?,
        title: String?,
    ) : this(sessionId, clientId, messageId, preview) {
        this.title = title
    }
}

/** Firebase-facing token and notification-data helpers without owning FCM setup. */
object OrigonPushNotifications {
    /** Call from every `FirebaseMessagingService.onNewToken` callback. */
    fun onNewToken(token: String) {
        OrigonClient.registerForPushNotifications(token)
    }

    /** Clear local preview authority during logout, even without a live client. */
    fun clearAuthority(context: Context) {
        PushRegistrar.clearAuthority(context)
    }

    fun isCurrent(context: Context, data: Map<String, String>): Boolean {
        return generationMatches(
            PushAuthorityStore.load(context.applicationContext)?.generation,
            data,
        )
    }

    /** Returns null on missing/mismatched generation so the host suppresses rich copy. */
    fun currentPayload(context: Context, data: Map<String, String>): OrigonNotificationPayload? {
        return authorizedPayload(
            localGeneration = PushAuthorityStore.load(context.applicationContext)?.generation,
            data = data,
        )
    }

    /** Notification taps are explicit user intent and may take over the chat. */
    fun open(client: OrigonClient, payload: OrigonNotificationPayload): StartSessionResponse =
        client.openChat(payload.sessionId, intent = ChatAccessIntent.NOTIFICATION)
}

internal fun generationMatches(local: String?, data: Map<String, String>): Boolean =
    local != null && data["endpointGeneration"] == local

internal fun authorizedPayload(
    localGeneration: String?,
    data: Map<String, String>,
): OrigonNotificationPayload? {
    if (!generationMatches(localGeneration, data)) return null
    val sessionId = data["sessionId"]?.takeIf(String::isNotBlank) ?: return null
    return OrigonNotificationPayload(
        sessionId = sessionId,
        clientId = data["clientId"],
        messageId = data["messageId"],
        preview = data["preview"]?.takeIf(String::isNotBlank),
        title = data["title"]?.takeIf(String::isNotBlank),
    )
}
