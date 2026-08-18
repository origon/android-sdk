package ai.origon.sdk

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal object InstallationIdentity {
    @Synchronized
    fun loadOrCreate(context: Context): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "ai.origon.sdk/installation-id"))
        file.baseFile.parentFile?.mkdirs()
        runCatching {
            file.openRead().bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.let { existing ->
            runCatching { UUID.fromString(existing).toString() }.getOrNull()?.let { return it }
        }

        val value = UUID.randomUUID().toString()
        val output = file.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        return value
    }
}
