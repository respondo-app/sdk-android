package ai.respondo.sdk.core

/** Активная поверхность модалки: чат / лента новостей / онбординг-чеклисты. */
enum class ChatSurface { CHAT, NEWS, CHECKLISTS }

/** Индикатор набора оператором. */
data class TypingState(val authorName: String?)

/** Сводное состояние баннера эскалации для UI. */
data class EscalationBanner(
    val waiting: Boolean,
    val agentIsHere: Boolean,
)

/** Проверка email по той же регулярке, что и веб (`isValidEmail`). */
object EmailValidator {
    private val REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    fun isValid(email: String?): Boolean = email != null && REGEX.matches(email)
}
