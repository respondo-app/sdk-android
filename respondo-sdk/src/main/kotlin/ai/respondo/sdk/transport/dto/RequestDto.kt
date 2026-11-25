package ai.respondo.sdk.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Тело `POST /widget/events` (Respondo.track). Trust-модель как у `/chat`; пустое имя → no-op 200. */
@Serializable
data class TrackEventRequestDto(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    val name: String,
    val properties: Map<String, JsonElement> = emptyMap(),
)

/** Тело `POST /widget/checklists/{id}/progress`. */
@Serializable
data class ChecklistProgressRequestDto(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    val event: String,
    @SerialName("task_id") val taskId: String? = null,
)

/** ПЛАНИРУЕТСЯ. Тело `POST /widget/push/register`. */
@Serializable
data class PushRegisterRequestDto(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("channel_id") val channelId: String,
    val platform: String,
    val token: String,
    @SerialName("app_id") val appId: String? = null,
    val locale: String? = null,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    @SerialName("sdk_name") val sdkName: String? = null,
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
)

/** ПЛАНИРУЕТСЯ. Тело `POST /widget/push/unregister`. */
@Serializable
data class PushUnregisterRequestDto(
    @SerialName("channel_id") val channelId: String,
    val token: String,
)

/** ПЛАНИРУЕТСЯ. Тело `POST /widget/push/opened`. */
@Serializable
data class PushOpenedRequestDto(
    @SerialName("delivery_id") val deliveryId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val token: String,
)

/**
 * Тело `POST /widget/identify`: личность и свойства контакта из `identify()` доходят до профиля сразу,
 * без чата (как `syncIdentity` веб-виджета). Шлётся, если у личности есть `user_id`/`email` (контакт
 * создаётся и без свойств — вход пользователя, как в Intercom Messenger) или непустые [properties];
 * доверие — то же identity-гейт, что у `/chat` (`user_hash`). Свойство `language_override` — явный язык
 * контакта; [signedUpAt] — дата регистрации в продукте.
 */
@Serializable
data class WidgetIdentifyRequestDto(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("channel_id") val channelId: String,
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    val name: String? = null,
    val properties: Map<String, String>,
    /** Дата регистрации в продукте ([ai.respondo.sdk.RespondoIdentity.signedUpAt]), Unix-секунды. */
    @SerialName("signed_up_at") val signedUpAt: Long? = null,
)

/**
 * Тело `POST /widget/app-session`: сессия приложения (запуск, возврат из фона) — источник «Last seen on
 * Android», числа сессий и языка устройства у контакта. Работает без разрешения на пуши.
 * [platform] без значения по умолчанию: `encodeDefaults = false` выбросил бы дефолт из тела.
 */
@Serializable
data class AppSessionRequestDto(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("channel_id") val channelId: String,
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    /** Имя личности: сервер берёт его, только если отметка создаёт контакт (слово приложения, «claimed»). */
    val name: String? = null,
    val platform: String,
    @SerialName("app_id") val appId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("sdk_name") val sdkName: String? = null,
    @SerialName("sdk_version") val sdkVersion: String? = null,
    val locale: String? = null,
)
