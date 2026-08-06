package origon.example.android.ui.components

import ai.origon.sdk.Attachment
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import origon.example.android.services.Http
import java.io.IOException

/**
 * The one SAF download helper — `MessageBubble`'s capsule affordance and
 * `AttachmentsPreview`'s header both go through this.
 *
 * iOS hands the attachment URL to the system and Safari's download manager
 * takes it from there. Android has no such handoff — `ACTION_VIEW` renders
 * instead of saving — so the platform's save flow is SAF:
 * `ACTION_CREATE_DOCUMENT` lets the user pick the destination, then the bytes
 * stream into the chosen document. The fetch carries no auth headers: the
 * attachment GET needs none, and the wire's `url` is used verbatim.
 *
 * [onResult] fires on the caller's UI with `null` on success or a user-facing
 * message on failure — the feedback surface is the host's (toast). A failed or
 * cancelled copy deletes the half-written document rather than leaving a
 * truncated file that looks downloaded.
 */
@Stable
class AttachmentDownloader internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    internal var onResult: (String?) -> Unit = {}
    internal var launchPicker: (Pair<String, String>) -> Unit = {}

    /**
     * The attachment whose picker is on screen. SAF returns only a Uri, so the
     * request context rides here; one picker can be up at a time, which the
     * system guarantees (the document UI is a full-screen activity).
     */
    private var pending: Attachment? = null

    /** Pick a destination for [attachment], then stream it there. */
    fun download(attachment: Attachment) {
        if (attachment.url.isBlank()) {
            // The same degenerate wire payload MessageBubble's thumbnail guards
            // against; iOS hides its download button for it.
            onResult("Couldn't download ${attachment.name}")
            return
        }
        pending = attachment
        launchPicker(
            attachment.contentType.ifBlank { FALLBACK_MIME } to
                attachment.name.ifBlank { "attachment" },
        )
    }

    internal fun onDestinationPicked(uri: Uri?) {
        val attachment = pending
        pending = null
        // Null uri = the user backed out of the picker; not an error.
        if (uri == null) return
        if (attachment == null) {
            // Process death while the picker was up: the provider already
            // created a 0-byte document at the user's chosen location, and the
            // request context died with the process. Remove the residue rather
            // than leave an empty file that looks downloaded.
            scope.launch { removeResidue(uri) }
            return
        }
        scope.launch { copy(attachment, uri) }
    }

    private suspend fun copy(attachment: Attachment, dest: Uri) {
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(attachment.url).build()
                Http.shared.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    // "wt", not the "w" default: CREATE_DOCUMENT lets the user
                    // overwrite an existing file, and per the platform docs "w"
                    // may not truncate on some providers — a shorter download
                    // over a longer file would keep the old tail and report the
                    // corrupt result as saved.
                    context.contentResolver.openOutputStream(dest, "wt")?.use { out ->
                        response.body.byteStream().copyTo(out)
                    } ?: throw IOException("no output stream for $dest")
                }
            }
            onResult(null)
        } catch (e: CancellationException) {
            removeResidue(dest)
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "download ${attachment.name} failed: $e")
            removeResidue(dest)
            onResult("Couldn't download ${attachment.name}")
        }
    }

    /** Best-effort delete, off the main thread even when arriving cancelled. */
    private suspend fun removeResidue(dest: Uri) =
        withContext(NonCancellable + Dispatchers.IO) { deleteQuietly(dest) }

    private fun deleteQuietly(dest: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, dest)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't remove partial download $dest: $e")
        }
    }

    private companion object {
        const val TAG = "AttachmentDownloader"
        const val FALLBACK_MIME = "application/octet-stream"
    }
}

/**
 * A downloader bound to this composition. [onResult] is kept current across
 * recompositions, so the host can close over changing state (its toast).
 */
@Composable
fun rememberAttachmentDownloader(onResult: (String?) -> Unit): AttachmentDownloader {
    val context = LocalContext.current.applicationContext
    // Deliberately NOT rememberCoroutineScope: a download must survive the
    // overlay that started it. Tied to the composition, dismissing the preview
    // mid-save cancels the copy — at the exact moment the bytes finish writing
    // — and then deletes the user's completed document. A result arriving after
    // disposal lands in a toast nobody renders, which is the correct degraded
    // case.
    val downloader = remember {
        AttachmentDownloader(
            context,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    downloader.onResult = onResult
    val launcher = rememberLauncherForActivityResult(CreateDocument) { uri ->
        downloader.onDestinationPicked(uri)
    }
    downloader.launchPicker = { input -> launcher.launch(input) }
    return downloader
}

/**
 * `ACTION_CREATE_DOCUMENT` with a per-launch MIME. The stock
 * `ActivityResultContracts.CreateDocument` fixes the MIME at contract
 * construction, but an attachment's type is data — one launcher must serve
 * `image/png` and `application/pdf` alike, hence this two-field input.
 */
private object CreateDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.first
            putExtra(Intent.EXTRA_TITLE, input.second)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
