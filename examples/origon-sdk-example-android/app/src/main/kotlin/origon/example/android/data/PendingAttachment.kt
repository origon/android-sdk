package origon.example.android.data

import android.net.Uri
import ai.origon.sdk.Attachment

/**
 * One pending-upload tile in the chat composer.
 *
 * [id] is a local UUID assigned at pick time. It serves two purposes:
 * (1) the stable list key for the composer's tile row; and (2) the
 * `uploadId` passed to `client.uploadAttachment(...)` so that
 * `client.cancelUpload(id)` can cancel the upload in-flight without a
 * network request. Completed draft removal changes local state only.
 * Once the upload completes, [attachment] holds the
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
