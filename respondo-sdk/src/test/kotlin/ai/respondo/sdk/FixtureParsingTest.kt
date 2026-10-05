package ai.respondo.sdk

import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.realtime.WsParser
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.dto.BannersCatalogResponseDto
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.ChecklistsCatalogResponseDto
import ai.respondo.sdk.transport.dto.HistoryResponseDto
import ai.respondo.sdk.transport.dto.NewsListResponseDto
import ai.respondo.sdk.transport.dto.ResumeResponseDto
import ai.respondo.sdk.transport.dto.SurveysCatalogResponseDto
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяет, что все 15 фикстур разбираются в модели SDK без потерь ключевых полей. */
class FixtureParsingTest {

    @Test
    fun config_parses() {
        val dto = respondoJson.decodeFromString(WidgetConfigDto.serializer(), Fixtures.read("config.json"))
        assertEquals("#2563eb", dto.primaryColor)
        assertEquals(true, dto.identityVerificationEnabled)
        assertEquals("ru", dto.visitorLanguage)
        assertEquals(3, dto.quickQuestions.size)
        assertNotNull(dto.officeHours)
        assertEquals(true, dto.officeHours?.open)
    }

    @Test
    fun chatResponseFirst_hasAssistantMessageAndSuggestions() {
        val dto = respondoJson.decodeFromString(ChatResponseDto.serializer(), Fixtures.read("chat-response-first.json"))
        assertFalse(dto.humanHandover)
        assertNotNull(dto.message)
        assertEquals("assistant", dto.message?.role)
        assertEquals(3, dto.suggestedQuestions.size)
        assertEquals(2, dto.docLinks.size)
        assertNotNull(dto.sessionToken)
    }

    @Test
    fun chatResponseHandover_setsHumanHandover() {
        val dto = respondoJson.decodeFromString(ChatResponseDto.serializer(), Fixtures.read("chat-response-handover.json"))
        assertTrue(dto.humanHandover)
        assertNotNull(dto.message)
    }

    /**
     * Прод-инцидент: после эскалации виджет показывал клиенту кнопку
     * «View ticket #42466» со ссылкой на
     * https://<tenant>.zendesk.com/agent/tickets/42466 — staff-интерфейс
     * хелпдеска арендатора.
     *
     * Раньше фикстура handover содержала ticket_url/ticket_id, а этот сьют
     * утверждал их наличие — тесты защищали утечку. Утверждение перевёрнуто:
     * ни в одном клиентском payload не должно быть ключа со словом "ticket".
     * Скан рекурсивный, вложенное поле тоже не проскочит. Аналог
     * backend/internal/api/handlers/escalation_no_ticket_leak_test.go.
     */
    @Test
    fun customerFacingFixtures_carryNoTicketKeys() {
        val fixtures = listOf(
            "chat-response-handover.json",
            "chat-response-first.json",
            "resume.json",
            "history-page.json",
        )
        for (name in fixtures) {
            val keys = mutableListOf<String>()
            collectKeys(respondoJson.parseToJsonElement(Fixtures.read(name)), keys)
            val leaked = keys.filter { it.lowercase().contains("ticket") }
            assertTrue(
                "фикстура $name содержит ключи $leaked: номер и URL тикета — внутренние " +
                    "данные хелпдеска арендатора, клиенту они не уходят",
                leaked.isEmpty(),
            )
        }
    }

