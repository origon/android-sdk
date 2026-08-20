package origon.example.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import origon.example.android.services.ExampleAttachmentPolicy
import origon.example.android.services.ExampleEndpointPolicy
import origon.example.android.services.ExampleServerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointConfigInstrumentedTest {
    @Test
    fun endpointPolicyRunsOnDevice() {
        val policy = ExampleEndpointPolicy.from(ExampleServerConfig(
            startMessage = "Welcome",
            multipleChannels = true,
            chatEnabled = true,
            callEnabled = true,
            attachments = ExampleAttachmentPolicy(images = true),
        ))
        assertTrue(policy.showsComposer)
        assertTrue(policy.showsComposerVoiceAction)
        assertTrue(policy.allowsAttachment("image/jpeg"))
        assertFalse(policy.allowsAttachment("video/mp4"))
    }
}
