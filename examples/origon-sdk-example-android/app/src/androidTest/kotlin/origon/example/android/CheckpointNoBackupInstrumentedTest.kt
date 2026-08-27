package origon.example.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import origon.example.android.services.ExampleCheckpointStore
import origon.example.android.services.NoBackupCheckpointFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckpointNoBackupInstrumentedTest {
    @Test
    fun rowsAndEpochStayUnderNoBackupAndRowsContainNoRawScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = NoBackupCheckpointFiles(context)
        files.directory.deleteRecursively()
        val store = ExampleCheckpointStore(files)
        val endpoint = "https://example.invalid/private-endpoint"
        val session = "private-session"
        store.markSeen(endpoint, session, "message", true, true, true, true, 100)

        assertTrue(files.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val rows = File(files.directory, "rows-v1.json").readText()
        assertFalse(rows.contains(endpoint))
        assertFalse(rows.contains(session))
        assertTrue(File(files.directory, "epoch-v1").length() == 32L)
    }
}
