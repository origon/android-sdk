package ai.origon.sdk.bridge

/** ABI-locked JVM value constructed by the Rust JNI bridge. */
internal data class SessionAudioLevelsBridge(
    val sessionId: String,
    val outbound: Float,
    val inbound: Float,
    val endpoints: Array<EndpointAudioLevelBridge>,
)
