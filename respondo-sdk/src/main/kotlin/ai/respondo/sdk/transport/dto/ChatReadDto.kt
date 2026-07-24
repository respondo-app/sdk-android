package ai.respondo.sdk.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответ `GET /chat/resume`. При resolved-беседе `conversation_id`/`session_token` = null (тред read-only). */
@Serializable
data class ResumeResponseDto(
    val status: String? = null,
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("history_conversation_id") val historyConversationId: String? = null,
    @SerialName("oldest_message_id") val oldestMessageId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
)

/** Ответ `GET /chat/history` (страница назад). */
@Serializable
data class HistoryResponseDto(
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("oldest_message_id") val oldestMessageId: String? = null,
)

/** Ответ `GET /chat/conversations/{id}/messages` (поллинг). */
@Serializable
data class WidgetMessagesResponseDto(
    val messages: List<MessageDto> = emptyList(),
    val status: String? = null,
)

/** Ответ `POST /chat/conversations/{id}/escalate`. */
@Serializable
data class EscalationResponseDto(
    @SerialName("conversation_id") val conversationId: String? = null,
    val status: String? = null,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("ticket_id") val ticketId: Long? = null,
    val message: String? = null,
)

/** Ответ `POST /chat/conversations/{id}/continue`. */
@Serializable
data class ContinueResponseDto(
    val status: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
)

/** Ответ `POST /chat/conversations/{id}/revoke-session`. */
@Serializable
data class RevokeResponseDto(
    val revoked: Boolean = false,
)

/** Ответ `GET /widget/proactive/{agentId}` (200; иначе 204 → null на уровне ApiClient). */
@Serializable
data class ProactiveResponseDto(
    val message: String? = null,
    @SerialName("page_path") val pagePath: String? = null,
)
