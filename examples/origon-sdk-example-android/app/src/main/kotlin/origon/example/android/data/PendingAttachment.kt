package origon.example.android.data

import android.net.Uri
import ai.origon.sdk.Attachment

/**
 * One pending-upload tile in the chat composer.
 *
 * [id] is a local UUID assigned at pick time. It serves two purposes:
 * (1) the stable list key for the RecyclerView adapter; and (2) the
 * `uploadId` passed to `client.uploadAttachment(...)` so that
 * `client.deleteAttachment(id)` can cancel the upload in-flight (the
 * SDK's dual-purpose deleteAttachment matches the id against its
 * in-flight upload table first, then falls through to a server-side
 * DELETE). Once the upload completes, [attachment] holds the
 * server-issued [Attachment].
 */
data class PendingAttachment(
    val id: String,
    val fileName: String,
    val contentType: String,
    val previewUri: Uri?,
    val status: Status,
    val progress: Int = 0,
    val attachment: Attachment? = null,
    val errorText: String? = null,
) {
    enum class Status { UPLOADING, COMPLETED, ERROR }

    val isImage: Boolean get() = contentType.startsWith("image/")

    val fileExtension: String
        get() = fileName.substringAfterLast('.', "").uppercase()
}
