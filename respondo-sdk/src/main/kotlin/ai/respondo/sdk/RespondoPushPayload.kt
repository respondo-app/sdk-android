package ai.respondo.sdk

import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Разобранный payload входящего пуша Respondo. Host-приложение добывает «сырой» словарь из своей push-подсистемы
 * (FCM `RemoteMessage.data`) и отдаёт его в [Respondo.handlePush]. SDK сам распознаёт «свой» пуш и извлекает поля.
 *
 * Полезная нагрузка Respondo лежит под корневым ключом `respondo` (JSON-строка в FCM `data`), см.
 * `sdks/spec/schemas/push-payload.schema.json`.
 *
 * @property conversationId id беседы (для deep-link и дедупа).
 * @property messageId id сообщения (для дедупликации — повтор с тем же id не показывается дважды).
 * @property title заголовок карточки уведомления.
 * @property body текст уведомления.
 * @property raw исходный словарь данных пуша (транспортные поля провайдера).
 */
data class RespondoPushPayload(
    val conversationId: String?,
    val messageId: String?,
    val title: String?,
    val body: String?,
    val raw: Map<String, String>,
) {
    /** Тип пуша: `message` (ответ оператора/бота) или `campaign` (outbound-кампания); прочее — `null`. */
    val type: String?
        get() = raw[KEY_TYPE_CACHE]

    /** id доставки push-кампании (только type=campaign), для `POST /widget/push/opened`. */
    val deliveryId: String?
        get() = raw[KEY_DELIVERY_CACHE]

    companion object {
        private const val ROOT_KEY = "respondo"
        private const val KEY_TYPE_CACHE = "__respondo_type"
        private const val KEY_DELIVERY_CACHE = "__respondo_delivery_id"

        /**
         * Разбирает словарь данных пуша. Возвращает `null`, если это не пуш Respondo (нет корневого ключа `respondo`).
         * Никогда не бросает: на битом payload — предупреждение в лог и `null`.
         */
        fun from(data: Map<String, String>): RespondoPushPayload? {
            val rootRaw = data[ROOT_KEY] ?: return null
            return try {
                val root: JsonObject = respondoJson.parseToJsonElement(rootRaw).jsonObject
                val type = root.stringOrNull("type")
                val conversationId = root.stringOrNull("conversation_id")
                val messageId = root.stringOrNull("message_id")
                val deliveryId = root.stringOrNull("delivery_id")
                val payloadData = root["data"]?.let { it as? JsonObject }
                val title = payloadData?.stringOrNull("title")
                val body = payloadData?.stringOrNull("body")

                // Кэшируем разобранные type/delivery_id в raw под приватными ключами — чтобы геттеры не парсили заново.
                val enrichedRaw = HashMap(data)
                if (type != null) enrichedRaw[KEY_TYPE_CACHE] = type
                if (deliveryId != null) enrichedRaw[KEY_DELIVERY_CACHE] = deliveryId

                RespondoPushPayload(
                    conversationId = conversationId,
                    messageId = messageId,
                    title = title,
                    body = body,
                    raw = enrichedRaw,
                )
            } catch (t: Throwable) {
                RespondoLog.w("не удалось разобрать push payload Respondo", t)
                null
            }
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val el = this[key] as? JsonPrimitive ?: return null
            return el.contentOrNull
        }
    }
}
