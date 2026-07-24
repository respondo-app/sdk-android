package ai.respondo.sdk

import ai.respondo.sdk.core.EngagementController
import ai.respondo.sdk.core.EngagementHost
import ai.respondo.sdk.core.EngagementMapper
import ai.respondo.sdk.core.EngagementParams
import ai.respondo.sdk.core.OverlayArbiter
import ai.respondo.sdk.core.OverlayArbiterInput
import ai.respondo.sdk.core.OverlayDecision
import ai.respondo.sdk.core.RespondoBannerAction
import ai.respondo.sdk.core.RespondoBannerLayout
import ai.respondo.sdk.core.RespondoBannerPosition
import ai.respondo.sdk.core.RespondoChecklistAction
import ai.respondo.sdk.core.RespondoSurvey
import ai.respondo.sdk.core.RespondoSurveyAnswer
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpRequest
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.dto.BannerCatalogItemDto
import ai.respondo.sdk.transport.dto.SurveyCatalogItemDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тестовый host: фиксированные параметры контакта, экран и открытие URL. */
private class TestEngagementHost(
    var chatOpen: Boolean = false,
    var screen: String? = "Home",
) : EngagementHost {
    val openedUrls = mutableListOf<String>()

    override fun engagementParams() = EngagementParams(
        agentId = "agent-1",
        channelId = "chan-1",
        visitorId = "v_test",
        email = null,
        userId = null,
        userHash = null,
    )

    override fun engagementVisitorId() = "v_test"
    override fun engagementLang() = "en"
    override fun engagementScreen() = screen
    override fun isChatOpen() = chatOpen
    override fun requestOpenUrl(url: String): Boolean {
        openedUrls += url
        return true
    }

    val externallyOpenedUrls = mutableListOf<String>()
    override fun openUrlExternally(url: String) {
        externallyOpenedUrls += url
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementTest {

    private fun surveyFixtureDto(): SurveyCatalogItemDto {
        val item = respondoJson.parseToJsonElement(Fixtures.read("surveys-list.json"))
            .jsonObject["surveys"]!!.jsonArray.first().jsonObject
        return respondoJson.decodeFromJsonElement(SurveyCatalogItemDto.serializer(), item)
    }

    private fun surveyFixtureItem(): JsonObject =
        respondoJson.parseToJsonElement(Fixtures.read("surveys-list.json"))
            .jsonObject["surveys"]!!.jsonArray.first().jsonObject

    private fun bannerFixtureItem(): JsonObject =
        respondoJson.parseToJsonElement(Fixtures.read("banners-list.json"))
            .jsonObject["banners"]!!.jsonArray.first().jsonObject

    private fun sampleSurvey(): RespondoSurvey = EngagementMapper.toSurvey(surveyFixtureDto())

    private fun sampleBanner() = EngagementMapper.toBanner(
        respondoJson.decodeFromJsonElement(
            BannerCatalogItemDto.serializer(), bannerFixtureItem(),
        ),
    )

    // ==================== арбитр ====================

    @Test
    fun arbiter_surveyBeatsBanner() {
        val decision = OverlayArbiter.decide(
            OverlayArbiterInput(
                surveys = listOf(sampleSurvey()),
                banners = listOf(sampleBanner()),
                dismissed = emptySet(),
                lightboxOpen = false,
                composerHasText = false,
            ),
        )
        assertTrue(decision is OverlayDecision.Survey)
    }

    @Test
    fun arbiter_suppressedByLightboxAndComposer() {
        val input = OverlayArbiterInput(
            surveys = listOf(sampleSurvey()),
            banners = listOf(sampleBanner()),
            dismissed = emptySet(),
            lightboxOpen = true,
            composerHasText = false,
        )
        assertTrue(OverlayArbiter.decide(input) is OverlayDecision.None)
        assertTrue(OverlayArbiter.decide(input.copy(lightboxOpen = false, composerHasText = true)) is OverlayDecision.None)
    }

    @Test
    fun arbiter_dismissedSurveyFallsBackToBanner() {
        val survey = sampleSurvey()
        val decision = OverlayArbiter.decide(
            OverlayArbiterInput(
                surveys = listOf(survey),
                banners = listOf(sampleBanner()),
                dismissed = setOf(survey.deliveryId),
                lightboxOpen = false,
                composerHasText = false,
            ),
        )
        assertTrue(decision is OverlayDecision.Banner)
    }

    // ==================== маппер ====================

    @Test
    fun mapper_surveyDerivesStepsPerQuestion() {
        val survey = sampleSurvey()
        // survey_steps в фикстуре не задан → шаг на каждый вопрос (3).
        assertEquals(3, survey.steps.size)
        assertEquals(listOf("q_csat"), survey.steps.first())
    }

    @Test
    fun mapper_bannerFields() {
        val banner = sampleBanner()
        assertEquals(RespondoBannerAction.URL, banner.action)
        assertEquals(RespondoBannerLayout.FLOATING, banner.layout)
        assertEquals(RespondoBannerPosition.BOTTOM, banner.position)
        assertEquals("https://respondo.ai/pricing", banner.url)
    }

    @Test
    fun mapper_checklistTourTasksHidden() {
        val dto = respondoJson.decodeFromString(
            ai.respondo.sdk.transport.dto.ChecklistsCatalogResponseDto.serializer(),
            Fixtures.read("checklists-list.json"),
        )
        val checklist = EngagementMapper.toChecklist(dto.checklists.first())
        assertEquals(2, checklist.tasks.size)
        assertTrue(checklist.tasks.any { it.action is RespondoChecklistAction.Url })
        assertTrue(checklist.tasks.any { it.action is RespondoChecklistAction.Manual })
        // task-connect-inbox отмечен выполненным.
        assertTrue(checklist.doneTaskIds.contains("task-connect-inbox"))
    }

    // ==================== контроллер: каталоги + оверлей ====================

    private fun routingEngine(proactiveCode: Int = 204, proactiveBody: String = ""): FakeHttpEngine =
        FakeHttpEngine { req ->
            val url = req.url
            when {
                url.contains("/widget/surveys") -> HttpResponse(200, Fixtures.read("surveys-list.json"))
                url.contains("/widget/banners") -> HttpResponse(200, Fixtures.read("banners-list.json"))
                url.contains("/widget/news") && !url.contains("/seen") -> HttpResponse(200, Fixtures.read("news-list.json"))
                url.contains("/widget/checklists") && !url.contains("/progress") -> HttpResponse(200, Fixtures.read("checklists-list.json"))
                url.contains("/widget/proactive") -> HttpResponse(proactiveCode, proactiveBody)
                else -> HttpResponse(200, """{"ok":true}""")
            }
        }

    @Test
    fun loadCatalogs_populatesSurveyOverlayAndBanners() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.loadCatalogs()
        advanceUntilIdle()
        assertTrue(controller.activeOverlay.value is OverlayDecision.Survey)
        assertEquals(1, controller.banners.value.size)
    }

    @Test
    fun surveyFlow_answersEachStepThenSubmits() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.loadCatalogs()
        advanceUntilIdle()
        val survey = controller.activeSurvey()!!

        // Шаг 1: обязательный CSAT.
        assertEquals("q_csat", controller.currentStepQuestions(survey).first().id)
        controller.answerQuestion(survey, "q_csat", RespondoSurveyAnswer.Number(5.0))
        assertTrue(controller.canAdvance(survey))
        controller.advanceSurvey(survey)
        assertEquals(1, controller.surveyStepIndex.value)

        // Шаг 2: choice (не обязателен) → сразу можно дальше.
        assertTrue(controller.canAdvance(survey))
        controller.advanceSurvey(survey)
        // Шаг 3: text → завершение.
        controller.advanceSurvey(survey)
        advanceUntilIdle()

        assertTrue(controller.surveyFinished.value)
        assertNotNull(engine.lastRequestFor("/widget/survey/answer"))
        assertNotNull(engine.lastRequestFor("/widget/survey/submit"))
    }

    @Test
    fun checklists_startedThenTaskDone() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.loadChecklists()
        advanceUntilIdle()
        assertEquals(1, controller.checklists.value.size)
        // started отправлен при первом показе.
        assertTrue(bodyOf(engine.lastRequestFor("/progress")).contains("started"))

        // Тап по manual-задаче (task-invite-team ещё не выполнена).
        controller.performTask("7a3b5c9d-1e6f-4a0b-c2d4-5e7f9a1b3c6d", "task-invite-team")
        advanceUntilIdle()
        assertTrue(bodyOf(engine.lastRequestFor("/progress")).contains("task_done"))
    }

    @Test
    fun checklists_urlTaskOpensUrl() = runTest {
        val engine = routingEngine()
        val host = TestEngagementHost()
        val controller = EngagementController(ApiClient("https://api.test", engine), host, this)
        controller.loadChecklists()
        advanceUntilIdle()
        controller.performTask("7a3b5c9d-1e6f-4a0b-c2d4-5e7f9a1b3c6d", "task-connect-inbox")
        advanceUntilIdle()
        assertTrue(host.openedUrls.contains("https://app.respondo.ai/channels"))
    }

    @Test
    fun news_markSeenDecrementsUnread() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.loadNews()
        advanceUntilIdle()
        assertEquals(1, controller.newsUnread.value)
        val firstId = controller.news.value.first().id
        controller.markNewsSeen(firstId)
        advanceUntilIdle()
        assertEquals(0, controller.newsUnread.value)
        assertNotNull(engine.lastRequestFor("/seen"))
    }

    @Test
    fun banner_reactionPostsResponse() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.loadCatalogs()
        advanceUntilIdle()
        val banner = controller.banners.value.first()
        controller.bannerReaction(banner, "👍")
        advanceUntilIdle()
        val body = bodyOf(engine.lastRequestFor("/widget/banner/response"))
        assertTrue(body.contains("reaction"))
    }

    @Test
    fun proactive_204YieldsNothing() = runTest {
        val engine = routingEngine(proactiveCode = 204)
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.scheduleProactive(0)
        advanceUntilIdle()
        assertNull(controller.proactive.value)
    }

    @Test
    fun proactive_200EmitsMessage() = runTest {
        val engine = routingEngine(proactiveCode = 200, proactiveBody = """{"message":"Need help checking out?","page_path":"/cart"}""")
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(chatOpen = false), this)
        controller.scheduleProactive(0)
        advanceUntilIdle()
        assertEquals("Need help checking out?", controller.proactive.value?.text)
    }

    @Test
    fun applyOverlayItems_classifiesSurveyAndBanner() = runTest {
        val engine = routingEngine()
        val controller = EngagementController(ApiClient("https://api.test", engine), TestEngagementHost(), this)
        controller.applyOverlayItems(listOf(surveyFixtureItem(), bannerFixtureItem()))
        advanceUntilIdle()
        assertTrue(controller.activeOverlay.value is OverlayDecision.Survey)
        assertEquals(1, controller.banners.value.size)
    }

    private fun bodyOf(req: HttpRequest?): String = (req?.body as? HttpBody.Json)?.text.orEmpty()
}