    /** Рекурсивно собирает имена всех ключей JSON-объектов, включая вложенные в массивы. */
    private fun collectKeys(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                out.add(key)
                collectKeys(value, out)
            }
            is JsonArray -> element.forEach { collectKeys(it, out) }
            else -> Unit
        }
    }

    @Test
    fun resume_parsesLiveConversation() {
        val dto = respondoJson.decodeFromString(ResumeResponseDto.serializer(), Fixtures.read("resume.json"))
        assertEquals("open", dto.status)
        assertEquals(2, dto.messages.size)
        assertNotNull(dto.conversationId)
        assertNotNull(dto.sessionToken)
        assertFalse(dto.hasMore)
    }

    @Test
    fun historyPage_hasMore() {
        val dto = respondoJson.decodeFromString(HistoryResponseDto.serializer(), Fixtures.read("history-page.json"))
        assertTrue(dto.hasMore)
        assertEquals(2, dto.messages.size)
        assertNotNull(dto.oldestMessageId)
    }

    @Test
    fun uploadResponse_parses() {
        val dto = respondoJson.decodeFromString(ChatAttachmentDto.serializer(), Fixtures.read("upload-response.json"))
        assertEquals("screenshot.png", dto.filename)
        assertEquals("image/png", dto.contentType)
        assertEquals(184320L, dto.size)
    }

    @Test
    fun newsList_parses() {
        val dto = respondoJson.decodeFromString(NewsListResponseDto.serializer(), Fixtures.read("news-list.json"))
        assertEquals(2, dto.items.size)
        assertEquals(1, dto.unread)
        assertFalse(dto.items.first().seen)
    }

    @Test
    fun checklistsList_parses() {
        val dto = respondoJson.decodeFromString(ChecklistsCatalogResponseDto.serializer(), Fixtures.read("checklists-list.json"))
        assertEquals(1, dto.checklists.size)
        val checklist = dto.checklists.first()
        assertEquals(2, checklist.tasks.size)
        assertEquals("started", checklist.progress.status)
        assertEquals("url", checklist.tasks.first().action.type)
    }

    @Test
    fun messageEventNew_parsesToNewMessage() {
        val event = WsParser.parse(Fixtures.read("message-event-new.json"))
        assertTrue(event is RealtimeEvent.NewMessage)
        val nm = event as RealtimeEvent.NewMessage
        assertEquals("Анна", nm.message.authorName)
        assertEquals("assistant", nm.message.role)
    }

    @Test
    fun messageEventStatus_parsesToMessageUpdated() {
        val event = WsParser.parse(Fixtures.read("message-event-status.json"))
        assertTrue(event is RealtimeEvent.MessageUpdated)
        val upd = (event as RealtimeEvent.MessageUpdated).update
        assertEquals("e9a3c1f6-4b8d-4e0a-b5f3-1d7b2a4e9c63", upd.id)
        assertEquals("delivered", upd.deliveryStatus)
    }

    @Test
    fun messageEventTyping_parsesToTyping() {
        val event = WsParser.parse(Fixtures.read("message-event-typing.json"))
        assertTrue(event is RealtimeEvent.Typing)
        val typing = event as RealtimeEvent.Typing
        assertTrue(typing.isTyping)
        assertEquals("Анна", typing.authorName)
    }

    @Test
    fun pushPayloadMessage_parses() {
        val payload = pushPayloadFrom("push-payload-message.json")
        assertNotNull(payload)
        assertEquals("message", payload?.type)
        assertEquals("Анна из поддержки", payload?.title)
        assertNotNull(payload?.conversationId)
        assertNotNull(payload?.messageId)
    }

    @Test
    fun pushPayloadCampaign_parses() {
        val payload = pushPayloadFrom("push-payload-campaign.json")
        assertNotNull(payload)
        assertEquals("campaign", payload?.type)
        assertNotNull(payload?.deliveryId)
    }

    @Test
    fun surveysList_parsesToTypedDto() {
        val dto = respondoJson.decodeFromString(SurveysCatalogResponseDto.serializer(), Fixtures.read("surveys-list.json"))
        assertEquals(1, dto.surveys.size)
        val survey = dto.surveys.first()
        assertEquals("in_modal", survey.content.surveyFormat)
        assertEquals(3, survey.content.questions.size)
        assertEquals("csat", survey.content.questions.first().type)
        assertEquals("Анна", survey.fromSender?.name)
    }

    @Test
    fun bannersList_parsesToTypedDto() {
        val dto = respondoJson.decodeFromString(BannersCatalogResponseDto.serializer(), Fixtures.read("banners-list.json"))
        assertEquals(1, dto.banners.size)
        val banner = dto.banners.first()
        assertEquals("url", banner.content.bannerAction)
        assertEquals("https://respondo.ai/pricing", banner.content.bannerUrl)
        assertEquals("bottom", banner.content.bannerPosition)
    }

    @Test
    fun nonRespondoPush_returnsNull() {
        assertNull(RespondoPushPayload.from(mapOf("foo" to "bar")))
    }

    /** Собирает FCM-подобный data-словарь: полезная нагрузка `respondo` — JSON-строка. */
    private fun pushPayloadFrom(fixture: String): RespondoPushPayload? {
        val root = respondoJson.parseToJsonElement(Fixtures.read(fixture)).jsonObject
        val respondoObj = root["respondo"]!!
        val asString = respondoJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), respondoObj)
        return RespondoPushPayload.from(mapOf("respondo" to asString))
    }
}
