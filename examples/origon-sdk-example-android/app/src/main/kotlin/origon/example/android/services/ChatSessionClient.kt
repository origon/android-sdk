package origon.example.android.services

import ai.origon.sdk.ChatAccessIntent
import ai.origon.sdk.OrigonClient
import ai.origon.sdk.SessionLoadUpdate
import ai.origon.sdk.StartSessionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Narrow app-owned seam for deterministic destination-policy tests. */
internal interface ChatSessionClient {
    fun sessionUpdates(id: String): Flow<SessionLoadUpdate>
    suspend fun acquireChatAccess(id: String, intent: ChatAccessIntent): StartSessionResponse
}

internal class OrigonChatSessionClient(
    private val client: OrigonClient,
) : ChatSessionClient {
    override fun sessionUpdates(id: String): Flow<SessionLoadUpdate> = client.sessionUpdates(id)

    override suspend fun acquireChatAccess(
        id: String,
        intent: ChatAccessIntent,
    ): StartSessionResponse = withContext(Dispatchers.IO) { client.openChat(id, intent) }
}
