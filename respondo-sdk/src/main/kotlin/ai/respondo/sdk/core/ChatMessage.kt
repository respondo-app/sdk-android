package ai.respondo.sdk.core

import ai.respondo.sdk.internal.isBackendId
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.MessageMetadataDto
import ai.respondo.sdk.transport.dto.SourceDto
import kotlinx.serialization.Serializable

/** Роль в треде после нормализации: `agent`→ASSISTANT, `user`/`visitor`→USER. */
@Serializable
enum class MessageRole { USER, ASSISTANT, SYSTEM }

/** Статус отправки оптимистичного сообщения пользователя (нативное расширение поверх веб-семантики). */
@Serializable
enum class SendStatus { NONE, SENDING, SENT, FAILED }

/**
 * Доменное сообщение треда. Сериализуемо — кэшируется в блобе беседы (последние 50).
 *
 * @property isLocal `true` для оптимистичного пузыря до подтверждения сервером (id без дефиса).
 * @property sendStatus статус отправки для локальных пользовательских сообщений.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val attachments: List<ChatAttachmentDto> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
    val metadata: MessageMetadataDto? = null,
    val createdAt: String? = null,
    val deliveryStatus: String? = null,
    val sendStatus: SendStatus = SendStatus.NONE,
    val isLocal: Boolean = false,
    val originalText: String? = null,
) {
    /** html-рендер (`content_format="html"`) против markdown по умолчанию. */
    val isHtml: Boolean get() = metadata?.contentFormat == "html"

    companion object {
        /** Строит доменное сообщение из серверного DTO с нормализацией роли и фильтрацией источников. */
        fun fromDto(dto: MessageDto): ChatMessage = ChatMessage(
            id = dto.id,
            role = normalizeRole(dto.role),
            content = dto.content,
            authorName = dto.authorName,
            authorAvatarUrl = dto.authorAvatarUrl,
            attachments = dto.attachments,
            sources = dto.sources.filter { isPublicSource(it) },
            metadata = dto.metadata,
            createdAt = dto.createdAt,
            deliveryStatus = dto.deliveryStatus,
            sendStatus = SendStatus.NONE,
            isLocal = !dto.id.isBackendId(),
        )

        /** `agent`→ASSISTANT, `assistant`→ASSISTANT, `system`→SYSTEM, `user`/`visitor`/прочее→USER. */
        fun normalizeRole(role: String): MessageRole = when (role.lowercase()) {
            "assistant", "agent" -> MessageRole.ASSISTANT
            "system" -> MessageRole.SYSTEM
            else -> MessageRole.USER
        }

        /** Публичная ссылка-источник: есть title и http(s) URL (внутренние RAG-цитаты вырезаются). */
        fun isPublicSource(src: SourceDto): Boolean {
            val url = src.url ?: return false
            val title = src.title ?: return false
            return title.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
        }
    }
}

/** Признак «сообщение-эхо роли user/visitor из realtime» — фильтруется (уже отрисовано оптимистично). */
internal fun MessageDto.isEchoedUserRole(): Boolean {
    val r = role.lowercase()
    if (r != "user" && r != "visitor") return false
    // Исключение: survey-ответы пропускаем (SurveyCard не рисует оптимистичную копию).
    val meta = metadata ?: return true
    val isSurvey = meta.surveyStep != null || meta.surveyInline != null
    return !isSurvey
}

/** Вложение-изображение (для лайтбокса). */
internal fun ChatAttachmentDto.isImage(): Boolean = contentType.startsWith("image/")

/**
 * Сообщение действительно пришло с бэкенда: это не локальная карточка/оптимистичный пузырь
 * ([isLocal] == false) и id имеет форму серверного UUID (с дефисом). Локальные системные карточки
 * («escalate-card», «resolved-card», …) тоже содержат дефис, поэтому одного [isBackendId] мало —
 * иначе id карточки протекал бы в `lastBackendMsgId` и ломал catch-up реалтайма.
 */
internal fun ChatMessage.isBackendMessage(): Boolean = !isLocal && id.isBackendId()
