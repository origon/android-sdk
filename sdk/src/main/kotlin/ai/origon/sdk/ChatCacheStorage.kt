package ai.origon.sdk

import android.content.Context
import android.os.Build
import java.io.File

internal object ChatCacheStorage {
    fun credentialProtectedNoBackupDir(context: Context): File? {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && app.isDeviceProtectedStorage) {
            // Android exposes only CE -> DPS publicly, not the inverse. Never
            // weaken the storage boundary: a DPS/defaultToDPS host disables
            // durable chat caching instead of writing transcripts into DPS.
            return null
        }
        return app.noBackupFilesDir
    }

    fun root(context: Context): File? = credentialProtectedNoBackupDir(context)
        ?.resolve("ai.origon.sdk/chat-cache-v1")

    fun ensureRoot(context: Context): File? = root(context)?.also {
        check(it.mkdirs() || it.isDirectory) { "create protected chat cache root" }
    }
}
