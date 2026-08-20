package origon.example.android

import ai.origon.sdk.Attachment
import ai.origon.sdk.ChatAccessIntent
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.DisconnectReason
import ai.origon.sdk.Message
import ai.origon.sdk.MessageStatus
import ai.origon.sdk.SendMessagePayload
import ai.origon.sdk.SessionException
import ai.origon.sdk.SessionHistory
import ai.origon.sdk.SessionLoadPolicy
import ai.origon.sdk.SessionLoadSource
import ai.origon.sdk.SessionLoadUpdate
import ai.origon.sdk.SessionSnapshot
import ai.origon.sdk.StartChatOptions
import ai.origon.sdk.StartSessionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import origon.example.android.services.ChatService
import origon.example.android.services.ChatSessionClient
import origon.example.android.data.PendingAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDestinationPolicyTest {
    @Test
    fun cachePaintsBeforeNamedAccessAndCannotGrantSend() = runBlocking {
        val fixture = Fixture(this)
        val job = launch { fixture.service.openSession("saved") }
        fixture.fake.awaitRequests(1)
        fixture.fake.emit(0, snapshot(SessionLoadSource.CACHE, false, listOf(message("cached"))))
        fixture.await { fixture.service.stateFor("saved")?.loadState == ChatService.DestinationLoadState.CACHED }

        assertEquals(listOf("cached"), fixture.service.stateFor("saved")?.messages?.map { it.id })
        assertFalse(fixture.service.canSendFocusedSession)
        assertEquals(listOf("saved"), fixture.fake.accesses.map { it.id })
        assertEquals(listOf(ChatAccessIntent.EXPLICIT_NAVIGATION), fixture.fake.accesses.map { it.intent })

        fixture.fake.finish(0)
        fixture.fake.succeed(0)
        job.join()
        assertTrue(fixture.service.canSendFocusedSession)
        fixture.close()
    }

    @Test
    fun freshEmptyAndTypedRefreshFailureRemainDistinct() = runBlocking {
        val fixture = Fixture(this)
        val empty = launch { fixture.service.openSession("empty") }
        fixture.fake.awaitRequests(1)
        fixture.fake.emit(0, snapshot(SessionLoadSource.NETWORK, true, emptyList()))
        fixture.fake.finish(0)
        fixture.fake.succeed(0)
        empty.join()
        assertEquals(
            ChatService.DestinationLoadState.FRESH_EMPTY,
            fixture.service.stateFor("empty")?.loadState,
        )

        val offline = launch { fixture.service.openSession("offline") }
        fixture.fake.awaitRequests(2)
        fixture.fake.emit(1, snapshot(SessionLoadSource.CACHE, false, listOf(message("saved"))))
        fixture.fake.emit(1, SessionLoadUpdate.RefreshFailed(failure(), true))
        fixture.fake.finish(1)
        fixture.fake.fail(1)
        offline.join()
        assertEquals(
            ChatService.DestinationLoadState.REFRESH_FAILED_CACHED,
            fixture.service.stateFor("offline")?.loadState,
        )
        assertFalse(fixture.service.stateFor("offline")?.accessGranted ?: true)
        fixture.close()
    }

    @Test
    fun rapidDestinationClientReplacementAndCancellationFenceLateResults() = runBlocking {
        val fixture = Fixture(this)
        val firstA = launch { fixture.service.openSession("a") }
        fixture.fake.awaitRequests(1)
        val b = launch { fixture.service.openSession("b") }
        fixture.fake.awaitRequests(2)
        val finalA = launch { fixture.service.openSession("a") }
        fixture.fake.awaitRequests(3)

        fixture.fake.resolve(0, snapshot(SessionLoadSource.NETWORK, true, listOf(message("stale-a"))))
        fixture.fake.resolve(1, snapshot(SessionLoadSource.NETWORK, true, listOf(message("stale-b"))))
        fixture.fake.resolve(2, snapshot(SessionLoadSource.NETWORK, true, listOf(message("current-a"))))
        firstA.join()
        b.join()
        finalA.join()
        assertEquals("a", fixture.service.currentSessionId.value)
        assertEquals(listOf("current-a"), fixture.service.stateFor("a")?.messages?.map { it.id })
        assertNull(fixture.service.stateFor("b")?.messages?.firstOrNull { it.id == "stale-b" })

        val oldEndpoint = launch { fixture.service.openSession("old-endpoint") }
        fixture.fake.awaitRequests(4)
        fixture.service.clientWillChange()
        fixture.fake.resolve(3, snapshot(SessionLoadSource.NETWORK, true, listOf(message("late"))))
        oldEndpoint.join()
        assertFalse(fixture.service.stateFor("old-endpoint")?.accessGranted ?: true)
        assertFalse(fixture.service.stateFor("old-endpoint")?.messages?.any { it.id == "late" } ?: false)

        val cancelled = launch { fixture.service.openSession("cancelled") }
        fixture.fake.awaitRequests(5)
        cancelled.cancel()
        fixture.fake.resolve(4, snapshot(SessionLoadSource.NETWORK, true, listOf(message("cancel-late"))))
        cancelled.join()
        assertFalse(fixture.service.stateFor("cancelled")?.messages?.any { it.id == "cancel-late" } ?: false)
        fixture.close()
    }

    @Test
    fun reconciliationPreservesStableIdentityPreviewAndLiveTail() {
        val local = Message(
            id = "server-1", localId = "local-1", text = "old",
            attachments = listOf(Attachment("file", "photo", "image/jpeg", "old", "file:///preview")),
        )
        val live = message("live")
        val failed = Message(id = "", localId = "failed", text = "retry", status = MessageStatus.FAILED)
        val state = ChatService.SessionUIState(
            messages = listOf(local, live, failed),
            liveMessageKeys = setOf("live"),
        )
        val remote = Message(
            id = "server-1", text = "authoritative",
            attachments = listOf(Attachment("file", "photo", "image/jpeg", "new")),
        )

        val reconciled = ChatService.reconcile(listOf(remote), state)
        assertEquals(listOf("server-1", "live", ""), reconciled.messages.map { it.id })
        assertEquals("authoritative", reconciled.messages[0].text)
        assertEquals("local-1", reconciled.messages[0].localId)
        assertEquals("new", reconciled.messages[0].attachments[0].url)
        assertEquals("file:///preview", reconciled.messages[0].attachments[0].localUrl)
    }

    @Test
    fun reconciliationHandlesReorderEmptyCacheToNetworkAndIdempotentReplay() {
        val stale = message("stale")
        val sending = Message(id = "", localId = "sending", status = MessageStatus.SENDING)
        val failed = Message(id = "", localId = "failed", status = MessageStatus.FAILED)
        val state = ChatService.SessionUIState(messages = listOf(stale, sending, failed))
        val fetched = listOf(message("2"), message("1"))

        val cache = ChatService.reconcile(listOf(message("1")), state)
        val network = ChatService.reconcile(fetched, cache)
        assertEquals(listOf("2", "1", "", ""), network.messages.map { it.id })
        assertEquals(network.messages, ChatService.reconcile(fetched, network).messages)
        assertEquals(
            listOf("sending", "failed"),
            ChatService.reconcile(emptyList(), state).messages.map { it.localId },
        )
    }

    @Test
    fun deliveredIdAndFailureCorrelateToOneProvisionalRow() {
        val provisional = Message(id = "", localId = "sdk-local", text = "hello", status = MessageStatus.SENDING)
        val initial = ChatService.SessionUIState(
            messages = listOf(provisional), liveMessageKeys = setOf("sdk-local"),
        )
        val delivered = message("server-id")
        val deliveredState = ChatService.applyingMessageUpdate("sdk-local", delivered, initial)
        assertEquals(1, deliveredState.messages.size)
        assertEquals("server-id", deliveredState.messages[0].id)
        assertEquals("sdk-local", deliveredState.messages[0].localId)
        assertEquals(1, ChatService.reconcile(listOf(delivered), deliveredState).messages.size)

        val failed = Message(id = "", text = "hello", status = MessageStatus.FAILED, errorText = "offline")
        val failedState = ChatService.applyingMessageUpdate("sdk-local", failed, initial)
        assertEquals(1, failedState.messages.size)
        assertEquals(MessageStatus.FAILED, failedState.messages[0].status)
        assertEquals("sdk-local", failedState.messages[0].localId)
    }

    @Test
    fun eventReplayIsIdempotentAndUnknownUpdateWaitsForAuthoritativeSnapshot() {
        val inbound = message("inbound")
        val empty = ChatService.SessionUIState()
        val once = ChatService.applyingMessageAdded(inbound, empty)
        val twice = ChatService.applyingMessageAdded(inbound, once)
        assertEquals(1, twice.messages.size)

        val unknownDelivered = message("server-only")
        val unchanged = ChatService.applyingMessageUpdate("missing-local", unknownDelivered, twice)
        assertEquals(twice, unchanged)

        val authoritative = ChatService.reconcile(listOf(inbound, unknownDelivered), unchanged)
        assertEquals(listOf("inbound", "server-only"), authoritative.messages.map { it.id })
    }

    @Test
    fun reconnectPreservesTranscriptAndGapRefetches() = runBlocking {
        val fixture = Fixture(this)
        fixture.service.installStateForTesting(
            "chat",
            ChatService.SessionUIState(messages = listOf(message("before")), accessGranted = true),
        )

        fixture.service.receiveForTesting(ClientEvent.Reconnecting(
            "chat", 1, DisconnectReason.NetworkLoss,
        ))
        assertEquals(
            ChatService.ConnectionState.RECONNECTING,
            fixture.service.stateFor("chat")?.connectionState,
        )
        assertFalse(fixture.service.canSendFocusedSession)
        assertEquals(listOf("before"), fixture.service.stateFor("chat")?.messages?.map { it.id })

        fixture.service.receiveForTesting(ClientEvent.Reconnected("chat"))
        fixture.fake.awaitStreams(1)
        assertEquals(SessionLoadPolicy.NETWORK_ONLY, fixture.fake.policies[0])
        fixture.fake.emit(0, snapshot(
            SessionLoadSource.NETWORK, true, listOf(message("before"), message("missed")),
        ))
        fixture.fake.finish(0)
        fixture.await { fixture.service.stateFor("chat")?.messages?.map { it.id } ==
            listOf("before", "missed") }
        assertEquals(
            ChatService.ConnectionState.CONNECTED,
            fixture.service.stateFor("chat")?.connectionState,
        )
        assertTrue(fixture.service.canSendFocusedSession)
        fixture.close()
    }

    @Test
    fun droppedAttachmentFirstSendResumesSameId() = runBlocking {
        val fixture = Fixture(this)
        val attachment = Attachment("file", "photo.jpg", "image/jpeg", "https://example.invalid/file")
        val pending = PendingAttachment(
            id = "local-file", fileName = "photo.jpg", contentType = "image/jpeg",
            previewUri = null, status = PendingAttachment.Status.COMPLETED,
            progress = 100, attachment = attachment,
        )
        fixture.service.installStateForTesting(
            "chat",
            ChatService.SessionUIState(
                messages = listOf(message("kept")), accessGranted = false,
                connectionState = ChatService.ConnectionState.DROPPED,
                pendingAttachments = listOf(pending),
            ),
        )

        fixture.service.sendMessage("")

        assertEquals(1, fixture.fake.starts.size)
        assertEquals("chat", fixture.fake.starts[0].sessionId)
        assertEquals(listOf("file"), fixture.fake.starts[0].firstMessage.attachments.map { it.id })
        assertEquals(listOf("kept"), fixture.service.stateFor("chat")?.messages?.map { it.id })
        assertEquals(
            ChatService.ConnectionState.CONNECTED,
            fixture.service.stateFor("chat")?.connectionState,
        )
        assertTrue(fixture.service.stateFor("chat")?.pendingAttachments?.isEmpty() == true)
        fixture.close()
    }

    @Test
    fun cleanEndIsReadOnlyAndIgnoresStaleReconnect() = runBlocking {
        val fixture = Fixture(this)
        fixture.service.installStateForTesting(
            "chat",
            ChatService.SessionUIState(messages = listOf(message("kept")), accessGranted = true),
        )
        fixture.service.receiveForTesting(ClientEvent.ChatSessionEnded("chat", "complete"))
        fixture.service.receiveForTesting(ClientEvent.Reconnected("chat"))
        fixture.service.sendMessage("blocked")

        assertEquals(
            ChatService.ConnectionState.ENDED,
            fixture.service.stateFor("chat")?.connectionState,
        )
        assertFalse(fixture.service.canSendFocusedSession)
        assertEquals(listOf("kept"), fixture.service.stateFor("chat")?.messages?.map { it.id })
        assertTrue(fixture.fake.starts.isEmpty())
        assertTrue(fixture.fake.sent.isEmpty())
        fixture.close()
    }

    @Test
    fun clientEpochFencesLateEventsAndRefocusRefetches() = runBlocking {
        val fixture = Fixture(this)
        fixture.service.installStateForTesting(
            "chat", ChatService.SessionUIState(messages = listOf(message("kept"))),
        )
        fixture.service.clientWillChange()
        fixture.service.receiveForTesting(ClientEvent.MessageAdded("chat", message("stale")))
        assertEquals(listOf("kept"), fixture.service.stateFor("chat")?.messages?.map { it.id })

        fixture.service.clientDidChange()
        fixture.service.refetchFocusedSession()
        fixture.fake.awaitStreams(1)
        fixture.fake.emit(0, snapshot(
            SessionLoadSource.NETWORK, true, listOf(message("kept"), message("refocused")),
        ))
        fixture.fake.finish(0)
        fixture.await { fixture.service.stateFor("chat")?.messages?.map { it.id } ==
            listOf("kept", "refocused") }
        fixture.close()
    }

    private class Fixture(parent: CoroutineScope) {
        private val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val fake = FakeChatSessionClient()
        val service = ChatService(scope, emptyFlow(), { null }, { fake }, {})

        suspend fun await(predicate: () -> Boolean) {
            repeat(500) { if (predicate()) return; yield() }
            assertTrue(predicate())
        }

        fun close() { scope.cancel() }
    }

    private class FakeChatSessionClient : ChatSessionClient {
        data class Access(
            val id: String,
            val intent: ChatAccessIntent,
            val result: CompletableDeferred<StartSessionResponse>,
        )

        val streams = mutableListOf<Channel<SessionLoadUpdate>>()
        val policies = mutableListOf<SessionLoadPolicy>()
        val accesses = mutableListOf<Access>()
        val starts = mutableListOf<StartChatOptions>()
        val sent = mutableListOf<Pair<String, SendMessagePayload>>()

        override fun sessionUpdates(id: String, policy: SessionLoadPolicy): Flow<SessionLoadUpdate> {
            policies += policy
            return Channel<SessionLoadUpdate>(Channel.UNLIMITED).also(streams::add).receiveAsFlow()
        }

        override suspend fun acquireChatAccess(
            id: String,
            intent: ChatAccessIntent,
        ): StartSessionResponse = CompletableDeferred<StartSessionResponse>().let { result ->
            accesses += Access(id, intent, result)
            result.await()
        }

        override suspend fun startChat(options: StartChatOptions): StartSessionResponse {
            starts += options
            return StartSessionResponse(
                options.sessionId ?: "new-chat", "https://example.invalid", "test",
            )
        }

        override suspend fun sendMessage(id: String, payload: SendMessagePayload) {
            sent += id to payload
        }

        suspend fun awaitRequests(count: Int) {
            repeat(500) { if (streams.size >= count && accesses.size >= count) return; yield() }
            assertEquals(count, streams.size)
            assertEquals(count, accesses.size)
        }

        suspend fun awaitStreams(count: Int) {
            repeat(500) { if (streams.size >= count) return; yield() }
            assertEquals(count, streams.size)
        }

        fun emit(index: Int, update: SessionLoadUpdate) { streams[index].trySend(update).getOrThrow() }
        fun finish(index: Int) { streams[index].close() }
        fun succeed(index: Int) {
            val id = accesses[index].id
            accesses[index].result.complete(StartSessionResponse(id, "https://example.invalid", "test"))
        }
        fun fail(index: Int) { accesses[index].result.completeExceptionally(failure()) }
        fun resolve(index: Int, update: SessionLoadUpdate) {
            emit(index, update)
            finish(index)
            succeed(index)
        }
    }

    companion object {
        private fun message(id: String): Message = Message(id = id, text = id)
        private fun snapshot(
            source: SessionLoadSource,
            authoritative: Boolean,
            messages: List<Message>,
        ): SessionLoadUpdate = SessionLoadUpdate.Snapshot(
            SessionSnapshot(source, authoritative, 1, SessionHistory(messages)),
        )
        private fun failure(): SessionException = SessionException(8, 0, "offline", "offline")
    }
}
