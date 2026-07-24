package ai.respondo.sdk

/**
 * Личность визитёра. Все поля опциональны — пустой `RespondoIdentity()` валиден (анонимный визитёр).
 *
 * @property userId внешний id пользователя (подписывается HMAC при identity verification).
 * @property email email (подписывается HMAC при identity verification).
 * @property name имя пользователя.
 * @property userHash HMAC-подпись личности. Считает **бэкенд клиента**, SDK лишь реплеит её в запросы;
 *   SDK **никогда** не вычисляет HMAC и не хранит секрет.
 * @property metadata технический контекст сессии. Перед отправкой SDK **автоматически** домешивает сюда
 *   поля устройства (app_version, os_version, platform, timezone, …); host-значения выигрывают при совпадении ключа.
 * @property properties атрибуты контакта (план, роль), уходящие в профиль.
 */
data class RespondoIdentity(
    val userId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val userHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
)
