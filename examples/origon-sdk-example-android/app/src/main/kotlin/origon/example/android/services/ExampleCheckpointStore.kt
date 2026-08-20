package origon.example.android.services

import ai.origon.sdk.Message
import ai.origon.sdk.MessageRole
import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val EXAMPLE_CHECKPOINT_VERSION = 1
internal const val EXAMPLE_CHECKPOINT_MAX_ENTRIES = 100
internal const val EXAMPLE_NEW_MESSAGES_ACCESSIBILITY_LABEL = "New messages"
internal val EXAMPLE_CHECKPOINT_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(30)

internal data class ExampleCheckpoint(
    val version: Int = EXAMPLE_CHECKPOINT_VERSION,
    val scopeKey: String,
    val lastSeenMessageId: String,
    val lastAccessedAt: Long,
)

internal interface ExampleCheckpointFiles {
    fun loadOrCreateEpoch(): ByteArray
    fun readRows(): ByteArray?
    fun replaceRows(data: ByteArray)
}

internal class NoBackupCheckpointFiles(context: Context) : ExampleCheckpointFiles {
    internal val directory = File(context.noBackupFilesDir, "origon-example-checkpoint-v1")
    private val epochFile = AtomicFile(File(directory, "epoch-v1"))
    private val rowsFile = AtomicFile(File(directory, "rows-v1.json"))

    override fun loadOrCreateEpoch(): ByteArray {
        directory.mkdirs()
        if (epochFile.baseFile.exists()) {
            return epochFile.readFully().also { require(it.size == 32) }
        }
        val epoch = ByteArray(32).also(SecureRandom()::nextBytes)
        writeAtomic(epochFile, epoch)
        return epoch
    }

    override fun readRows(): ByteArray? =
        rowsFile.baseFile.takeIf(File::exists)?.let { rowsFile.readFully() }

    override fun replaceRows(data: ByteArray) {
        directory.mkdirs()
        writeAtomic(rowsFile, data)
    }

    private fun writeAtomic(file: AtomicFile, data: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(data)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}

/** One mutex-serialized I/O lane; all file and digest work is dispatched off main. */
internal class ExampleCheckpointStore(
    private val files: ExampleCheckpointFiles,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    suspend fun read(endpoint: String, sessionId: String, now: Long): ExampleCheckpoint? =
        withContext(dispatcher) {
            mutex.withLock {
                val key = exampleCheckpointScopeKey(files.loadOrCreateEpoch(), endpoint, sessionId)
                val rows = loadRows().toMutableList()
                val index = rows.indexOfFirst { it.scopeKey == key }
                if (index < 0) return@withLock null
                val found = rows[index]
                rows[index] = found.copy(lastAccessedAt = now)
                files.replaceRows(encodeExampleCheckpoints(pruneExampleCheckpoints(rows, now)))
                found
            }
        }

    suspend fun markSeen(
        endpoint: String,
        sessionId: String,
        messageId: String?,
        authoritative: Boolean,
        foreground: Boolean,
        detailVisible: Boolean,
        latestRowVisible: Boolean,
        now: Long,
    ) = withContext(dispatcher) {
        mutex.withLock {
            if (!exampleShouldAdvanceCheckpoint(
                    authoritative, foreground, detailVisible, latestRowVisible, messageId,
                )
            ) return@withLock
            val id = requireNotNull(messageId)
            val key = exampleCheckpointScopeKey(files.loadOrCreateEpoch(), endpoint, sessionId)
            val next = loadRows().filterNot { it.scopeKey == key } + ExampleCheckpoint(
                scopeKey = key,
                lastSeenMessageId = id,
                lastAccessedAt = now,
            )
            files.replaceRows(encodeExampleCheckpoints(pruneExampleCheckpoints(next, now)))
        }
    }

    private fun loadRows(): List<ExampleCheckpoint> = files.readRows()
        ?.let { runCatching { decodeExampleCheckpoints(it) }.getOrNull() }
        .orEmpty()
        .filter { it.version == EXAMPLE_CHECKPOINT_VERSION }
}

private fun encodeExampleCheckpoints(rows: List<ExampleCheckpoint>): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(0x4F435031) // OCP1
        output.writeInt(rows.size)
        rows.forEach { row ->
            output.writeInt(row.version)
            output.writeLengthPrefixed(row.scopeKey)
            output.writeLengthPrefixed(row.lastSeenMessageId)
            output.writeLong(row.lastAccessedAt)
        }
    }
    return bytes.toByteArray()
}

