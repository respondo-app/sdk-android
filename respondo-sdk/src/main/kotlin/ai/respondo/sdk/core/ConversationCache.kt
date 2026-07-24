package ai.respondo.sdk.core

import ai.respondo.sdk.identity.StorageKeys
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import kotlinx.serialization.Serializable

/** Кэшированный блоб беседы (последние [ConversationCache.MAX_MESSAGES] сообщений). Источник правды — бэкенд. */
@Serializable
data class CachedConversation(
    val conversationId: String? = null,
    val sessionToken: String? = null,
    val escalated: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val timestamp: Long = 0L,
)

/**
 * Локальный кэш беседы: TTL 24ч, последние 50 сообщений. При наличии сети источник правды — бэкенд
 * (resume при старте, history при листании). Кэш переживает перезапуск приложения.
 */
class ConversationCache(
    private val store: KeyValueStore,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    /** Загружает блоб; возвращает null, если ключа нет или TTL истёк. */
    fun load(agentId: String?, channelId: String?): CachedConversation? {
        val raw = store.getString(StorageKeys.conversation(agentId, channelId)) ?: return null
        return try {
            val cached = respondoJson.decodeFromString(CachedConversation.serializer(), raw)
            if (nowProvider() - cached.timestamp >= TTL_MS) null else cached
        } catch (t: Throwable) {
            RespondoLog.w("не удалось разобрать кэш беседы", t)
            null
        }
    }

    /** Сохраняет блоб, обрезая историю до последних [MAX_MESSAGES] и штампуя timestamp. */
    fun save(agentId: String?, channelId: String?, conversation: CachedConversation) {
        val trimmed = conversation.copy(
            messages = conversation.messages.takeLast(MAX_MESSAGES),
            timestamp = nowProvider(),
        )
        store.putString(
            StorageKeys.conversation(agentId, channelId),
            respondoJson.encodeToString(CachedConversation.serializer(), trimmed),
        )
    }

    fun clear(agentId: String?, channelId: String?) {
        store.remove(StorageKeys.conversation(agentId, channelId))
    }

    /**
     * Все хранимые беседы с непустыми (conversationId, sessionToken) — для revoke-session при reset.
     * Сканирует все ключи Respondo и пробует разобрать каждое значение как блоб беседы.
     */
    fun allConversationsWithToken(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (key in store.keys()) {
            if (!key.startsWith(StorageKeys.CONVERSATION_SCAN_PREFIX)) continue
            val raw = store.getString(key) ?: continue
            val cached = runCatching {
                respondoJson.decodeFromString(CachedConversation.serializer(), raw)
            }.getOrNull() ?: continue
            val cid = cached.conversationId
            val token = cached.sessionToken
            if (!cid.isNullOrEmpty() && !token.isNullOrEmpty()) result += cid to token
        }
        return result
    }

    companion object {
        const val MAX_MESSAGES = 50
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
