package ai.origon.sdk.bridge

/** ABI-locked JNI DTO. Constructor descriptor: (String, int, String?). */
internal data class RestoreResult(
    val sessionId: String,
    val status: Int,
    val error: String?,
)
