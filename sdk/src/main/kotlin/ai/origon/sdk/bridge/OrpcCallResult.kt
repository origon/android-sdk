package ai.origon.sdk.bridge

/** JNI-owned result of `orpcOpen` / `orpcReopen`. Field names are ABI. */
internal class OrpcCallResult {
    @JvmField var orpc: Long = 0
    @JvmField var generation: Long = 0
    @JvmField var response: ByteArray? = null
}
