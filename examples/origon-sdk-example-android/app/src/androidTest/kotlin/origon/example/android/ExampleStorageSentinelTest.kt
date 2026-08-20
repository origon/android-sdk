package origon.example.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleStorageSentinelTest {
    @Test
    fun noBackupStorageIsAvailableToExampleIntegrationTests() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val noBackupRoot = context.noBackupFilesDir.canonicalFile

        assertTrue(noBackupRoot.isDirectory || noBackupRoot.mkdirs())
        assertFalse(noBackupRoot.path.contains("shared_prefs"))
    }
}
