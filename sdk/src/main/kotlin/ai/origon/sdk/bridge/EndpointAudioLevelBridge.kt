package ai.origon.sdk.bridge

/** ABI-locked JVM value constructed by the Rust JNI bridge. */
internal data class EndpointAudioLevelBridge(
    val endpointId: String,
    val inbound: Float,
)
