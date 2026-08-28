package ai.origon.sdk.bridge

/** ABI-locked result of one blocking native audio-level pull. */
internal data class AudioLevelsNextBridge(
    val status: Int,
    val snapshot: SessionAudioLevelsBridge?,
)
