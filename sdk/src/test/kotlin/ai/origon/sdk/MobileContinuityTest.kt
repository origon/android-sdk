package ai.origon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class MobileContinuityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dtmfValidationAcceptsExactlyThePublicAlphabet() {
        for (digit in "0123456789*#ABCD") {
            assertEquals(digit, OrigonClient.validateDtmfDigit(digit))
        }
    }

    @Test
    fun dtmfValidationRejectsLowercaseAndNonAsciiWithConstantError() {
        for (digit in listOf('a', 'd', 'é', '１', '\uD83D')) {
            val error = assertFailsWith<IllegalArgumentException> {
                OrigonClient.validateDtmfDigit(digit)
            }
            assertEquals(
                "DTMF digit must be one uppercase ASCII symbol",
                error.message,
            )
        }
    }

    @Test
    fun durableCachePolicyDefaultsOnAndCanBeDisabled() {
        assertEquals(ChatCachePolicy.ENABLED, ClientConfig("https://example.test").chatCachePolicy)
        assertEquals(
            ChatCachePolicy.DISABLED,
            ClientConfig("https://example.test", chatCachePolicy = ChatCachePolicy.DISABLED)
                .chatCachePolicy,
        )
    }

    @Test
    fun sessionSummaryRequiresActive() {
        val complete = """{"sessionId":"s","subject":"x","channel":"chat","active":true,"createdAt":"c","updatedAt":"u"}"""
        assertTrue(json.decodeFromString<SessionSummary>(complete).active)

        val legacy = """{"sessionId":"s","subject":"x","channel":"chat","createdAt":"c","updatedAt":"u"}"""
        assertFails { json.decodeFromString<SessionSummary>(legacy) }
    }

    @Test
    fun messageAndSendPayloadPreserveOptionalAudienceMetadata() {
        val internalRow = """{"id":"m1","metadata":{"audience":"internal"}}"""
        assertEquals(
            MessageAudience.INTERNAL,
            json.decodeFromString<Message>(internalRow).metadata?.audience,
        )
        assertEquals(
            MessageAudience.ALL,
            json.decodeFromString<Message>("""{"id":"m1a","metadata":{"audience":"all"}}""")
                .metadata?.audience,
        )

        for (row in listOf("""{"id":"m2"}""", """{"id":"m2","metadata":null}""")) {
            assertNull(json.decodeFromString<Message>(row).metadata)
        }

        for (row in listOf(
            """{"id":"m3","metadata":{}}""",
            """{"id":"m3","metadata":{"audience":null}}""",
            """{"id":"m3","metadata":{"audience":""}}""",
        )) {
            assertNull(json.decodeFromString<Message>(row).metadata?.audience)
        }

        assertNull(Message(id = "local").metadata)

        val unknown = """{"id":"m4","metadata":{"audience":"staff"}}"""
        assertFails { json.decodeFromString<Message>(unknown) }
        assertFails { json.decodeFromString<Message>("""{"id":"m5","metadata":{"audience":" "}}""") }

        assertFalse(json.encodeToString(SendMessagePayload(text = "hello")).contains("metadata"))
        assertEquals(
            emptySet(),
            json.parseToJsonElement(
                json.encodeToString(SendMessagePayload(metadata = MessageMetadata())),
            ).jsonObject.getValue("metadata").jsonObject.keys,
        )
        assertEquals(
            "all",
            json.parseToJsonElement(
                json.encodeToString(
                    SendMessagePayload(metadata = MessageMetadata(MessageAudience.ALL)),
                ),
            ).jsonObject.getValue("metadata").jsonObject.getValue("audience").toString().trim('"'),
        )
    }

    @Test
    fun sessionSummaryLastMessageAcceptsLegacyMissingMetadata() {
        val row = """{"sessionId":"s","subject":"Support","channel":"chat","active":false,"createdAt":"c","updatedAt":"u","lastMessage":{"id":"m","role":"external","attachments":[],"status":"delivered","state":"completed"}}"""
        assertNull(json.decodeFromString<SessionSummary>(row).lastMessage?.metadata)
    }

    @Test
    fun restoreStatusAndGenerationAreClosed() {
        assertEquals(RestoreStatus.CONNECTED, restoreStatus(0))
        assertEquals(RestoreStatus.FAILED, restoreStatus(99))
        assertTrue(generationMatches("current", mapOf("endpointGeneration" to "current")))
        assertFalse(generationMatches("current", mapOf("endpointGeneration" to "stale")))
        assertFalse(generationMatches(null, mapOf("endpointGeneration" to "current")))
    }

    @Test
    fun notificationCopyRequiresExactGeneration() {
        val data = mapOf(
            "sessionId" to "session",
            "clientId" to "client",
            "messageId" to "message",
            "endpointGeneration" to "current",
            "title" to "Agent Name",
            "preview" to "Authorized preview",
        )

        val current = authorizedPayload(localGeneration = "current", data = data)
        assertEquals("Agent Name", current?.title)
        assertEquals("Authorized preview", current?.preview)

        // A stale or missing generation exposes no payload object, so neither
        // rich-copy field can accidentally reach visible notification UI.
        assertNull(authorizedPayload(localGeneration = "stale", data = data))
        assertNull(authorizedPayload(localGeneration = null, data = data))
        assertNull(authorizedPayload(localGeneration = "current", data = data - "endpointGeneration"))
    }

    @Test
    fun notificationTitleIsOptionalAndBlankIsAbsent() {
        val required = mapOf(
            "sessionId" to "session",
            "endpointGeneration" to "current",
            "preview" to "Authorized preview",
        )

        val absent = authorizedPayload(localGeneration = "current", data = required)
        assertNull(absent?.title)
        assertEquals("Authorized preview", absent?.preview)

        val blank = authorizedPayload(
            localGeneration = "current",
            data = required + ("title" to " \t\n"),
        )
        assertNull(blank?.title)
        assertEquals("Authorized preview", blank?.preview)
    }

    @Test
    fun notificationPayloadRetainsLegacyJvmConstructorAndCopy() {
        val constructor = OrigonNotificationPayload::class.java.constructors.single {
            it.parameterTypes.contentEquals(
                arrayOf(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ),
            )
        }

        val payload = constructor.newInstance("session", "client", "message", "preview")
            as OrigonNotificationPayload
        assertEquals("session", payload.sessionId)
        assertEquals("client", payload.clientId)
        assertEquals("message", payload.messageId)
        assertEquals("preview", payload.preview)
        assertNull(payload.title)

        val copy = OrigonNotificationPayload::class.java.declaredMethods.single {
            it.name == "copy" && it.parameterTypes.contentEquals(
                arrayOf(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ),
            )
        }
        val copied = copy.invoke(payload, "session-2", "client-2", "message-2", "preview-2")
            as OrigonNotificationPayload
        assertEquals("session-2", copied.sessionId)
        assertEquals("client-2", copied.clientId)
        assertEquals("message-2", copied.messageId)
        assertEquals("preview-2", copied.preview)
        assertNull(copied.title)

        OrigonNotificationPayload::class.java.declaredMethods.single {
            it.name == "copy\$default" && it.parameterTypes.contentEquals(
                arrayOf(
                    OrigonNotificationPayload::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    java.lang.Integer.TYPE,
                    Any::class.java,
                ),
            )
        }
    }
}
