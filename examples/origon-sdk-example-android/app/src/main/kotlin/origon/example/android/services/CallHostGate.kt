package origon.example.android.services

import android.Manifest
import kotlinx.coroutines.withTimeoutOrNull

internal interface PromotedCallHost {
    /** Runs only after foreground promotion; must complete before native capture starts. */
    fun beginCall()
}

internal sealed interface CallHostGateResult {
    data object Ready : CallHostGateResult
    data class Failed(val reason: String) : CallHostGateResult
}

internal fun callPermissionAllowsHost(
    result: Map<String, Boolean>,
    microphoneCurrentlyGranted: Boolean,
): Boolean = result[Manifest.permission.RECORD_AUDIO] ?: microphoneCurrentlyGranted

/** Testable five-second gate shared by the Android service binding and unit tests. */
internal suspend fun awaitPromotedCallHost(
    timeoutMillis: Long,
    connect: suspend () -> PromotedCallHost?,
    unwind: () -> Unit,
): CallHostGateResult {
    val host = try {
        withTimeoutOrNull(timeoutMillis) { connect() }
    } catch (error: Throwable) {
        unwind()
        return CallHostGateResult.Failed(error.message ?: "Call host failed")
    }
    if (host == null) {
        unwind()
        return CallHostGateResult.Failed("Call host did not start in time")
    }
    return try {
        host.beginCall()
        CallHostGateResult.Ready
    } catch (error: Throwable) {
        unwind()
        CallHostGateResult.Failed(error.message ?: "Call host failed")
    }
}
