package ai.respondo.sdk

import java.util.Date

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
 * @property properties атрибуты контакта (план, роль), уходящие в профиль. Доходят до контакта сразу
 *   после `identify()` (`POST /widget/identify`), не дожидаясь первого сообщения в чат. Сам контакт
 *   личность с [userId] или [email] создаёт тем же запросом, даже без атрибутов — как вход
 *   пользователя в Intercom Messenger.
 * @property languageOverride явный язык контакта (BCP-47, напр. `de`, `pt-BR`) — как `languageOverride`
 *   у Intercom: главнее языка устройства и детекции, тексты бесед и рассылок идут на нём. Уходит
 *   свойством `language_override`; перекрывает одноимённый ключ в [properties]. `null` — не трогать
 *   язык, уже сохранённый у контакта (снять его можно в профиле контакта).
 * @property signedUpAt когда человек зарегистрировался в вашем продукте — как `signedUpAt` у Intercom
 *   (`created_at`). Уходит в `signed_up_at` контакта Unix-секундами. `null` — дата регистрации
 *   остаётся пустой: первый запуск приложения регистрацией не считается. Подписанная личность
 *   ([userHash]) переписывает известную дату, неподписанная — только заполняет пустую.
 */
data class RespondoIdentity(
    val userId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val userHash: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
    val languageOverride: String? = null,
    val signedUpAt: Date? = null,
) {
    /**
     * Прежний конструктор из шести полей (до [languageOverride]) — совместимость: Java-код и уже
     * собранные библиотеки, вызывающие `RespondoIdentity(userId, email, name, userHash, metadata,
     * properties)`, продолжают работать без пересборки; Kotlin-вызов всеми шестью позиционными
     * аргументами выбирает его же. Явный конструктор, а не `@JvmOverloads`: он возвращает ровно прежнюю
     * JVM-сигнатуру и не добавляет Java новых перегрузок. Синтетический конструктор с умолчаниями,
     * `copy()` и `componentN()` data-класса этим не сохраняются (см. CHANGELOG).
     */
    constructor(
        userId: String?,
        email: String?,
        name: String?,
        userHash: String?,
        metadata: Map<String, String>,
        properties: Map<String, String>,
    ) : this(userId, email, name, userHash, metadata, properties, null, null)

    /**
     * Прежний конструктор из семи полей (до [signedUpAt]) — та же совместимость, что у
     * шестипольного: Java-код и собранные библиотеки с `RespondoIdentity(userId, email, name,
     * userHash, metadata, properties, languageOverride)` работают без пересборки.
     */
    constructor(
        userId: String?,
        email: String?,
        name: String?,
        userHash: String?,
        metadata: Map<String, String>,
        properties: Map<String, String>,
        languageOverride: String?,
    ) : this(userId, email, name, userHash, metadata, properties, languageOverride, null)

    /** [signedUpAt] в Unix-секундах (тело `signed_up_at`); `null` — не передан. */
    internal val signedUpAtSeconds: Long?
        get() = signedUpAt?.time?.floorDiv(1000L)

    /** [properties] вместе с [languageOverride] под ключом `language_override` (типизированное поле главнее). */
    internal val effectiveProperties: Map<String, String>
        get() {
            val lang = languageOverride?.trim()?.takeIf { it.isNotEmpty() } ?: return properties
            return properties + (LANGUAGE_OVERRIDE_PROPERTY to lang)
        }
}

/** Ключ свойства явного языка контакта (бэкенд: identify-атрибут `language_override`). */
internal const val LANGUAGE_OVERRIDE_PROPERTY = "language_override"
