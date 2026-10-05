package ai.respondo.sdk.core

import ai.respondo.sdk.transport.dto.BannerCatalogItemDto
import ai.respondo.sdk.transport.dto.ChecklistCatalogItemDto
import ai.respondo.sdk.transport.dto.ChecklistTaskActionDto
import ai.respondo.sdk.transport.dto.ChecklistTaskDto
import ai.respondo.sdk.transport.dto.NewsItemDto
import ai.respondo.sdk.transport.dto.QuestionDto
import ai.respondo.sdk.transport.dto.SenderDto
import ai.respondo.sdk.transport.dto.SurveyCatalogItemDto

/** Преобразование engagement-DTO бэкенда в публичные доменные модели. */
internal object EngagementMapper {

    fun toNews(dto: NewsItemDto): RespondoNewsItem = RespondoNewsItem(
        id = dto.id,
        title = dto.title.orEmpty(),
        body = dto.body.orEmpty(),
        imageUrl = dto.imageUrl?.takeIf { it.isNotEmpty() },
        labels = dto.labels,
        publishedAt = dto.publishedAt,
        seen = dto.seen,
    )

    fun toSurvey(dto: SurveyCatalogItemDto): RespondoSurvey {
        val content = dto.content
        val questions = content.questions.map { toQuestion(it) }
        val steps = if (content.surveySteps.isNotEmpty()) {
            content.surveySteps
        } else {
            // По умолчанию — один вопрос на шаг.
            questions.map { listOf(it.id) }
        }
        return RespondoSurvey(
            campaignId = dto.campaignId,
            deliveryId = dto.deliveryId,
            name = dto.name.orEmpty(),
            format = RespondoSurveyFormat.from(content.surveyFormat),
            intro = content.intro?.takeIf { it.isNotEmpty() },
            thanks = content.thanks?.takeIf { it.isNotEmpty() },
            showIntroScreen = content.showIntroScreen,
            showProgress = content.showProgress,
            showDismiss = content.showDismiss ?: true,
            steps = steps,
            questions = questions,
            sender = toSender(dto.fromSender),
            targeting = RespondoSurveyTargeting(
                screenRules = content.surveyScreenRules.map { RespondoScreenRule(it.op, it.value.orEmpty()) },
                delaySeconds = SurveyTargeting.clampDelay(content.surveyDelaySeconds),
                triggerEvent = SurveyTargeting.normalizeEventName(content.surveyTriggerEvent.orEmpty())
                    .takeIf { it.isNotEmpty() },
                showsInApps = SurveyTargeting.showsInApps(content.surveyPlatform),
            ),
        )
    }

    fun toQuestion(dto: QuestionDto): RespondoQuestion = RespondoQuestion(
        id = dto.id,
        type = RespondoQuestionType.from(dto.type),
        title = dto.title.orEmpty(),
        options = dto.options,
        required = dto.required,
    )

    fun toBanner(dto: BannerCatalogItemDto): RespondoBanner {
        val content = dto.content
        return RespondoBanner(
            campaignId = dto.campaignId,
            deliveryId = dto.deliveryId,
            name = dto.name.orEmpty(),
            body = content.body.orEmpty(),
            layout = RespondoBannerLayout.from(content.bannerLayout),
            position = RespondoBannerPosition.from(content.bannerPosition),
            action = RespondoBannerAction.from(content.bannerAction),
            url = content.bannerUrl?.takeIf { it.isNotEmpty() },
            linkLabel = content.bannerLinkLabel?.takeIf { it.isNotEmpty() },
            reactions = content.bannerReactions,
            openNewTab = content.bannerOpenNewTab,
            backgroundHex = content.bannerBg?.takeIf { it.isNotEmpty() },
            foregroundHex = content.bannerFg?.takeIf { it.isNotEmpty() },
            buttonBackgroundHex = content.bannerBtnBg?.takeIf { it.isNotEmpty() },
            buttonForegroundHex = content.bannerBtnFg?.takeIf { it.isNotEmpty() },
            dismissAfterAction = content.bannerDismissAfterAction,
            showDismiss = content.showDismiss ?: true,
            sender = toSender(dto.fromSender),
        )
    }

    fun toChecklist(dto: ChecklistCatalogItemDto): RespondoChecklist = RespondoChecklist(
        id = dto.id,
        title = dto.title,
        body = dto.body?.takeIf { it.isNotEmpty() },
        dismissible = dto.dismissible,
        tasks = dto.tasks.map { toChecklistTask(it) },
        status = dto.progress.status,
        doneTaskIds = dto.progress.doneTaskIds.toSet(),
    )

    fun toChecklistTask(dto: ChecklistTaskDto): RespondoChecklistTask = RespondoChecklistTask(
        id = dto.id,
        title = dto.title,
        body = dto.body?.takeIf { it.isNotEmpty() },
        action = toChecklistAction(dto.action),
    )

    fun toChecklistAction(dto: ChecklistTaskActionDto): RespondoChecklistAction = when (dto.type) {
        "url" -> {
            val url = dto.url
            if (!url.isNullOrEmpty()) RespondoChecklistAction.Url(url, dto.newTab) else RespondoChecklistAction.Manual
        }
        "tour" -> RespondoChecklistAction.Tour(dto.tourId)
        else -> RespondoChecklistAction.Manual
    }

    fun toSender(dto: SenderDto?): RespondoSender? {
        val name = dto?.name?.takeIf { it.isNotEmpty() } ?: return null
        return RespondoSender(name = name, avatarUrl = dto.avatarUrl?.takeIf { it.isNotEmpty() })
    }
}
