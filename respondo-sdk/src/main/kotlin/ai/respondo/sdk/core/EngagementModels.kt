package ai.respondo.sdk.core

/**
 * Публичные доменные модели engagement-слоя (news, surveys, banners, checklists, proactive).
 * Отделены от транспортных DTO: host-приложение и UI работают только с ними.
 */

/** Элемент ленты «Что нового». */
data class RespondoNewsItem(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val labels: List<String>,
    val publishedAt: String?,
    val seen: Boolean,
)

/** Отправитель (оператор), показываемый в опросе/баннере. */
data class RespondoSender(
    val name: String,
    val avatarUrl: String?,
)

/** Формат опроса: оверлей (модалка) или in-thread. */
enum class RespondoSurveyFormat(val raw: String) {
    IN_MODAL("in_modal"),
    IN_WIDGET("in_widget");

    companion object {
        fun from(raw: String?): RespondoSurveyFormat =
            entries.firstOrNull { it.raw == raw } ?: IN_MODAL
    }
}

/** Тип вопроса опроса. */
enum class RespondoQuestionType(val raw: String) {
    TEXT("text"),
    CHOICE("choice"),
    SCALE("scale"),
    NPS("nps"),
    CSAT("csat");

    companion object {
        fun from(raw: String?): RespondoQuestionType =
            entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

/** Вопрос опроса. */
data class RespondoQuestion(
    val id: String,
    val type: RespondoQuestionType,
    val title: String,
    val options: List<String>,
    val required: Boolean,
)

/** Оверлей-опрос (NPS/CSAT и др.), доставленный посетителю. */
data class RespondoSurvey(
    val campaignId: String,
    val deliveryId: String,
    val name: String,
    val format: RespondoSurveyFormat,
    val intro: String?,
    val thanks: String?,
    val showIntroScreen: Boolean,
    val showProgress: Boolean,
    val showDismiss: Boolean,
    /** Группы id вопросов по шагам; если пусто — шаг равен одному вопросу. */
    val steps: List<List<String>>,
    val questions: List<RespondoQuestion>,
    val sender: RespondoSender?,
)

/** Ответ пользователя на вопрос опроса (публичный тип, не раскрывает внутренний JSON). */
sealed interface RespondoSurveyAnswer {
    data class Text(val value: String) : RespondoSurveyAnswer
    data class Number(val value: Double) : RespondoSurveyAnswer
    data class Choice(val value: String) : RespondoSurveyAnswer
    data class MultiChoice(val values: List<String>) : RespondoSurveyAnswer
}

/** Раскладка баннера. */
enum class RespondoBannerLayout(val raw: String) {
    INLINE("inline"),
    FLOATING("floating");

    companion object {
        fun from(raw: String?): RespondoBannerLayout =
            entries.firstOrNull { it.raw == raw } ?: FLOATING
    }
}

/** Позиция баннера. */
enum class RespondoBannerPosition(val raw: String) {
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun from(raw: String?): RespondoBannerPosition =
            entries.firstOrNull { it.raw == raw } ?: BOTTOM
    }
}

/** Действие баннера при клике. */
enum class RespondoBannerAction(val raw: String) {
    NONE("none"),
    URL("url"),
    BUTTON("button"),
    REACTIONS("reactions"),
    EMAIL("email"),
    TOUR("tour");

    companion object {
        fun from(raw: String?): RespondoBannerAction =
            entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

/** Page-level баннер (Banner Redesign). */
data class RespondoBanner(
    val campaignId: String,
    val deliveryId: String,
    val name: String,
    val body: String,
    val layout: RespondoBannerLayout,
    val position: RespondoBannerPosition,
    val action: RespondoBannerAction,
    val url: String?,
    val linkLabel: String?,
    val reactions: List<String>,
    val openNewTab: Boolean,
    val backgroundHex: String?,
    val foregroundHex: String?,
    val buttonBackgroundHex: String?,
    val buttonForegroundHex: String?,
    val dismissAfterAction: Boolean,
    val showDismiss: Boolean,
    val sender: RespondoSender?,
)

/** Действие задачи чек-листа. */
sealed interface RespondoChecklistAction {
    data class Url(val url: String, val newTab: Boolean) : RespondoChecklistAction
    data object Manual : RespondoChecklistAction
    data class Tour(val tourId: String?) : RespondoChecklistAction
}

/** Задача чек-листа. */
data class RespondoChecklistTask(
    val id: String,
    val title: String,
    val body: String?,
    val action: RespondoChecklistAction,
)

/** Онбординг-чек-лист. */
data class RespondoChecklist(
    val id: String,
    val title: String,
    val body: String?,
    val dismissible: Boolean,
    val tasks: List<RespondoChecklistTask>,
    val status: String,
    val doneTaskIds: Set<String>,
) {
    /** Видимые задачи — tour-задачи скрываются в мобильном контексте. */
    val visibleTasks: List<RespondoChecklistTask>
        get() = tasks.filter { it.action !is RespondoChecklistAction.Tour }

    /** Число выполненных видимых задач. */
    val doneCount: Int get() = visibleTasks.count { doneTaskIds.contains(it.id) }

    /** Всего видимых задач. */
    val totalCount: Int get() = visibleTasks.size
}

/** Проактивное сообщение под текущий экран. */
data class RespondoProactiveMessage(
    val text: String,
    val pagePath: String?,
)

/** Решение арбитра: единственный оверлей, показываемый сейчас. */
sealed interface OverlayDecision {
    data object None : OverlayDecision
    data class Survey(val survey: RespondoSurvey) : OverlayDecision
    data class Banner(val banner: RespondoBanner) : OverlayDecision
}
