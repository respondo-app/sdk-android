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

/**
 * Шаг отзыва после оценки («Tell us more», модель Intercom; behavior.md §12): OPEN — оценка
 * выбрана, отзыва нет: поле + Send; SENT — отзыв сохранён: строка + Edit, пока правка доступна;
 * EDITING — нажали Edit. Пропуск — не писать: оценка уже сохранена, кнопки Skip нет.
 */
enum class RemarkStep { OPEN, SENT, EDITING }

/**
 * Оценка беседы (CSAT 1–5) и отзыв — для ОДНОЙ беседы: продолжение беседы не наследует ни
 * запрос, ни оценку, ни отзыв (зеркало widget/src/rating-ask.ts). Переходы — [ControllerLogic].
 */
data class ConversationRating(
    val conversationId: String? = null,
    val requested: Boolean = false,
    /** 1–5, 0 — не оценено. */
    val value: Int = 0,
    val remark: String = "",
    val remarkEditable: Boolean = true,
    val remarkStep: RemarkStep = RemarkStep.OPEN,
) {
    /** Блок виден под беседой на экране, когда она закрыта и её закрытие попросило оценку. */
    fun visibleFor(cid: String?, closed: Boolean): Boolean =
        cid != null && closed && requested && conversationId == cid

    /** Шаг отзыва — после оценки, пока есть что показать или можно написать. */
    val remarkVisible: Boolean get() = value > 0 && (remark.isNotEmpty() || remarkEditable)
}
