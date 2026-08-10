package ai.origon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MobileContinuityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionSummaryRequiresActive() {
        val complete = """{"sessionId":"s","subject":"x","channel":"chat","active":true,"createdAt":"c","updatedAt":"u"}"""
        assertTrue(json.decodeFromString<SessionSummary>(complete).active)

        val legacy = """{"sessionId":"s","subject":"x","channel":"chat","createdAt":"c","updatedAt":"u"}"""
        assertFails { json.decodeFromString<SessionSummary>(legacy) }
    }

    @Test
    fun restoreStatusAndGenerationAreClosed() {
        assertEquals(RestoreStatus.CONNECTED, restoreStatus(0))
        assertEquals(RestoreStatus.FAILED, restoreStatus(99))
        assertTrue(generationMatches("current", mapOf("endpointGeneration" to "current")))
        assertFalse(generationMatches("current", mapOf("endpointGeneration" to "stale")))
        assertFalse(generationMatches(null, mapOf("endpointGeneration" to "current")))
    }
}
