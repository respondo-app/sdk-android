package ai.respondo.sdk.identity

import ai.respondo.sdk.RespondoIdentity
import ai.respondo.sdk.internal.Ids
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.transport.AuthParams

/**
 * Хранилище личности и анонимного visitor_id. Держит стабильный per-session кэш visitor_id в памяти —
 * если хранилище заблокировано, id не регенерируется на каждый запрос (иначе контакт фрагментируется).
 */
class IdentityStore(private val store: KeyValueStore) {

    @Volatile
    private var identity: RespondoIdentity = RespondoIdentity()

    @Volatile
    private var visitorCache: String? = null

    /** Возвращает персистентный visitor_id, генерируя и сохраняя его при первом обращении. */
    @Synchronized
    fun visitorId(): String {
        visitorCache?.let { return it }
        val stored = store.getString(StorageKeys.VISITOR_ID)
        if (!stored.isNullOrEmpty()) {
            visitorCache = stored
            return stored
        }
        val fresh = Ids.newVisitorId()
        store.putString(StorageKeys.VISITOR_ID, fresh)
        visitorCache = fresh
        return fresh
    }

    /** Заменяет visitor_id новым анонимным (часть `reset()`). */
    @Synchronized
    fun regenerateVisitor(): String {
        val fresh = Ids.newVisitorId()
        store.putString(StorageKeys.VISITOR_ID, fresh)
        visitorCache = fresh
        return fresh
    }

    fun setIdentity(newIdentity: RespondoIdentity) {
        identity = newIdentity
    }

    fun currentIdentity(): RespondoIdentity = identity

    /** Сбрасывает личность в анонимную (без стирания хранилища — это делает контроллер в reset). */
    fun clearIdentity() {
        identity = RespondoIdentity()
    }

    /** Параметры владения для query/тела запросов: session_token + user_hash из identity + visitor_id. */
    fun authParams(sessionToken: String?): AuthParams =
        AuthParams(sessionToken = sessionToken, userHash = identity.userHash, visitorId = visitorId())

    var lang: String?
        get() = store.getString(StorageKeys.LANG)
        set(value) {
            if (value.isNullOrEmpty()) store.remove(StorageKeys.LANG) else store.putString(StorageKeys.LANG, value)
        }

    var collectedEmail: String?
        get() = store.getString(StorageKeys.COLLECTED_EMAIL)
        set(value) {
            if (value.isNullOrEmpty()) store.remove(StorageKeys.COLLECTED_EMAIL)
            else store.putString(StorageKeys.COLLECTED_EMAIL, value)
        }

    fun markChatSeen(msgId: String) = store.putString(StorageKeys.chatSeen(msgId), "1")
    fun isChatSeen(msgId: String): Boolean = store.contains(StorageKeys.chatSeen(msgId))

    fun markBannerDismissed(msgId: String) = store.putString(StorageKeys.bannerDismissed(msgId), "1")
    fun isBannerDismissed(msgId: String): Boolean = store.contains(StorageKeys.bannerDismissed(msgId))

    fun markAnnouncementDismissed(msgId: String) = store.putString(StorageKeys.announcementDismissed(msgId), "1")
    fun isAnnouncementDismissed(msgId: String): Boolean = store.contains(StorageKeys.announcementDismissed(msgId))

    /** Стирает все ключи Respondo (префикс `respondoai_`). Личность и visitor-кэш обнуляются отдельно. */
    @Synchronized
    fun wipeAll() {
        store.removeByPrefix(StorageKeys.PREFIX)
        visitorCache = null
    }
}
