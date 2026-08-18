package ai.origon.sdk

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCacheStorageInstrumentedTest {
    @Test
    fun testCredentialNoBackupRootAndIdempotentStaticClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = checkNotNull(ChatCacheStorage.ensureRoot(context))
        assertTrue(root.path.endsWith("/no_backup/ai.origon.sdk/chat-cache-v1"))
        val marker = File(root, "marker")
        marker.writeText("confidential")

        runBlocking { OrigonClient.clearAllChatCaches(context) }
        assertFalse(marker.exists())
        runBlocking { OrigonClient.clearAllChatCaches(context) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceProtected = context.createDeviceProtectedStorageContext()
            assertTrue(deviceProtected.isDeviceProtectedStorage)
            val fromDps = ChatCacheStorage.ensureRoot(deviceProtected)
            if (deviceProtected.applicationContext.isDeviceProtectedStorage) {
                assertNull(fromDps)
            } else {
                // Ordinary apps resolve their global application context back
                // to CE even when handed a derived DPS context.
                assertNotNull(fromDps)
                assertFalse(fromDps!!.path.contains("/user_de/"))
            }
        }
    }
}
