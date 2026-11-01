package ai.origon.sdk

import kotlinx.serialization.Serializable

/**
 * A snapshot of one voice session, cheap enough to poll every second. Counters
 * are lifetime totals since the call started. Unsigned on the wire; a value
 * past `Long.MAX_VALUE` cannot occur in a call.
 */
@Serializable
data class CallStats(
    val uplink: Uplink,
    val downlink: Downlink,
    val transport: Transport,
) {
    @Serializable
    data class Uplink(
        val sent: Long,
        val sentBytes: Long,
        val bitrateBps: Long,
        val targetBps: Long,
        val skippedStalled: Long,
        val skippedBacklog: Long,
        val skippedUnserviceable: Long,
        val skippedDown: Long,
        val skippedStale: Long,
        val probes: Long,
        val red: Boolean,
        val fecLossPercent: Int,
    )

    @Serializable
    data class Downlink(
        val tracks: Int,
        val received: Long,
        val lostFinal: Long,
        val recoveredFec: Long,
        val recoveredRed: Long,
        val concealed: Long,
        val late: Long,
        val overflow: Long,
        val decoded: Long,
        val jitterMs: Int,
        val depthMs: Int,
        val targetDepthMs: Int,
        val audioAgeMs: Int,
    )

    @Serializable
    data class Transport(
        val rttUs: Long,
        val minRttUs: Long,
        val pathStalls: Long,
        val pathSuspects: Long,
        val switches: Long,
        val reconnects: Long,
    )
}
