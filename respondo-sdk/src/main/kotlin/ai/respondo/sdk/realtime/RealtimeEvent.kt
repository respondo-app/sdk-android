package ai.respondo.sdk.realtime

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.MessageUpdateDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Доменное событие реалтайма — единая форма для кадров WS и строк `data:` SSE. Разбирается [WsParser].
 */
sealed interface RealtimeEvent {
    /** Новое сообщение беседы. Фильтрация ролей user/visitor и дедуп — на уровне контроллера. */
    data class NewMessage(val conversationId: String, val message: MessageDto) : RealtimeEvent

    /** Смена статуса/обработчика беседы (escalated / agent / resolved / bot). */
    data class StatusChanged(val conversationId: String, val newStatus: String) : RealtimeEvent

    /** Оператор печатает. */
    data class Typing(val conversationId: String, val isTyping: Boolean, val authorName: String?) : RealtimeEvent

    /** Точечное обновление беседы (напр. смена локали `lang`, `messages_read`). */
    data class ConversationUpdated(val conversationId: String, val field: String?, val lang: String?) : RealtimeEvent

    /** Точечное изменение существующего сообщения (delivery_status/ошибка). Частичный объект, обновляется по id. */
    data class MessageUpdated(val conversationId: String, val update: MessageUpdateDto) : RealtimeEvent

    /**
     * Оверлеи для контакта (engagement-слой Ф3). В чат-ядре игнорируются. [contact] — ключ контакта
     * из штампа `data.identity` (для кого сервер их посчитал), `null` — кадр без штампа.
     */
    data class OverlayShow(val items: List<JsonObject>, val contact: Pair<String?, String?>?) : RealtimeEvent

    /** In-thread кампания, доставленная в беседу (адопция беседы + бейдж). */
    data class CampaignConversation(val conversationId: String, val message: MessageDto?) : RealtimeEvent

    /** Подтверждение подписки на беседу. */
    data class Subscribed(val conversationId: String) : RealtimeEvent

    /** Ошибка обработки клиентского кадра (обычно subscribe): invalid conversation_id / conversation not found / forbidden. */
    data class Error(val message: String) : RealtimeEvent

    /** Кадр, который SDK осознанно игнорирует (tooltip.catalog / tour.catalog / неизвестный type). */
    data class Ignored(val type: String) : RealtimeEvent
}

/** Разбор входящего WS/SSE-кадра в [RealtimeEvent]. Никогда не бросает: на битом кадре → `null`. */
object WsParser {
    fun parse(rawText: String): RealtimeEvent? {
        return try {
            val obj = respondoJson.parseToJsonElement(rawText).jsonObject
            parseObject(obj)
        } catch (t: Throwable) {
            RespondoLog.w("не удалось разобрать realtime-кадр", t)
            null
        }
    }

    private fun parseObject(obj: JsonObject): RealtimeEvent? {
        val type = obj.str("type") ?: return null
        return when (type) {
            "new_message" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"]?.let { respondoJson.decodeFromJsonElement(MessageDto.serializer(), it) }
                    ?: return null
                RealtimeEvent.NewMessage(cid, data)
            }

            "status_changed" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"] as? JsonObject ?: return null
                val newStatus = data.str("new_status") ?: return null
                RealtimeEvent.StatusChanged(cid, newStatus)
            }

            "typing" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"] as? JsonObject
                val isTyping = (data?.get("is_typing") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true
                RealtimeEvent.Typing(cid, isTyping, data?.str("author_name"))
            }

            "conversation_updated" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"] as? JsonObject
                RealtimeEvent.ConversationUpdated(cid, data?.str("field"), data?.str("lang"))
            }

            "message_updated" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"]?.let { respondoJson.decodeFromJsonElement(MessageUpdateDto.serializer(), it) }
                    ?: return null
                RealtimeEvent.MessageUpdated(cid, data)
            }

            "overlay.show" -> {
                val data = obj["data"] as? JsonObject
                val items = (data?.get("items") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
                val contact = (data?.get("identity") as? JsonObject)?.let {
                    ControllerLogic.contactKey(it.str("user_id"), it.str("email"))
                }
                RealtimeEvent.OverlayShow(items, contact)
            }

            "campaign_conversation" -> {
                val cid = obj.str("conversation_id") ?: return null
                val data = obj["data"]?.let { runCatching { respondoJson.decodeFromJsonElement(MessageDto.serializer(), it) }.getOrNull() }
                RealtimeEvent.CampaignConversation(cid, data)
            }

            "subscribed" -> obj.str("conversation_id")?.let { RealtimeEvent.Subscribed(it) }

            "error" -> {
                val data = obj["data"] as? JsonObject
                RealtimeEvent.Error(data?.str("message") ?: "unknown")
            }

            else -> RealtimeEvent.Ignored(type)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
