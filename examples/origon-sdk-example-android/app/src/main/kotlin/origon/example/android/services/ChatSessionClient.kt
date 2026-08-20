package origon.example.android.services

import ai.origon.sdk.ChatAccessIntent
import ai.origon.sdk.OrigonClient
import ai.origon.sdk.SendMessagePayload
import ai.origon.sdk.SessionLoadPolicy
import ai.origon.sdk.SessionLoadUpdate
import ai.origon.sdk.StartChatOptions
import ai.origon.sdk.StartSessionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Narrow app-owned seam for deterministic destination-policy tests. */
internal interface ChatSessionClient {
    fun sessionUpdates(
        id: String,
        policy: SessionLoadPolicy = SessionLoadPolicy.CACHE_THEN_NETWORK,
    ): Flow<SessionLoadUpdate>
    suspend fun acquireChatAccess(id: String, intent: ChatAccessIntent): StartSessionResponse
    suspend fun startChat(options: StartChatOptions): StartSessionResponse
    suspend fun sendMessage(id: String, payload: SendMessagePayload)
}

internal class OrigonChatSessionClient(
    private val client: OrigonClient,
) : ChatSessionClient {
    override fun sessionUpdates(id: String, policy: SessionLoadPolicy): Flow<SessionLoadUpdate> =
        client.sessionUpdates(id, policy)

    override suspend fun acquireChatAccess(
        id: String,
        intent: ChatAccessIntent,
    ): StartSessionResponse = withContext(Dispatchers.IO) { client.openChat(id, intent) }

    override suspend fun startChat(options: StartChatOptions): StartSessionResponse =
        withContext(Dispatchers.IO) { client.startChat(options) }

    override suspend fun sendMessage(id: String, payload: SendMessagePayload) {
        withContext(Dispatchers.IO) { client.sendMessage(id, payload) }
    }
}
