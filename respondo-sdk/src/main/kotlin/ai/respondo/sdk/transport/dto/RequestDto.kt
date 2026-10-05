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
