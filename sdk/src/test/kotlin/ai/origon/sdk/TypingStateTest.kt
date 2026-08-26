package ai.origon.sdk

import ai.origon.sdk.bridge.SessionEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingStateTest {
    @Test fun canonicalJsonRoundTripsAndPreservesOrder() {
        val json = """{"participants":[{"participantId":"supervisor-1","role":"user","userId":"u1","userName":"Sam","audience":"internal"},{"participantId":"flow","role":"system","audience":"all"}]}"""
        val state = Json.decodeFromString(TypingState.serializer(), json)
        assertEquals(listOf("supervisor-1", "flow"), state.participants.map { it.participantId })
        assertEquals(MessageAudience.INTERNAL, state.participants.first().audience)
        assertNull(state.participants.last().userId)
    }

    @Test fun jniSessionEventRetainsCompatibilityFieldSignatures() {
        val fields = SessionEvent::class.java.declaredFields.associateBy { it.name }
        assertEquals(Boolean::class.javaPrimitiveType, fields.getValue("typing").type)
        assertEquals(String::class.java, fields.getValue("messageJson").type)
        assertTrue(fields.keys.containsAll(listOf("kind", "sessionId", "updateId", "audioRoute")))
    }
}
