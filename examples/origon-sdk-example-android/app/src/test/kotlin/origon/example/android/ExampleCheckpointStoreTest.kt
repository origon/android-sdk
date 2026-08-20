package origon.example.android

import ai.origon.sdk.Message
import ai.origon.sdk.MessageRole
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import origon.example.android.services.EXAMPLE_CHECKPOINT_MAX_AGE_MS
import origon.example.android.services.EXAMPLE_CHECKPOINT_MAX_ENTRIES
import origon.example.android.services.ExampleCheckpoint
import origon.example.android.services.ExampleCheckpointFiles
import origon.example.android.services.ExampleCheckpointStore
import origon.example.android.services.exampleCheckpointScopeKey
import origon.example.android.services.exampleNewestEligibleMessageId
import origon.example.android.services.exampleShouldAdvanceCheckpoint
import origon.example.android.services.exampleUnreadAnchor
import origon.example.android.services.pruneExampleCheckpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExampleCheckpointStoreTest {
    private fun row(
        id: String,
        role: MessageRole = MessageRole.USER,
        action: String? = null,
    ) = Message(role = role, id = id, action = action, text = id)

    @Test
    fun `scope matches Apple vector and length prefixes prevent collision`() {
        val epoch = ByteArray(32) { it.toByte() }
        assertEquals(
            "1d2f8669466130bea1f357b8e8e54c7b05a57421c789e51300c0368053dac18f",
            exampleCheckpointScopeKey(
                epoch, "https://example.invalid/chat/api/widget", "session-α",
            ),
        )
        assertNotEquals(
            exampleCheckpointScopeKey(epoch, "ab", "c"),
            exampleCheckpointScopeKey(epoch, "a", "bc"),
        )
    }

    @Test
    fun `qualification first visit authority terminal and pruning are fail closed`() {
        val rows = listOf(
            row("seen"), row("own", MessageRole.EXTERNAL), row("flow", MessageRole.SYSTEM),
            row("joined", action = "joined"), row("first-new"), row(""),
        )
        assertEquals(4, exampleUnreadAnchor(rows, "seen"))
        assertNull(exampleUnreadAnchor(rows, null))
        assertNull(exampleUnreadAnchor(rows, "evicted"))
        assertEquals("first-new", exampleNewestEligibleMessageId(rows))
        assertFalse(exampleShouldAdvanceCheckpoint(false, true, true, true, "first-new"))
        assertFalse(exampleShouldAdvanceCheckpoint(true, false, true, true, "first-new"))
        assertFalse(exampleShouldAdvanceCheckpoint(true, true, false, true, "first-new"))
        assertTrue(exampleShouldAdvanceCheckpoint(true, true, true, true, "first-new"))

        val now = 10_000_000_000L
        val recent = (0 until 105).map {
            ExampleCheckpoint(scopeKey = "key-$it", lastSeenMessageId = "$it", lastAccessedAt = now - it)
        }
        val old = ExampleCheckpoint(
            scopeKey = "old", lastSeenMessageId = "old",
            lastAccessedAt = now - EXAMPLE_CHECKPOINT_MAX_AGE_MS - 1,
        )
        val future = old.copy(scopeKey = "future", lastAccessedAt = now + 1)
        val pruned = pruneExampleCheckpoints(recent + old + future, now)
        assertEquals(EXAMPLE_CHECKPOINT_MAX_ENTRIES, pruned.size)
        assertEquals("key-0", pruned.first().scopeKey)
        assertEquals("key-99", pruned.last().scopeKey)
    }

    @Test
    fun `serialized writes raw scan scope change restore and interruption`() = runBlocking {
        val files = FakeFiles(ByteArray(32) { it.toByte() })
        val store = ExampleCheckpointStore(files, Dispatchers.Default)
        val endpoint = "https://example.invalid/chat/api/widget"
        val first = async {
            store.markSeen(endpoint, "session", "one", true, true, true, true, 10)
        }
        val second = async {
            first.await()
            store.markSeen(endpoint, "session", "two", true, true, true, true, 20)
        }
        second.await()
        assertEquals("two", store.read(endpoint, "session", 30)?.lastSeenMessageId)
        assertNull(store.read("$endpoint/other", "session", 30))
        val raw = files.rows!!.decodeToString()
        assertFalse(raw.contains(endpoint))
        assertFalse(raw.contains("session"))
        assertFalse(raw.contains(files.epoch.decodeToString()))

        files.failNext = true
        assertFailsWith<IOException> {
            store.markSeen(endpoint, "session", "lost", true, true, true, true, 40)
        }
        assertEquals("two", store.read(endpoint, "session", 50)?.lastSeenMessageId)
        store.markSeen(endpoint, "session", "cached", false, true, true, true, 60)
        assertEquals("two", store.read(endpoint, "session", 70)?.lastSeenMessageId)

        files.epoch = ByteArray(32) { 0x5A }
        assertNull(store.read(endpoint, "session", 80))
    }
}

private class FakeFiles(var epoch: ByteArray) : ExampleCheckpointFiles {
    var rows: ByteArray? = null
    var failNext = false

    override fun loadOrCreateEpoch(): ByteArray = epoch.copyOf()
    override fun readRows(): ByteArray? = rows?.copyOf()
    override fun replaceRows(data: ByteArray) {
        if (failNext) {
            failNext = false
            throw IOException("interrupted")
        }
        rows = data.copyOf()
    }
}
