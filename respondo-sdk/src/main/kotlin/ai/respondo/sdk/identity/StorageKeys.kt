package ai.respondo.sdk.identity

/**
 * Логические ключи локального хранилища. Все несут префикс [PREFIX] — `reset()` стирает всё по нему
 * (симметрично веб-семантике «стереть все ключи respondoai_*»).
 */
internal object StorageKeys {
    const val PREFIX = "respondoai_"

    const val VISITOR_ID = "respondoai_visitor_id"
    const val LANG = "respondoai_lang"
    const val COLLECTED_EMAIL = "respondoai_collected_email"

    /** Доставки опросов, которые посетитель закрыл (JSON-массив, новые в конце). */
    const val SURVEY_DISMISSED = "respondoai_survey_dismissed"

    /** Блоб беседы: messages(50), conversationId, escalated, sessionToken, timestamp (TTL 24ч). */
    fun conversation(agentId: String?, channelId: String?): String =
        "respondoai_" + (agentId?.ifEmpty { null } ?: "ch") + "_" + (channelId?.ifEmpty { null } ?: "default")

    /** Кэш конфига виджета (TTL 24ч). */
    fun config(agentId: String?, channelId: String?, lang: String?): String =
        "respondoai_config_" + (agentId?.ifEmpty { null } ?: "ch") + "_" +
            (channelId?.ifEmpty { null } ?: "default") + "_" + (lang?.ifEmpty { null } ?: "en")

    /** Префикс всех блобов бесед — для обхода при reset (revoke-session по каждой). */
    const val CONVERSATION_SCAN_PREFIX = "respondoai_"

    fun chatSeen(msgId: String): String = "respondoai_chat_seen_$msgId"
    fun bannerDismissed(msgId: String): String = "respondoai_banner_dismissed_$msgId"
    fun announcementDismissed(msgId: String): String = "respondoai_announcement_dismissed_$msgId"
}
