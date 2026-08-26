package origon.example.android

import ai.origon.sdk.MessageAudience
import ai.origon.sdk.MessageRole
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.TypingParticipant
import ai.origon.sdk.TypingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import origon.example.android.services.ChatService
import origon.example.android.ui.components.exampleTypingAuthor

class TypingParticipantTest {
    @Test fun namedAndUnnamedPeopleResolveFromTypingIdentity() {
        val named = TypingParticipant("p1", MessageRole.USER, "u1", "Sam", MessageAudience.ALL)
        assertEquals("Sam", exampleTypingAuthor(named).displayName)
        val unnamed = TypingParticipant("p2", MessageRole.USER, audience = MessageAudience.INTERNAL)
        assertEquals("Agent", exampleTypingAuthor(unnamed).displayName)
    }

    @Test fun flowAndEmptyUseAssistantFallback() {
        val flow = TypingParticipant("flow", MessageRole.SYSTEM, audience = MessageAudience.ALL)
        assertEquals("Assistant", exampleTypingAuthor(flow).displayName)
        assertEquals("Assistant", exampleTypingAuthor(null).displayName)
    }

    @Test fun terminalEventClearsTypingSnapshot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = ChatService(
            scope = scope,
            events = emptyFlow(),
            sdkClient = { null },
            destinationClient = { null },
            refreshSessions = {},
        )
        val participant = TypingParticipant(
            "p1", MessageRole.USER, "u1", "Sam", MessageAudience.ALL,
        )
        try {
            service.installStateForTesting("chat", ChatService.SessionUIState())
            service.receiveForTesting(ClientEvent.Typing("chat", TypingState(listOf(participant))))
            assertTrue(service.stateFor("chat")?.isTyping == true)
            assertEquals(participant, service.stateFor("chat")?.typingState?.participants?.first())

            service.receiveForTesting(ClientEvent.ChatSessionEnded("chat", "complete"))

            assertFalse(service.stateFor("chat")?.isTyping ?: true)
            assertTrue(service.stateFor("chat")?.typingState?.participants?.isEmpty() == true)
            assertNull(service.typingParticipant.value)
        } finally {
            scope.cancel()
        }
    }
}
