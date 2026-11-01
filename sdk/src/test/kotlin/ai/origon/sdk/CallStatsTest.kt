package ai.origon.sdk

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStatsTest {
    /** The shape in apps/sdk/session/docs/contract.md "Call statistics". */
    private val native = """
        {"uplink":{"sent":1,"sentBytes":2,"bitrateBps":3,"targetBps":4,"skippedStalled":5,
        "skippedBacklog":6,"skippedUnserviceable":7,"skippedDown":8,"skippedStale":9,"probes":10,
        "red":true,"fecLossPercent":11},
        "downlink":{"tracks":12,"received":13,"lostFinal":14,"recoveredFec":15,"recoveredRed":16,
        "concealed":17,"late":18,"overflow":19,"decoded":20,"jitterMs":21,"depthMs":22,
        "targetDepthMs":23,"audioAgeMs":24},
        "transport":{"rttUs":25,"minRttUs":26,"pathStalls":27,"pathSuspects":28,"switches":29,
        "reconnects":30}}
    """.trimIndent()

    @Test fun decodesTheNativeShape() {
        val stats = Json.decodeFromString(CallStats.serializer(), native)
        assertEquals(1L, stats.uplink.sent)
        assertTrue(stats.uplink.red)
        assertEquals(24, stats.downlink.audioAgeMs)
        assertEquals(30L, stats.transport.reconnects)
    }

    @Test fun bridgeSignatureMatchesTheJniExport() {
        val method = SessionBridge::class.java.getDeclaredMethod(
            "callStats",
            Long::class.javaPrimitiveType,
            String::class.java,
        )
        assertEquals(String::class.java, method.returnType)
    }
}
