package ai.origon.sdk.bridge

/** JNI-owned result from one blocking finite-loader pull. */
internal class SessionLoaderResult {
    @JvmField var status: Int = 0
    @JvmField var payloadJson: String? = null
    @JvmField var errorKind: Int = 0
    @JvmField var errorStatusCode: Int = 0
    @JvmField var errorCode: String? = null
    @JvmField var errorMessage: String? = null
    @JvmField var cachedSnapshotEmitted: Boolean = false
}
