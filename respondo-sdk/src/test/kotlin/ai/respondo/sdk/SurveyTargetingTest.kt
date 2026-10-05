package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.EngagementController
import ai.respondo.sdk.core.EngagementHost
import ai.respondo.sdk.core.EngagementMapper
import ai.respondo.sdk.core.EngagementParams
import ai.respondo.sdk.core.OverlayDecision
import ai.respondo.sdk.core.RespondoScreenRule
import ai.respondo.sdk.core.RespondoSurveyTargeting
import ai.respondo.sdk.core.SurveyTargeting
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.realtime.IdentifyFrame
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpEngine
import ai.respondo.sdk.transport.HttpRequest
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.dto.SurveysCatalogResponseDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScreenHost(var screen: String?) : EngagementHost {
    override fun engagementParams() = EngagementParams("agent-1", "chan-1", "v_test", null, null, null)
    override fun engagementVisitorId() = "v_test"
    override fun engagementLang() = "en"
    override fun engagementScreen() = screen
    override fun isChatOpen() = false
    override fun requestOpenUrl(url: String) = true
    override fun openUrlExternally(url: String) = Unit
}

/** «Когда и где» оверлей-опросов в приложении: чистая часть и поведение контроллера. */
@OptIn(ExperimentalCoroutinesApi::class)
class SurveyTargetingTest {
    private val campaignId = "5e1f3a7b-9c2d-4e8f-a0b1-c2d3e4f5a6b7"
    private val deliveryId = "6f2a4b8c-0d3e-4f9a-b1c2-d3e4f5a6b7c8"

    // ==================== чистая часть ====================

    @Test
    fun screenRules_anyMatchAndExclusionsAlwaysHold() {
        val rules = listOf(
            RespondoScreenRule("starts_with", "Checkout"),
            RespondoScreenRule("exact", "Cart"),
            RespondoScreenRule("not_contains", "Error"),
        )
        assertTrue(SurveyTargeting.screenMatches(rules, "CheckoutPayment"))
        assertTrue(SurveyTargeting.screenMatches(rules, "Cart"))
        assertFalse(SurveyTargeting.screenMatches(rules, "CheckoutError"))
        assertFalse(SurveyTargeting.screenMatches(rules, "Home"))
        assertFalse("экран не задан — правилам не подходит", SurveyTargeting.screenMatches(rules, null))
        assertTrue("нет правил — любой экран", SurveyTargeting.screenMatches(emptyList(), null))
    }

    @Test
    fun eventCanonAndDelayClampMatchBackend() {
        assertEquals("order_placed", SurveyTargeting.normalizeEventName("  Order   Placed "))
        assertEquals(0, SurveyTargeting.clampDelay(-5))
        assertEquals(600, SurveyTargeting.clampDelay(9999))
    }

    @Test
    fun readiness_waitsForEventThenDelay() {
        val targeting = RespondoSurveyTargeting(delaySeconds = 10, triggerEvent = "order_placed")
        assertEquals(
            SurveyTargeting.Readiness.Event,
            SurveyTargeting.readiness(targeting, "Home", 1_000, emptyMap(), 61_000),
        )
        assertEquals(
            "задержка считается от события",
            SurveyTargeting.Readiness.Delay(6_000),
            SurveyTargeting.readiness(targeting, "Home", 1_000, mapOf("order_placed" to 30_000L), 34_000),
        )
        assertEquals(
            SurveyTargeting.Readiness.Ready,
            SurveyTargeting.readiness(targeting, "Home", 1_000, mapOf("order_placed" to 30_000L), 40_000),
        )
        assertEquals(
            "событие на прошлом экране: задержка от прихода на этот",
            SurveyTargeting.Readiness.Delay(6_000),
            SurveyTargeting.readiness(targeting, "Home", 100_000, mapOf("order_placed" to 30_000L), 104_000),
        )
    }

    @Test
    fun readiness_resumedOpensAnywhereButNeverOffItsPlatform() {
        val targeting = RespondoSurveyTargeting(
            screenRules = listOf(RespondoScreenRule("exact", "Checkout")), delaySeconds = 30, triggerEvent = "x",
        )
        assertEquals(SurveyTargeting.Readiness.Elsewhere, SurveyTargeting.readiness(targeting, "Home", 0, emptyMap(), 0))
        assertEquals(
            "доставка есть — опрос продолжается на любом экране",
            SurveyTargeting.Readiness.Ready,
            SurveyTargeting.readiness(targeting, "Home", 0, emptyMap(), 0, resumed = true),
        )
        assertEquals(
            SurveyTargeting.Readiness.Elsewhere,
            SurveyTargeting.readiness(RespondoSurveyTargeting(showsInApps = false), "Home", 0, emptyMap(), 0, resumed = true),
        )
    }

