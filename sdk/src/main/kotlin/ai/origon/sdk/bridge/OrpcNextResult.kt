package ai.origon.sdk.bridge

/** JNI-owned result of one blocking `orpcNext`. Field names are ABI. */
internal class OrpcNextResult {
    @JvmField var kind: Int = 0
    @JvmField var generation: Long = 0
    @JvmField var data: ByteArray? = null
    @JvmField var status: Int = 0
    @JvmField var attempt: Int = 0
    @JvmField var terminal: Int = 0
    @JvmField var detail: String? = null
}
