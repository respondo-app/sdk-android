package ai.respondo.sdk.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Личность в теле `POST /chat`. ВНИМАНИЕ: ключи здесь **camelCase** (как отдаёт/принимает бэкенд),
 * в отличие от snake_case остального тела. Не помечать @SerialName — имена свойств уже совпадают.
 */
@Serializable
data class ChatIdentityDto(
    val email: String? = null,
    val name: String? = null,
    val userId: String? = null,
    val userHash: String? = null,
    val visitorId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
)

/** Ссылка на вложение чата (ответ `/chat/upload`; уходит в `attachments[]` тела `/chat`). */
@Serializable
data class ChatAttachmentDto(
    val id: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    val size: Long = 0,
    val url: String,
)

/** Публичная ссылка-источник под ответом (customer-safe; внутренние RAG-цитаты бэкенд вырезает). */
@Serializable
data class SourceDto(
    val title: String? = null,
    val url: String? = null,
)

/** Ссылка на документацию под ответом. */
@Serializable
data class DocLinkDto(
    val title: String? = null,
    val url: String? = null,
)

/**
 * Метаданные сообщения. Типизировано только подмножество, задающее спец-типы; прочие поля бэкенда
 * игнорируются ([respondoJson] с ignoreUnknownKeys).
 */
@Serializable
data class MessageMetadataDto(
    val banner: Boolean? = null,
    val link: String? = null,
    @SerialName("link_label") val linkLabel: String? = null,
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("cta_label") val ctaLabel: String? = null,
    @SerialName("cta_url") val ctaUrl: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("delivery_id") val deliveryId: String? = null,
    @SerialName("reply_type") val replyType: String? = null,
    @SerialName("chat_display") val chatDisplay: String? = null,
    @SerialName("content_format") val contentFormat: String? = null,
    val announcement: JsonObject? = null,
    @SerialName("survey_step") val surveyStep: JsonObject? = null,
    @SerialName("survey_inline") val surveyInline: JsonObject? = null,
)

/** Сообщение беседы в форме, как её сериализует бэкенд для виджета (customer-safe). */
@Serializable
data class MessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val role: String,
    val content: String = "",
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("author_avatar_url") val authorAvatarUrl: String? = null,
    val attachments: List<ChatAttachmentDto> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
    val metadata: MessageMetadataDto? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("delivery_status") val deliveryStatus: String? = null,
    @SerialName("delivery_error") val deliveryError: String? = null,
)

/**
 * Данные WS-кадра `message_updated` — ЧАСТИЧНЫЙ объект: обязателен только [id],
 * остальные поля точечно меняются (`delivery_status`/`delivery_error`).
 * Отдельный DTO вместо [MessageDto]: у частичного объекта нет `role`/`content`,
 * и декодирование в полный [MessageDto] упало бы (обязательное поле `role` отсутствует).
 */
@Serializable
data class MessageUpdateDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("delivery_status") val deliveryStatus: String? = null,
    @SerialName("delivery_error") val deliveryError: String? = null,
)

/** Тело `POST /chat`. snake_case; null-поля не сериализуются ([respondoJson] explicitNulls=false). */
@Serializable
data class ChatRequestDto(
    @SerialName("agent_id") val agentId: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val message: String,
    @SerialName("user_email") val userEmail: String? = null,
    val source: String? = null,
    val attachments: List<ChatAttachmentDto>? = null,
    val identity: ChatIdentityDto? = null,
    @SerialName("session_token") val sessionToken: String? = null,
)

/** Тело ответа `POST /chat`. `message` бывает null (humans-only handover). */
@Serializable
data class ChatResponseDto(
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
    val message: MessageDto? = null,
    @SerialName("human_handover") val humanHandover: Boolean = false,
    @SerialName("suggested_questions") val suggestedQuestions: List<String> = emptyList(),
    @SerialName("doc_links") val docLinks: List<DocLinkDto> = emptyList(),
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("ticket_id") val ticketId: Long? = null,
)
