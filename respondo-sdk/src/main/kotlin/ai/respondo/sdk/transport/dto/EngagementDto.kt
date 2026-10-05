package ai.respondo.sdk.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Элемент ленты News. */
@Serializable
data class NewsItemDto(
    val id: String,
    val title: String? = null,
    val body: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val labels: List<String> = emptyList(),
    @SerialName("published_at") val publishedAt: String? = null,
    val seen: Boolean = false,
)

/** Ответ `GET /widget/news`. */
@Serializable
data class NewsListResponseDto(
    val items: List<NewsItemDto> = emptyList(),
    val unread: Int = 0,
)

/** Отправитель (оператор), показываемый в опросе/баннере. */
@Serializable
data class SenderDto(
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Вопрос опроса. */
@Serializable
data class QuestionDto(
    val id: String,
    val type: String,
    val title: String? = null,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
)

/** Правило экрана опроса (backend domain/pageurl.Rule). */
@Serializable
data class PageRuleDto(
    val op: String,
    val value: String? = null,
)

/**
 * Контент outbound-кампании (подмножество полей, релевантных каталогам survey/banner).
 * Бэкенд может добавлять другие поля (additionalProperties=true) — они игнорируются парсером.
 */
@Serializable
data class OutboundContentDto(
    val body: String? = null,
    val intro: String? = null,
    val questions: List<QuestionDto> = emptyList(),
    val thanks: String? = null,
    @SerialName("survey_format") val surveyFormat: String? = null,
    @SerialName("survey_steps") val surveySteps: List<List<String>> = emptyList(),
    @SerialName("show_intro_screen") val showIntroScreen: Boolean = false,
    @SerialName("show_dismiss") val showDismiss: Boolean? = null,
    @SerialName("show_progress") val showProgress: Boolean = false,
    // Таргетинг оверлей-опроса для приложений (survey_url_rules — только веб, SDK их не читает намеренно).
    @SerialName("survey_platform") val surveyPlatform: String? = null,
    @SerialName("survey_screen_rules") val surveyScreenRules: List<PageRuleDto> = emptyList(),
    @SerialName("survey_delay_seconds") val surveyDelaySeconds: Int? = null,
    @SerialName("survey_trigger_event") val surveyTriggerEvent: String? = null,
    @SerialName("banner_link_label") val bannerLinkLabel: String? = null,
    @SerialName("banner_bg") val bannerBg: String? = null,
    @SerialName("banner_fg") val bannerFg: String? = null,
    @SerialName("banner_btn_bg") val bannerBtnBg: String? = null,
    @SerialName("banner_btn_fg") val bannerBtnFg: String? = null,
    @SerialName("banner_layout") val bannerLayout: String? = null,
    @SerialName("banner_position") val bannerPosition: String? = null,
    @SerialName("banner_action") val bannerAction: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("banner_reactions") val bannerReactions: List<String> = emptyList(),
    @SerialName("banner_open_new_tab") val bannerOpenNewTab: Boolean = false,
    @SerialName("banner_dismiss_after_action") val bannerDismissAfterAction: Boolean = false,
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val dismissible: Boolean? = null,
)

/** Элемент каталога опросов (`GET /widget/surveys`). */
@Serializable
data class SurveyCatalogItemDto(
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("delivery_id") val deliveryId: String,
    val name: String? = null,
    val content: OutboundContentDto = OutboundContentDto(),
    @SerialName("from_sender") val fromSender: SenderDto? = null,
)

/** Ответ `GET /widget/surveys`. */
@Serializable
data class SurveysCatalogResponseDto(
    val surveys: List<SurveyCatalogItemDto> = emptyList(),
)

/** Элемент каталога баннеров (`GET /widget/banners`). */
@Serializable
data class BannerCatalogItemDto(
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("delivery_id") val deliveryId: String,
    val name: String? = null,
    val content: OutboundContentDto = OutboundContentDto(),
    @SerialName("from_sender") val fromSender: SenderDto? = null,
)

/** Ответ `GET /widget/banners`. */
@Serializable
data class BannersCatalogResponseDto(
    val banners: List<BannerCatalogItemDto> = emptyList(),
)

/** Тело `POST /widget/survey/answer` (один ответ). */
@Serializable
data class SubmitAnswerRequestDto(
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("visitor_id") val visitorId: String,
    @SerialName("question_id") val questionId: String,
    val value: JsonElement? = null,
)

/** Тело `POST /widget/survey/submit` (весь опрос разом). */
@Serializable
data class SubmitSurveyRequestDto(
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("visitor_id") val visitorId: String,
    val answers: Map<String, JsonElement> = emptyMap(),
)

/** Тело `POST /widget/banner/response` (реакция/email). */
@Serializable
data class BannerResponseRequestDto(
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("visitor_id") val visitorId: String,
    val kind: String,
    val value: String,
)

/** Действие задачи чек-листа. */
@Serializable
data class ChecklistTaskActionDto(
    val type: String,
    @SerialName("tour_id") val tourId: String? = null,
    val url: String? = null,
    @SerialName("new_tab") val newTab: Boolean = false,
)

/** Задача чек-листа. */
@Serializable
data class ChecklistTaskDto(
    val id: String,
    val title: String,
    val body: String? = null,
    val action: ChecklistTaskActionDto,
)

/** Прогресс контакта по чек-листу. */
@Serializable
data class ChecklistProgressStateDto(
    val status: String = "",
    @SerialName("done_task_ids") val doneTaskIds: List<String> = emptyList(),
)

/** Элемент каталога чек-листов. */
@Serializable
data class ChecklistCatalogItemDto(
    val id: String,
    val title: String,
    val body: String? = null,
    val dismissible: Boolean = false,
    val tasks: List<ChecklistTaskDto> = emptyList(),
    val progress: ChecklistProgressStateDto = ChecklistProgressStateDto(),
)

/** Ответ `GET /widget/checklists`. */
@Serializable
data class ChecklistsCatalogResponseDto(
    val checklists: List<ChecklistCatalogItemDto> = emptyList(),
)

/** Универсальный ответ-подтверждение виджет-эндпоинтов (survey/banner/seen/progress). */
@Serializable
data class OkResponseDto(
    val ok: Boolean = false,
    val done: Boolean? = null,
    @SerialName("already_answered") val alreadyAnswered: Boolean? = null,
    @SerialName("already_answered_question") val alreadyAnsweredQuestion: Boolean? = null,
)
