package origon.example.android

import origon.example.android.services.ExampleAttachmentPolicy
import origon.example.android.services.ExampleConfigReplacement
import origon.example.android.services.ExampleEndpointPolicy
import origon.example.android.services.ExampleServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointConfigPolicyTest {
    @Test
    fun channelMatrixDrivesTheExampleSurface() {
        data class Row(
            val chat: Boolean, val call: Boolean, val multi: Boolean,
            val composer: Boolean, val voiceOnly: Boolean, val composerVoice: Boolean,
        )
        val rows = listOf(
            Row(false, true, false, false, true, false),
            Row(true, false, false, true, false, false),
            Row(true, true, true, true, false, true),
            Row(true, true, false, true, false, false),
            Row(false, false, false, false, false, false),
        )

        rows.forEach { row ->
            val policy = ExampleEndpointPolicy.from(config(row.chat, row.call, row.multi))
            assertEquals(row.composer, policy.showsComposer)
            assertEquals(row.voiceOnly, policy.showsVoiceOnlyAction)
            assertEquals(row.composerVoice, policy.showsComposerVoiceAction)
            assertEquals(row.chat, policy.promptSendEnabled)
        }
    }

    @Test
    fun greetingAndPerCategoryAttachmentsAreAppOwned() {
        val blank = ExampleEndpointPolicy.from(config(true, true, true, greeting = "  "))
        assertEquals(ExampleEndpointPolicy.DEFAULT_GREETING, blank.greeting)

        val policy = ExampleEndpointPolicy.from(config(
            true, false, false,
            attachments = ExampleAttachmentPolicy(images = true, audio = true),
        ))
        assertTrue(policy.allowsAttachment("image/jpeg"))
        assertTrue(policy.allowsAttachment("audio/mpeg"))
        assertFalse(policy.allowsAttachment("video/mp4"))
        assertFalse(policy.allowsAttachment("application/pdf"))

        val voiceOnly = ExampleEndpointPolicy.from(config(
            false, true, false,
            attachments = ExampleAttachmentPolicy(images = true, documents = true),
        ))
        assertFalse(voiceOnly.allowsAttachment("image/jpeg"))
        assertFalse(voiceOnly.allowsAttachment("application/pdf"))
    }

    @Test
    fun replacementClearsImmediatelyAndRejectsLateConfig() {
        val replacement = ExampleConfigReplacement()
        val old = replacement.begin()
        assertTrue(replacement.install(config(true, false, false, greeting = "Old"), old))
        assertEquals("Old", replacement.value?.startMessage)

        val current = replacement.begin()
        assertNull(replacement.value)
        assertFalse(replacement.install(config(true, false, false, greeting = "Late"), old))
        assertNull(replacement.value)
        assertTrue(replacement.install(config(false, true, false, greeting = "New"), current))
        assertEquals("New", replacement.value?.startMessage)
    }

    @Test
    fun cachedConfigurationKeepsGreetingButDisablesEveryAction() {
        val cached = config(
            chat = true,
            call = true,
            multi = true,
            greeting = "Cached greeting",
            attachments = ExampleAttachmentPolicy(images = true, documents = true),
        )
        val policy = ExampleEndpointPolicy.from(cached, authoritative = false)
        assertEquals("Cached greeting", policy.greeting)
        assertFalse(policy.showsComposer)
        assertFalse(policy.showsVoiceOnlyAction)
        assertFalse(policy.showsComposerVoiceAction)
        assertFalse(policy.promptSendEnabled)
        assertFalse(policy.allowsAttachment("image/jpeg"))
    }

    private fun config(
        chat: Boolean,
        call: Boolean,
        multi: Boolean,
        greeting: String = "Welcome",
        attachments: ExampleAttachmentPolicy = ExampleAttachmentPolicy(),
    ) = ExampleServerConfig(greeting, multi, chat, call, attachments)
}
