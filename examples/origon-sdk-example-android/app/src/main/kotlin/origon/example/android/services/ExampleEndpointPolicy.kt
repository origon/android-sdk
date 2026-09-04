package origon.example.android.services

import ai.origon.sdk.ServerConfig

enum class AttachmentCategory { IMAGE, DOCUMENT, VIDEO, AUDIO }

data class ExampleAttachmentPolicy(
    val images: Boolean = false,
    val documents: Boolean = false,
    val videos: Boolean = false,
    val audio: Boolean = false,
) {
    fun allows(category: AttachmentCategory): Boolean = when (category) {
        AttachmentCategory.IMAGE -> images
        AttachmentCategory.DOCUMENT -> documents
        AttachmentCategory.VIDEO -> videos
        AttachmentCategory.AUDIO -> audio
    }

    fun allows(contentType: String): Boolean = allows(category(contentType))

    companion object {
        fun category(contentType: String): AttachmentCategory = when {
            contentType.startsWith("image/") -> AttachmentCategory.IMAGE
            contentType.startsWith("video/") -> AttachmentCategory.VIDEO
            contentType.startsWith("audio/") -> AttachmentCategory.AUDIO
            else -> AttachmentCategory.DOCUMENT
        }
    }
}

data class ExampleServerConfig(
    val startMessage: String,
    val multipleChannels: Boolean,
    val chatEnabled: Boolean,
    val callEnabled: Boolean,
    val attachments: ExampleAttachmentPolicy,
) {
    companion object {
        fun from(config: ServerConfig) = ExampleServerConfig(
            startMessage = config.startMessage,
            multipleChannels = config.multipleChannels,
            chatEnabled = config.isChatEnabled,
            callEnabled = config.isCallEnabled,
            attachments = ExampleAttachmentPolicy(
                images = config.attachmentPolicy.images.enabled,
                documents = config.attachmentPolicy.documents.enabled,
                videos = config.attachmentPolicy.videos.enabled,
                audio = config.attachmentPolicy.audio.enabled,
            ),
        )
    }
}

data class ExampleEndpointPolicy(
    val greeting: String,
    val chatEnabled: Boolean,
    val callEnabled: Boolean,
    val multipleChannels: Boolean,
    val attachments: ExampleAttachmentPolicy,
) {
    val showsComposer: Boolean get() = chatEnabled
    val showsVoiceOnlyAction: Boolean get() = callEnabled && !chatEnabled
    val showsComposerVoiceAction: Boolean get() = callEnabled && chatEnabled && multipleChannels
    val promptSendEnabled: Boolean get() = chatEnabled

    fun allowsAttachment(contentType: String): Boolean =
        chatEnabled && attachments.allows(contentType)

    companion object {
        const val DEFAULT_GREETING = "How can we help?"

        val DISABLED = ExampleEndpointPolicy(
            greeting = DEFAULT_GREETING,
            chatEnabled = false,
            callEnabled = false,
            multipleChannels = false,
            attachments = ExampleAttachmentPolicy(),
        )

        fun from(
            config: ExampleServerConfig?,
            authoritative: Boolean = true,
        ): ExampleEndpointPolicy {
            if (config == null) return DISABLED
            return ExampleEndpointPolicy(
                greeting = config.startMessage.trim().ifEmpty { DEFAULT_GREETING },
                chatEnabled = authoritative && config.chatEnabled,
                callEnabled = authoritative && config.callEnabled,
                multipleChannels = authoritative && config.multipleChannels,
                attachments = if (authoritative && config.chatEnabled) {
                    config.attachments
                } else {
                    ExampleAttachmentPolicy()
                },
            )
        }
    }
}

/** Rejects a late /config result from a client that has already been replaced. */
class ExampleConfigReplacement {
    private var epoch = 0L
    val currentEpoch: Long get() = epoch
    var value: ExampleServerConfig? = null
        private set

    fun begin(): Long {
        value = null
        return ++epoch
    }

    fun install(candidate: ExampleServerConfig, token: Long): Boolean {
        if (token != epoch) return false
        value = candidate
        return true
    }
}
