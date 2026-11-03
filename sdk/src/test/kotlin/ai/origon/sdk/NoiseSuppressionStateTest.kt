package ai.origon.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseSuppressionStateTest {
    @Test
    fun decodesEveryReasonTheBridgeNames() {
        val names = mapOf(
            "none" to NoiseSuppressionReason.NONE,
            "userOff" to NoiseSuppressionReason.USER_OFF,
            "slowDevice" to NoiseSuppressionReason.SLOW_DEVICE,
            "modelUnavailable" to NoiseSuppressionReason.MODEL_UNAVAILABLE,
            "unsupported" to NoiseSuppressionReason.UNSUPPORTED,
        )
        for ((name, reason) in names) {
            val state = OrigonClient.decodeNoiseSuppression("""{"active":false,"reason":"$name"}""")
            assertEquals(NoiseSuppressionState(active = false, reason = reason), state)
        }
        assertEquals(
            NoiseSuppressionState(active = true, reason = NoiseSuppressionReason.NONE),
            OrigonClient.decodeNoiseSuppression("""{"active":true,"reason":"none"}"""),
        )
    }

    @Test
    fun aMissingOrUnknownPayloadIsDropped() {
        assertNull(OrigonClient.decodeNoiseSuppression(null))
        assertNull(OrigonClient.decodeNoiseSuppression(""))
        assertNull(OrigonClient.decodeNoiseSuppression("""{"active":false,"reason":"later"}"""))
    }
}
