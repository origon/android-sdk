package ai.origon.sdk

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class SessionBridgeApiContractTest {
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
        assertTrue(snapshot.config.attachmentPolicy.images.enabled)
    }
}
