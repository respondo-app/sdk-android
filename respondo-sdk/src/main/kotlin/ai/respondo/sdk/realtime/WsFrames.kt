@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ai.respondo.sdk.realtime

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Исходящие клиентские WS-кадры. Сериализуются через [respondoJson] (explicitNulls=false, encodeDefaults=false),
 * поэтому пустые поля опускаются. Дискриминатор `type` — свойство первичного конструктора с [EncodeDefault] ALWAYS,
 * чтобы он всегда попадал в JSON даже при выключенном encodeDefaults (body-свойства kotlinx не сериализует).
 * `type` объявлен последним параметром — это не ломает позиционные вызовы прочих полей. Порядок отправки
 * строго **identify → subscribe**.
 */

/** Первый кадр после установления соединения (и при смене identity / reconnect / после resolve). */
@Serializable
data class IdentifyFrame(
    @SerialName("visitor_id") val visitorId: String? = null,
    val email: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("session_token") val sessionToken: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    val lang: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "identify",
)

/** Подписка на живой поток событий конкретной беседы (после создания беседы). */
@Serializable
data class SubscribeFrame(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_token") val sessionToken: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    @SerialName("visitor_id") val visitorId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "subscribe",
)

/** Read-receipt: посетитель видел тред (дебаунс 400мс на клиенте). */
@Serializable
data class ReadFrame(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("session_token") val sessionToken: String? = null,
    @SerialName("user_hash") val userHash: String? = null,
    @SerialName("visitor_id") val visitorId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "read",
)

/** Приложение ушло в фон — бэкенд подавляет push-доставку, пока WS жив (planned-кадр). */
@Serializable
data class BackgroundFrame(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "background",
)

/** Приложение вернулось на передний план (planned-кадр, парный к [BackgroundFrame]). */
@Serializable
data class ForegroundFrame(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "foreground",
)