    @Test
    fun events_liveForTheSession() {
        val now = 10 * SurveyTargeting.EVENT_TTL_MS
        val events = mutableMapOf(
            "a" to now - 60_000,
            "stale" to now - SurveyTargeting.EVENT_TTL_MS - 1,
            "future" to now + 5_000,
        )
        SurveyTargeting.dropStaleEvents(events, now)
        assertEquals(setOf("a"), events.keys)
    }

    @Test
    fun targetedFixture_mapsScreenRulesAndIgnoresUrlRules() {
        val dto = respondoJson.decodeFromString(SurveysCatalogResponseDto.serializer(), Fixtures.read("surveys-targeted.json"))
        val survey = EngagementMapper.toSurvey(dto.surveys.first())
        assertEquals("", survey.deliveryId)
        assertEquals(2, survey.targeting.screenRules.size)
        assertEquals("order_placed", survey.targeting.triggerEvent)
        assertTrue(SurveyTargeting.screenMatches(survey.targeting.screenRules, "Checkout"))
    }

    @Test
    fun platform_readCaseAndWhitespaceInsensitively() {
        fun mapped(platform: String) = EngagementMapper.toSurvey(
            respondoJson.decodeFromString(
                SurveysCatalogResponseDto.serializer(),
                """{"surveys":[{"campaign_id":"c","delivery_id":"","content":{"survey_format":"in_modal",
                "survey_platform":"$platform","questions":[]}}]}""",
            ).surveys.first(),
        ).targeting.showsInApps
        assertFalse(mapped("web"))
        assertFalse(mapped(" WEB "))
        assertFalse(mapped("Web"))
        assertTrue(mapped("apps"))
        assertTrue(mapped(""))
        assertTrue(SurveyTargeting.showsInApps(null))
    }

    @Test
    fun identifyFrame_declaresSurveyTargeting() {
        val json = respondoJson.encodeToString(IdentifyFrame.serializer(), IdentifyFrame(visitorId = "v"))
        assertTrue(json, json.contains("\"features\":[\"survey_targeting\"]"))
        assertTrue(json, json.contains("\"platform\":\"app\""))
    }

    // ==================== контроллер ====================

    private fun targetedEngine() = FakeHttpEngine { req ->
        when {
            req.url.contains("survey_id=") -> HttpResponse(200, Fixtures.read("surveys-open.json"))
            req.url.contains("/widget/surveys") -> HttpResponse(200, Fixtures.read("surveys-targeted.json"))
            else -> HttpResponse(200, """{"ok":true}""")
        }
    }

    private fun TestScope.controller(
        engine: HttpEngine,
        host: ScreenHost,
        store: KeyValueStore = InMemoryKeyValueStore(),
    ) = EngagementController(ApiClient("https://api.test", engine), host, this, store) { testScheduler.currentTime }

    private fun EngagementController.surveyOnScreen() =
        (activeOverlay.value as? OverlayDecision.Survey)?.survey

    @Test
    fun targetedSurvey_opensOnlyOnMatchingScreenAfterEvent() = runTest {
        val engine = targetedEngine()
        val host = ScreenHost("Home")
        val c = controller(engine, host)
        c.loadCatalogs()
        runCurrent()
        assertTrue(engine.requests.first().url.contains("features=survey_targeting"))
        assertTrue(engine.requests.first().url.contains("platform=app"))

        host.screen = "Checkout"
        c.screenDidChange()
        runCurrent()
        assertNull("нужный экран, но события ещё не было", c.surveyOnScreen())

        host.screen = "Home"
        c.screenDidChange()
        c.eventTracked("Order placed")
        runCurrent()
        assertNull("не тот экран — событие опрос не открывает", c.surveyOnScreen())
        assertNull(engine.lastRequestFor("survey_id="))

        // Событие пережило смену экрана: track перед переходом на экран опроса.
        host.screen = "Checkout"
        c.screenDidChange()
        runCurrent()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
        assertFalse(engine.lastRequestFor("survey_id=")!!.url.contains("source=api"))

        // Начатый опрос следует за посетителем на другой экран.
        host.screen = "Home"
        c.screenDidChange()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)

