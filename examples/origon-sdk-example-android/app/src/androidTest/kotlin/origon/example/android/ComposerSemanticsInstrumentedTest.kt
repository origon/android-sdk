package origon.example.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import origon.example.android.ui.chat.Composer
import origon.example.android.ui.theme.OrigonTheme

class ComposerSemanticsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun talkBackOrderActionsAndTransportDisabledSemantics() {
        val transportEnabled = mutableStateOf(true)
        compose.setContent {
            OrigonTheme {
                Composer(
                    draft = "hello", onDraftChange = {}, pending = emptyList(),
                    onRemovePending = {}, sending = false, hasContent = true,
                    onAttach = {}, onSend = {}, onStartCall = {}, enabled = transportEnabled.value,
                    allowMedia = true, allowFiles = true, voiceActionEnabled = true,
                )
            }
        }
        val attach = compose.onNodeWithContentDescription("Add attachment").assertIsEnabled()
        val message = compose.onNodeWithContentDescription("Message").assertIsEnabled()
        val send = compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        assertEquals(1f, attach.fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
        assertEquals(2f, message.fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
        assertEquals(3f, send.fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])

        compose.runOnIdle { transportEnabled.value = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun emptyComposerAdvertisesStartCallOnlyWhenConfigured() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrigonTheme(darkTheme = true) {
                    Composer(
                        draft = "", onDraftChange = {}, pending = emptyList(),
                        onRemovePending = {}, sending = false, hasContent = false,
                        onAttach = {}, onSend = {}, onStartCall = {}, enabled = true,
                        allowMedia = false, allowFiles = false, voiceActionEnabled = true,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Start a call").assertIsEnabled()
    }
}