private fun decodeExampleCheckpoints(bytes: ByteArray): List<ExampleCheckpoint> =
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 0x4F435031)
        val count = input.readInt()
        require(count in 0..1_000)
        List(count) {
            ExampleCheckpoint(
                version = input.readInt(),
                scopeKey = input.readLengthPrefixed(),
                lastSeenMessageId = input.readLengthPrefixed(),
                lastAccessedAt = input.readLong(),
            )
        }.also { require(input.read() == -1) }
    }

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 4_096)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readLengthPrefixed(): String {
    val length = readInt()
    require(length in 0..4_096)
    return ByteArray(length).also(::readFully).decodeToString()
}

internal fun exampleCheckpointScopeKey(
    epoch: ByteArray,
    endpoint: String,
    sessionId: String,
): String {
    require(epoch.size == 32)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("origon-example-checkpoint-v1".encodeToByteArray())
    digest.update(epoch)
    listOf(endpoint, sessionId).forEach { value ->
        val bytes = value.encodeToByteArray()
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun Message.qualifiesForExampleUnread(): Boolean =
    role == MessageRole.USER && action.isNullOrEmpty() && id.isNotEmpty()

internal fun exampleUnreadAnchor(messages: List<Message>, checkpointId: String?): Int? {
    if (checkpointId.isNullOrEmpty()) return null
    val checkpointIndex = messages.indexOfFirst { it.id == checkpointId }
    if (checkpointIndex < 0) return null
    return messages.indices.firstOrNull {
        it > checkpointIndex && messages[it].qualifiesForExampleUnread()
    }
}

internal fun exampleNewestEligibleMessageId(messages: List<Message>): String? =
    messages.lastOrNull { it.qualifiesForExampleUnread() }?.id

internal fun exampleShouldAdvanceCheckpoint(
    authoritative: Boolean,
    foreground: Boolean,
    detailVisible: Boolean,
    latestRowVisible: Boolean,
    newestEligibleId: String?,
): Boolean = authoritative && foreground && detailVisible && latestRowVisible && newestEligibleId != null

internal fun pruneExampleCheckpoints(records: List<ExampleCheckpoint>, now: Long): List<ExampleCheckpoint> =
    records.asSequence()
        .filter { now - it.lastAccessedAt in 0..EXAMPLE_CHECKPOINT_MAX_AGE_MS }
        .sortedByDescending(ExampleCheckpoint::lastAccessedAt)
        .take(EXAMPLE_CHECKPOINT_MAX_ENTRIES)
        .toList()

internal data class ExampleTranscriptDecision(val followTail: Boolean, val consumeSend: Boolean)

internal fun exampleTranscriptDecision(
    explicitSendPending: Boolean,
    outgoingKeysBeforeSend: Set<String>,
    outgoingKeysNow: Set<String>,
    positioned: Boolean,
    wasAtTail: Boolean,
): ExampleTranscriptDecision {
    if (explicitSendPending && (outgoingKeysNow - outgoingKeysBeforeSend).isNotEmpty()) {
        return ExampleTranscriptDecision(followTail = true, consumeSend = true)
    }
    return ExampleTranscriptDecision(followTail = positioned && wasAtTail, consumeSend = false)
}

internal fun Message.exampleStableKey(index: Int): String =
    localId?.takeIf(String::isNotEmpty)?.let { "local-$it" }
        ?: id.takeIf(String::isNotEmpty)?.let { "server-$it" }
        ?: "index-$index"
