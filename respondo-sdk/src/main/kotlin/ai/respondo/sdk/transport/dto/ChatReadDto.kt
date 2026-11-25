@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ai.respondo.sdk.transport.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ `GET /chat/resume`.
 *
 * `conversation_id` и `session_token` приходят при ЛЮБОМ статусе, ЗАКРЫТОМ В ТОМ ЧИСЛЕ: следующее
 * сообщение пользователя и нажатие «нужен человек» форкают от этой строки follow-up, и без её id
 * форкать не от чего. `status` решает только одно — есть ли ещё что слушать на этой строке.
 */
@Serializable
data class ResumeResponseDto(
    val status: String? = null,
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("history_conversation_id") val historyConversationId: String? = null,
    @SerialName("oldest_message_id") val oldestMessageId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
    /** Карточки статусов на языке клиента (см. [StatusTextsDto]). */
    @SerialName("status_texts") val statusTexts: StatusTextsDto? = null,
    /** Оценка нынешнего закрытия: запрос, выбранная цифра, отзыв (behavior.md §12). */
    @SerialName("rating_requested") val ratingRequested: Boolean = false,
    @SerialName("visitor_rating") val visitorRating: Int? = null,
    @SerialName("rating_remark") val ratingRemark: String? = null,
    @SerialName("rating_remark_editable") val ratingRemarkEditable: Boolean? = null,
)

/**
 * Карточки, которые SDK рисует сам по смене статуса беседы, на языке КЛИЕНТА (`status_texts`:
 * каталог системных текстов сервера). Кадр WS `status_changed` языка не несёт — SDK запоминает
 * последние из poll, resume, `/chat` и `/escalate`; строки SDK — фолбэк.
 */
@Serializable
data class StatusTextsDto(
    @SerialName("back_to_ai") val backToAi: String? = null,
    val resolved: String? = null,
    /** Блок оценки беседы — только пока закрытие просит оценку (behavior.md §12). */
    @SerialName("rating_question") val ratingQuestion: String? = null,
    @SerialName("rating_thanks") val ratingThanks: String? = null,
    @SerialName("rating_remark_prompt") val ratingRemarkPrompt: String? = null,
    @SerialName("rating_remark_placeholder") val ratingRemarkPlaceholder: String? = null,
    @SerialName("rating_send") val ratingSend: String? = null,
    @SerialName("rating_edit") val ratingEdit: String? = null,
)

/**
 * Тело `POST /chat/conversations/{id}/rating`: `score` — всегда, и вместе с отзывом; `client` —
 * android (behavior.md §12). `respondoJson` не пишет значения по умолчанию (encodeDefaults=false),
 * поэтому `client` помечен [EncodeDefault] ALWAYS — без этого поле не уходило, и сервер записывал
 * каждую оценку с Android как web.
 */
@Serializable
data class RatingRequestDto(
    val score: Int,
    val remark: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val client: String = "android",
)

/** Ответ оценки: сохранённая оценка, отзыв и можно ли его ещё править. */
@Serializable
data class RatingResponseDto(
    val ok: Boolean = false,
    val score: Int? = null,
    val remark: String? = null,
    @SerialName("remark_editable") val remarkEditable: Boolean? = null,
)

/** Тело отказа (`{"error": "remark locked"}`). */
@Serializable
data class RatingErrorDto(val error: String? = null)

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
    /** Карточки статусов на языке клиента: сервер шлёт их, пока тред у людей или закрыт. */
    @SerialName("status_texts") val statusTexts: StatusTextsDto? = null,
    /** Оценка нынешнего закрытия: запрос, выбранная цифра, отзыв (behavior.md §12). */
    @SerialName("rating_requested") val ratingRequested: Boolean = false,
    @SerialName("visitor_rating") val visitorRating: Int? = null,
    @SerialName("rating_remark") val ratingRemark: String? = null,
    @SerialName("rating_remark_editable") val ratingRemarkEditable: Boolean? = null,
)

/**
 * Ответ `POST /chat/conversations/{id}/escalate`.
 *
 * ГРАНИЦА СЕССИИ. Нажатие «нужен человек» на ЗАКРЫТОЙ беседе её не воскрешает: бэкенд заводит
 * follow-up, эскалирует ЕГО и возвращает здесь id, ключ и ссылку назад ИМЕННО новой строки.
 *
 * `session_token` тут отсутствовал вовсе, а вызывающий читал из ответа только `message`. Итог:
 * оператор получал в «Needs human» кейс, в который пользователь физически не мог написать (SDK
 * продолжал жить на закрытой строке), а следующее сообщение форкало ТРЕТЬЮ беседу и перебивало
 * закрытой ссылку вперёд — второй кейс выпадал из цепочки и становился недостижим для ленты,
 * истории и аналитики.
 */
@Serializable
data class EscalationResponseDto(
    @SerialName("conversation_id") val conversationId: String? = null,
    val status: String? = null,
    val message: String? = null,
    /** Ключ от НОВОЙ строки: follow-up рождается с `access=token`, без ключа лента ответит 403. */
    @SerialName("session_token") val sessionToken: String? = null,
    /** Тред для пользователя тот же — ленту не сбрасываем. */
    @SerialName("previous_conversation_id") val previousConversationId: String? = null,
    /** Карточки статусов на языке клиента — «вернулся к ИИ», когда оператор вернёт тред боту. */
    @SerialName("status_texts") val statusTexts: StatusTextsDto? = null,
    // Внимание: без ticket_url / ticket_id — см. комментарий у ChatResponseDto.
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
