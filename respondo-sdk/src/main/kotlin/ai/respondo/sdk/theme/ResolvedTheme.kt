package ai.respondo.sdk.theme

import ai.respondo.sdk.RespondoChatSize
import ai.respondo.sdk.transport.dto.OfficeHoursDto

/**
 * Итоговая тема чата после разрешения `themeOverride → серверный конфиг → дефолт SDK`. Цвета хранятся как
 * ARGB-Long (без зависимости от Compose), поэтому маппинг тестируется на JVM.
 */
data class ResolvedTheme(
    val primaryColorArgb: Long,
    val onPrimaryArgb: Long,
    val linkColorArgb: Long,
    val title: String,
    val greeting: String,
    val greetingEnabled: Boolean,
    val agentName: String?,
    val avatarUrl: String?,
    val logoUrl: String?,
    val quickQuestions: List<String>,
    val suggestedQuestionsEnabled: Boolean,
    val proactiveEnabled: Boolean,
    val proactiveDelaySeconds: Int,
    val identityVerificationEnabled: Boolean,
    val officeHours: OfficeHoursDto?,
    val chatSize: RespondoChatSize,
    val initialDetent: Float,
    val maxDetent: Float,
    val cornerRadiusDp: Int,
) {
    companion object {
        const val DEFAULT_PRIMARY: Long = 0xFF2563EB
        const val DEFAULT_TITLE = "Support"
        const val DEFAULT_GREETING = "Hi! How can I help you today?"
        const val DEFAULT_BORDER_RADIUS = 12
        const val DEFAULT_PROACTIVE_DELAY = 5
        const val MIN_RADIUS = 0
        const val MAX_RADIUS = 28
    }
}
