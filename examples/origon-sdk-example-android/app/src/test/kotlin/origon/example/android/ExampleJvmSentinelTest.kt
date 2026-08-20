package origon.example.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleJvmSentinelTest {
    @Test
    fun examplePolicyTestsExecuteInTheApplicationModule() {
        assertEquals("origon.example.android", BuildConfig.APPLICATION_ID)
    }
}
