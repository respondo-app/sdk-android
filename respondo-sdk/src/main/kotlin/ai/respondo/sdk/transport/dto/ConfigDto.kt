package ai.respondo.sdk.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Статус рабочих часов (вычисляется бэкендом на каждый запрос). */
@Serializable
data class OfficeHoursDto(
    val open: Boolean = false,
    @SerialName("reply_time") val replyTime: String? = null,
    val timezone: String? = null,
    @SerialName("next_open_at") val nextOpenAt: String? = null,
    @SerialName("next_close_at") val nextCloseAt: String? = null,
)

/**
 * Публичный конфиг виджета (`GET /widget/config[-by-channel]`). Часть полей (position, launcher_size,
 * offset_*, z_index, custom_css) относится к веб-лончеру/хрому страницы и на мобильных игнорируется,
 * но парсится без падения ([respondoJson] ignoreUnknownKeys).
 */
@Serializable
data class WidgetConfigDto(
    @SerialName("agent_id") val agentId: String? = null,
    val name: String? = null,
    val title: String? = null,
    @SerialName("primary_color") val primaryColor: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val greeting: String? = null,
    @SerialName("greeting_enabled") val greetingEnabled: Boolean? = null,
    @SerialName("quick_questions") val quickQuestions: List<String> = emptyList(),
    val tone: String? = null,
    @SerialName("identity_verification_enabled") val identityVerificationEnabled: Boolean? = null,
    @SerialName("chat_size") val chatSize: String? = null,
    @SerialName("border_radius") val borderRadius: Double? = null,
    @SerialName("suggested_questions_enabled") val suggestedQuestionsEnabled: Boolean? = null,
    @SerialName("proactive_messages_enabled") val proactiveMessagesEnabled: Boolean? = null,
    @SerialName("proactive_delay_seconds") val proactiveDelaySeconds: Int? = null,
    @SerialName("office_hours") val officeHours: OfficeHoursDto? = null,
    @SerialName("visitor_language") val visitorLanguage: String? = null,
)
