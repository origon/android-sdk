package ai.origon.sdk

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class SessionBridgeApiContractTest {
    @Test
    fun uploadCancellationMatchesNativeBooleanDescriptor() {
        assertEquals(
            Boolean::class.javaPrimitiveType,
            SessionBridge::class.java.getDeclaredMethod(
                "cancelUpload", Long::class.javaPrimitiveType, String::class.java,
            ).returnType,
        )
        assertEquals(
            Boolean::class.javaPrimitiveType,
            OrigonClient::class.java.getDeclaredMethod("cancelUpload", String::class.java).returnType,
        )
        for (type in listOf(SessionBridge::class.java, OrigonClient::class.java)) {
            assertTrue(type.declaredMethods.none { it.name == "deleteAttachment" })
        }
    }

    @Test
    fun initializeAndConfigBridgeDescriptorsAreExact() {
        val bridge = SessionBridge::class.java
        assertEquals(
            Long::class.javaPrimitiveType,
            bridge.getDeclaredMethod(
                "initialize",
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            ).returnType,
        )
        assertEquals(
            String::class.java,
            bridge.getDeclaredMethod("serverConfig", Long::class.javaPrimitiveType).returnType,
        )
        assertEquals(
            Long::class.javaPrimitiveType,
            bridge.getDeclaredMethod("configLoaderStart", Long::class.javaPrimitiveType).returnType,
        )
        assertEquals(
            Long::class.javaPrimitiveType,
            bridge.getDeclaredMethod("configRetry", Long::class.javaPrimitiveType).returnType,
        )
        assertEquals(
            Void.TYPE,
            bridge.getDeclaredMethod("destroy", Long::class.javaPrimitiveType).returnType,
        )
    }

    @Test
    fun publicConstructorAndConfigFlowShapesArePresent() {
        assertNotNull(
            OrigonClient::class.java.getConstructor(Context::class.java, ClientConfig::class.java),
        )
        val updates: (OrigonClient) -> Flow<ServerConfigLoadUpdate> =
            OrigonClient::serverConfigUpdates
        val retry: (OrigonClient) -> Flow<ServerConfigLoadUpdate> =
            OrigonClient::retryServerConfig
        assertNotNull(updates)
        assertNotNull(retry)
    }

    @Test
    fun configSnapshotDecodesAsOneGeneration() {
        val snapshot = Json.decodeFromString<ServerConfigLoadSnapshot>(
            """{"source":"cache","authoritative":false,"refreshedAt":7,"config":{"startMessage":"Hello","multipleChannels":true,"chatEnabled":true,"callEnabled":false,"attachmentPolicy":{"images":{"enabled":true,"maxSize":5},"documents":{"enabled":false,"maxSize":0},"videos":{"enabled":false,"maxSize":0},"audio":{"enabled":false,"maxSize":0}}}}""",
        )
        assertEquals(SessionLoadSource.CACHE, snapshot.source)
        assertEquals(7L, snapshot.refreshedAt)
        assertEquals("Hello", snapshot.config.startMessage)
        assertTrue(snapshot.config.isChatEnabled)
        // A core without `chatEndEnabled` (this fixture) offers End.
        assertTrue(snapshot.config.isChatEndEnabled)
        assertTrue(snapshot.config.attachmentPolicy.images.enabled)
    }

    @Test
    fun configCarriesWhetherEndIsOffered() {
        val policy = """"attachmentPolicy":{"images":{"enabled":false,"maxSize":0},"documents":{"enabled":false,"maxSize":0},"videos":{"enabled":false,"maxSize":0},"audio":{"enabled":false,"maxSize":0}}"""
        fun decode(end: String) = Json.decodeFromString<ServerConfig>(
            """{"startMessage":"","multipleChannels":false,"chatEnabled":true,$end"callEnabled":false,$policy}""",
        )
        assertFalse(decode(""""chatEndEnabled":false,""").isChatEndEnabled)
        assertTrue(decode(""""chatEndEnabled":true,""").isChatEndEnabled)
        assertTrue(decode("").isChatEndEnabled)
        assertFalse(ServerConfig.DISABLED.isChatEndEnabled)
    }
}