        // Повторная загрузка каталога (без доставки) не затирает открытый опрос.
        c.loadCatalogs()
        runCurrent()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
    }

    /** Событие сессии на экране без опроса, смена контакта, каталог нового контакта и экран опроса. */
    private fun TestScope.eventSurvivesIdentityChange(fromAnonymous: Boolean): Pair<Boolean, Boolean> {
        val engine = targetedEngine()
        val host = ScreenHost("Home")
        val c = controller(engine, host)
        c.loadCatalogs()
        runCurrent()
        c.eventTracked("Order placed")
        c.identityChanged(fromAnonymous = fromAnonymous)
        c.loadCatalogs()
        runCurrent()
        host.screen = "Checkout"
        c.screenDidChange()
        runCurrent()
        val opened = c.surveyOnScreen() != null
        val minted = engine.lastRequestFor("survey_id=") != null
        c.clear()
        return opened to minted
    }

    @Test
    fun identityChanged_anotherUsersTrackedEventDoesNotOpenSurvey() = runTest {
        val (opened, minted) = eventSurvivesIdentityChange(fromAnonymous = false)
        assertFalse("track() пользователя A не открывает опрос пользователю B", opened)
        assertFalse(minted)
    }

    @Test
    fun identityChanged_anonymousToIdentifiedKeepsSessionEvents() = runTest {
        val (opened, _) = eventSurvivesIdentityChange(fromAnonymous = true)
        assertTrue("аноним → пользователь — тот же человек, событие остаётся", opened)
    }

    @Test
    fun delay_opensAfterTimeOnScreen() = runTest {
        // Каталог отдаёт опрос без доставки, минт по survey_id — тот же опрос с доставкой.
        fun catalog(delivery: String) =
            """{"surveys":[{"campaign_id":"$campaignId","delivery_id":"$delivery","name":"N",
               "content":{"survey_format":"in_modal","survey_delay_seconds":5,
               "questions":[{"id":"q1","type":"csat","title":"?"}]}}]}"""
        val engine = FakeHttpEngine { req ->
            when {
                req.url.contains("survey_id=") -> HttpResponse(200, catalog(deliveryId))
                req.url.contains("/widget/surveys") -> HttpResponse(200, catalog(""))
                else -> HttpResponse(200, """{"ok":true}""")
            }
        }
        val c = controller(engine, ScreenHost("Home"))
        c.loadCatalogs()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertNull(c.surveyOnScreen())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
    }

    @Test
    fun openedSurvey_resumesOnAnyScreenAfterRelaunch() = runTest {
        // Каталог после перезапуска: доставка уже есть (опрос открывали), экран чужой.
        val opened = Fixtures.read("surveys-targeted.json")
            .replace("\"delivery_id\": \"\"", "\"delivery_id\": \"$deliveryId\"")
        val engine = FakeHttpEngine { req ->
            if (req.url.contains("/widget/surveys")) HttpResponse(200, opened) else HttpResponse(200, """{"ok":true}""")
        }
        val c = controller(engine, ScreenHost("Settings"))
        c.loadCatalogs()
        runCurrent()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
        assertNull("доставка уже есть — минт не нужен", engine.lastRequestFor("survey_id="))
    }

    @Test
    fun startSurvey_opensExplicitlyAnywhere() = runTest {
        val engine = targetedEngine()
        val c = controller(engine, ScreenHost("Settings"))
        c.startSurvey(campaignId)
        runCurrent()
        assertTrue(engine.lastRequestFor("survey_id=")!!.url.contains("source=api"))
        assertEquals(campaignId, c.surveyOnScreen()?.campaignId)
    }

    @Test
    fun dismissedSurvey_staysClosedAfterRelaunch() = runTest {
        // Опрос с доставкой нацелен на «Checkout»; посетитель закрыл его, приложение перезапущено
        // на чужом экране — опрос не возвращается, хотя правило «доставка есть — продолжается» есть.
        val opened = Fixtures.read("surveys-targeted.json")
            .replace("\"delivery_id\": \"\"", "\"delivery_id\": \"$deliveryId\"")
        val engine = FakeHttpEngine { req ->
            if (req.url.contains("/widget/surveys")) HttpResponse(200, opened) else HttpResponse(200, """{"ok":true}""")
        }
        val store = InMemoryKeyValueStore()
        val first = controller(engine, ScreenHost("Settings"), store)
        first.loadCatalogs()
        runCurrent()
        assertEquals(deliveryId, first.surveyOnScreen()?.deliveryId)
        first.dismissSurvey(deliveryId)
        assertNull(first.surveyOnScreen())

        val relaunched = controller(engine, ScreenHost("Settings"), store)
        relaunched.loadCatalogs()
        runCurrent()
        assertNull("закрытый опрос не возвращается после перезапуска", relaunched.surveyOnScreen())

        // Явный startSurvey показывает его снова и снимает отметку.
        val explicitEngine = targetedEngine()
        val again = controller(explicitEngine, ScreenHost("Settings"), store)
        again.startSurvey(campaignId)
        runCurrent()
        assertEquals(deliveryId, again.surveyOnScreen()?.deliveryId)
    }

    @Test
    fun clear_duringOpen_dropsThePreviousContactsSurvey() = runTest {
        // Минт доставки ещё идёт, когда host вызывает reset(): ответ — опрос и доставка прежнего
        // контакта, новому они не достаются.
        val gate = CompletableDeferred<Unit>()
        val engine = object : HttpEngine {
            val delegate = targetedEngine()
            override suspend fun execute(request: HttpRequest): HttpResponse {
                if (request.url.contains("survey_id=")) gate.await()
                return delegate.execute(request)
            }
        }
        val c = controller(engine, ScreenHost("Settings"))
        c.startSurvey(campaignId)
        runCurrent()
        c.clear()
        gate.complete(Unit)
        runCurrent()
        assertNull(c.surveyOnScreen())

        // Ответ уже получен, и reset() успевает между ним и записью (поток с ответом ждёт
        // stateLock): отмена запроса тут ничего не меняет, опрос отбрасывает поколение.
        lateinit var d: EngagementController
        val racing = FakeHttpEngine { req ->
            when {
                req.url.contains("survey_id=") -> {
                    d.clear()
                    HttpResponse(200, Fixtures.read("surveys-open.json"))
                }
                req.url.contains("/widget/surveys") -> HttpResponse(200, Fixtures.read("surveys-targeted.json"))
                else -> HttpResponse(200, """{"ok":true}""")
            }
        }
        d = controller(racing, ScreenHost("Settings"))
        d.startSurvey(campaignId)
        runCurrent()
        assertNull("ответ прежнего контакта отброшен по поколению", d.surveyOnScreen())
    }

    @Test
    fun contactKey_userIdAndEmailOnly() {
        val a = RespondoIdentity(userId = "u1", email = "A@x.io", name = "A")
        assertEquals(ControllerLogic.contactKey(a), ControllerLogic.contactKey(a.copy(email = " a@x.io ", name = "B", userHash = "h")))
        assertNotEquals(ControllerLogic.contactKey(a), ControllerLogic.contactKey(a.copy(email = "b@x.io")))
        assertNotEquals(ControllerLogic.contactKey(RespondoIdentity()), ControllerLogic.contactKey(a))
    }

    @Test
    fun identityChanged_whileOpenInFlight_leavesNoPreviousDelivery() = runTest {
        // identify(B), пока минт доставки контакта A ещё идёт: доставка A не достаётся B.
        val gate = CompletableDeferred<Unit>()
        val engine = object : HttpEngine {
            val delegate = targetedEngine()
            override suspend fun execute(request: HttpRequest): HttpResponse {
                if (request.url.contains("survey_id=")) gate.await()
                return delegate.execute(request)
            }
        }
        val c = controller(engine, ScreenHost("Settings"))
        c.startSurvey(campaignId)
        runCurrent()
        c.identityChanged(fromAnonymous = false)
        gate.complete(Unit)
        runCurrent()
        assertNull("минт контакта A отброшен", c.surveyOnScreen())

        // Каталог B без доставки: открытая доставка A не подмешивается по campaign_id.
        c.loadCatalogs()
        runCurrent()
        assertNull(c.surveyOnScreen())
    }

    @Test
    fun identityChanged_dropsOpenedDeliveryAndStaleCatalogue() = runTest {
        // Опрос A открыт (доставка есть) — identify(B) его снимает, и он не возвращается.
        val c = controller(targetedEngine(), ScreenHost("Settings"))
        c.startSurvey(campaignId)
        runCurrent()
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
        c.identityChanged(fromAnonymous = false)
        assertNull(c.surveyOnScreen())
        c.loadCatalogs()
        runCurrent()
        assertNull("каталог без доставки не наследует доставку A", c.surveyOnScreen())

        // Каталог A (с доставкой) приходит после каталога B: он устарел и отбрасывается.
        val gate = CompletableDeferred<Unit>()
        var catalogCalls = 0
        val engine = object : HttpEngine {
            override suspend fun execute(request: HttpRequest): HttpResponse = when {
                request.url.contains("/widget/surveys") -> {
                    catalogCalls++
                    if (catalogCalls == 1) {
                        gate.await()
                        HttpResponse(200, Fixtures.read("surveys-open.json"))
                    } else {
                        HttpResponse(200, """{"surveys":[]}""")
                    }
                }
                else -> HttpResponse(200, """{"banners":[]}""")
            }
        }
        val d = controller(engine, ScreenHost("Settings"))
        d.loadCatalogs()
        runCurrent()
        d.identityChanged(fromAnonymous = false)
        d.loadCatalogs()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull("каталог контакта A, пришедший последним, отброшен", d.surveyOnScreen())

        // Ответ A уже получен, и identify(B) успевает до записи: отмена не помогает, решает поколение.
        lateinit var e: EngagementController
        val racing = FakeHttpEngine { req ->
            when {
                req.url.contains("/widget/surveys") -> {
                    e.identityChanged(fromAnonymous = false)
                    HttpResponse(200, Fixtures.read("surveys-open.json"))
                }
                else -> HttpResponse(200, """{"banners":[]}""")
            }
        }
        e = controller(racing, ScreenHost("Settings"))
        e.loadCatalogs()
        runCurrent()
        assertNull(e.surveyOnScreen())
    }
}
