package ai.respondo.sdk

import ai.respondo.sdk.core.OverlayDecision
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.realtime.WsParser
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpResponse
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Публичный путь `identify()` (RespondoController.identify → EngagementController.identityChanged):
 * другой email/userId снимает опрос прежнего контакта, имя и userHash — нет (api-surface.md §3.3).
 */
class IdentifySurveyTest {
    private val campaignId = "5e1f3a7b-9c2d-4e8f-a0b1-c2d3e4f5a6b7"
    private val deliveryId = "6f2a4b8c-0d3e-4f9a-b1c2-d3e4f5a6b7c8"

    /** Контекст без Android-рантайма: контроллеру нужен только applicationContext. */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    // Минт отдаёт доставку A; каталог — её же для A и пустой для B.
    private val engine = FakeHttpEngine { req ->
        when {
            req.url.contains("survey_id=") -> HttpResponse(200, Fixtures.read("surveys-open.json"))
            req.url.contains("/widget/surveys") ->
                if (req.url.contains("b%40x.io") || req.url.contains("u-b")) HttpResponse(200, """{"surveys":[]}""")
                else HttpResponse(200, Fixtures.read("surveys-open.json"))
            else -> HttpResponse(200, """{"ok":true}""")
        }
    }

    private fun controller(identity: RespondoIdentity) = RespondoController(
        TestContext(),
        RespondoConfig(agentId = "agent-1", channelId = "chan-1"),
        identity,
        ApiClient("https://api.test", engine),
        OkHttpClient(),
        InMemoryKeyValueStore(),
    )

    private fun RespondoController.surveyOnScreen() =
        (engagement.activeOverlay.value as? OverlayDecision.Survey)?.survey

    /** Открывает опрос A и ждёт, пока он на экране. */
    private fun RespondoController.openSurveyA() = runBlocking {
        engagement.startSurvey(campaignId)
        withTimeout(5_000) { engagement.activeOverlay.first { it is OverlayDecision.Survey } }
        assertEquals(deliveryId, surveyOnScreen()?.deliveryId)
    }

    /** Ждёт, пока каталог, запрошенный identify(), вернётся и будет применён. */
    private fun settleCatalogue(before: Int) = runBlocking {
        withTimeout(5_000) {
            while (engine.requests.count { it.url.contains("/widget/surveys") && !it.url.contains("survey_id=") } <= before) {
                delay(10)
            }
        }
        delay(100)
    }

    private fun catalogueRequests() =
        engine.requests.count { it.url.contains("/widget/surveys") && !it.url.contains("survey_id=") }

    @Test
    fun identify_otherEmail_dropsOpenedSurvey() {
        val c = controller(RespondoIdentity(email = "a@x.io"))
        c.openSurveyA()
        val before = catalogueRequests()
        c.identify(RespondoIdentity(email = "b@x.io"))
        assertEquals("опрос A снят сразу", null, c.surveyOnScreen())
        settleCatalogue(before)
        assertEquals("каталог B не возвращает доставку A", null, c.surveyOnScreen())
        assertTrue(engine.requests.last { it.url.contains("/widget/surveys") }.url.contains("b%40x.io"))
    }

    @Test
    fun identify_otherUserId_dropsOpenedSurvey() {
        val c = controller(RespondoIdentity(userId = "u-a"))
        c.openSurveyA()
        c.identify(RespondoIdentity(userId = "u-b"))
        assertEquals(null, c.surveyOnScreen())
    }

    @Test
    fun identify_anonymousToUser_dropsOpenedSurvey() {
        val c = controller(RespondoIdentity())
        c.openSurveyA()
        c.identify(RespondoIdentity(email = "b@x.io"))
        assertEquals(null, c.surveyOnScreen())
    }

    @Test
    fun identify_nameOrUserHashOnly_keepsOpenedSurvey() {
        val c = controller(RespondoIdentity(email = "a@x.io"))
        c.openSurveyA()
        val before = catalogueRequests()
        c.identify(RespondoIdentity(email = " A@x.io ", name = "Ann", userHash = "h"))
        assertEquals(deliveryId, c.surveyOnScreen()?.deliveryId)
        settleCatalogue(before)
        assertEquals("тот же контакт: опрос остаётся", deliveryId, c.surveyOnScreen()?.deliveryId)
    }

    /** Кадр overlay.show с опросом из surveys-list (без правил) и штампом [email] (`null` — без штампа). */
    private fun overlayFrame(email: String?): RealtimeEvent.OverlayShow {
        val items = respondoJson.parseToJsonElement(Fixtures.read("surveys-list.json")).jsonObject["surveys"]!!
        val data = buildJsonObject {
            put("items", items)
            if (email != null) put("identity", buildJsonObject { put("user_id", ""); put("email", email) })
        }
        val frame = buildJsonObject { put("type", "overlay.show"); put("data", data) }
        return WsParser.parse(frame.toString()) as RealtimeEvent.OverlayShow
    }

    @Test
    fun overlayShow_parsesIdentityStamp() {
        assertEquals(Pair(null, "a@x.io"), overlayFrame(" A@X.io ").contact)
        assertNull(overlayFrame(null).contact)
    }

    /** Сокет переживает identify(): кадр A, пришедший после identify(B), до опросов B не доходит. */
    @Test
    fun staleOverlayFrameOfPreviousContact_isDropped() {
        val c = controller(RespondoIdentity(email = "a@x.io"))
        val before = catalogueRequests()
        c.identify(RespondoIdentity(email = "b@x.io"))
        settleCatalogue(before)
        c.applyOverlayShow(overlayFrame("a@x.io"))
        assertNull("кадр A отброшен", c.surveyOnScreen())
        c.applyOverlayShow(overlayFrame(null))
        assertNull("кадр без штампа отброшен", c.surveyOnScreen())
        c.applyOverlayShow(overlayFrame("B@x.io"))
        runBlocking { withTimeout(5_000) { c.engagement.activeOverlay.first { it is OverlayDecision.Survey } } }
        assertNotNull("кадр текущего контакта применён", c.surveyOnScreen())
    }
}
