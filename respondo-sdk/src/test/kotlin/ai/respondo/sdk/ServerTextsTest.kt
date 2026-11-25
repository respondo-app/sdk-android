package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.ApiException
import ai.respondo.sdk.transport.AuthParams
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.EscalationResponseDto
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.ResumeResponseDto
import ai.respondo.sdk.transport.dto.StatusTextsDto
import ai.respondo.sdk.transport.dto.WidgetMessagesResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Системные карточки на языке КЛИЕНТА: китайский клиент английского приложения после
 * китайского разговора получал карточки «передано человеку», «вернулся к ИИ», «чат завершён»
 * строками SDK на UI-локали приложения. Сервер отдаёт их на языке клиента (`handover_card_text`,
 * `status_texts`, `message` ответа `/escalate`); строки SDK — только фолбэк.
 */
class ServerTextsTest {

    private val zhCard = "您的请求已转交人工客服，请稍候。"
    private val zhBack = "对话已转回 AI 助手。有什么可以帮您？"
    private val zhEnded = "对话已结束。感谢您的咨询！"
    private val zhFailed = "暂时无法为您转接人工客服，请一分钟后再试。"

    @Test
    fun `dtos carry the server texts`() {
        val chat = respondoJson.decodeFromString(
            ChatResponseDto.serializer(),
            """{"conversation_id":"c","human_handover":true,"handover_card_text":"$zhCard",
               "status_texts":{"back_to_ai":"$zhBack","resolved":"$zhEnded"}}""",
        )
        assertEquals(zhCard, chat.handoverCardText)
        assertEquals(zhBack, chat.statusTexts?.backToAi)
        assertEquals(zhEnded, chat.statusTexts?.resolved)

        val poll = respondoJson.decodeFromString(
            WidgetMessagesResponseDto.serializer(),
            """{"messages":[],"status":"escalated","status_texts":{"resolved":"$zhEnded"}}""",
        )
        assertEquals(zhEnded, poll.statusTexts?.resolved)

        val escalate = respondoJson.decodeFromString(
            EscalationResponseDto.serializer(),
            """{"conversation_id":"c","status":"escalated","message":"$zhCard","status_texts":{"back_to_ai":"$zhBack"}}""",
        )
        assertEquals(zhBack, escalate.statusTexts?.backToAi)

        val legacy = respondoJson.decodeFromString(ResumeResponseDto.serializer(), """{"status":"open"}""")
        assertNull("старый бэкенд без поля", legacy.statusTexts)
    }

    @Test
    fun `remembered status texts merge, empty fields keep known ones`() {
        val first = ControllerLogic.mergeStatusTexts(ControllerLogic.StatusTexts(), StatusTextsDto(zhBack, zhEnded))
        assertEquals(ControllerLogic.StatusTexts(zhBack, zhEnded), first)
        val next = ControllerLogic.mergeStatusTexts(first, StatusTextsDto(backToAi = "  ", resolved = "Gespräch beendet."))
        assertEquals(zhBack, next.backToAi)
        assertEquals("Gespräch beendet.", next.resolved)
        assertEquals("кадр без текстов прежние не стирает", next, ControllerLogic.mergeStatusTexts(next, null))

        assertEquals(zhBack, ControllerLogic.serverTextOr(next.backToAi, "Chat returned to AI assistant."))
        assertEquals(
            "ничего от сервера — строка SDK",
            "Chat returned to AI assistant.",
            ControllerLogic.serverTextOr(ControllerLogic.StatusTexts().backToAi, "Chat returned to AI assistant."),
        )
    }

    @Test
    fun `handover card text prefers the response, then the notice, then the SDK string`() {
        val answer = message("""{"id":"x-1","role":"assistant","content":"关于提现问题，我为您转接人工客服。",
            "metadata":{"handover_notice":true,"handover_card_text":"$zhCard"}}""")
        assertEquals(zhCard, ControllerLogic.handoverCardText(null, answer, "sdk"))
        assertEquals("card", ControllerLogic.handoverCardText("card", answer, "sdk"))
        assertEquals("sdk", ControllerLogic.handoverCardText("  ", null, "sdk"))
        assertFalse(ControllerLogic.isCannedHandover(answer))

        val canned = message("""{"id":"x-2","role":"assistant","content":"$zhCard",
            "metadata":{"handover_notice":true,"handover_canned":true}}""")
        assertTrue("стандартное уведомление — само карточка", ControllerLogic.isCannedHandover(canned))
        assertFalse(ControllerLogic.isCannedHandover(null))

        // Эхо реплики клиента (тред уже у людей) — не уведомление: только карточка.
        assertEquals(answer, ControllerLogic.handoverNotice(answer))
        assertNull(ControllerLogic.handoverNotice(message("""{"id":"x-3","role":"user","content":"我要提现"}""")))
        assertNull(ControllerLogic.handoverNotice(null))
    }

    @Test
    fun `escalate refusal carries the customer text`() {
        val engine = FakeHttpEngine { HttpResponse(500, """{"error":"Internal error","message":"$zhFailed"}""") }
        val error = assertThrows(ApiException::class.java) {
            kotlinx.coroutines.runBlocking { ApiClient("https://api.respondo.ai", engine).escalate("c", AuthParams()) }
        }
        assertEquals(zhFailed, ControllerLogic.escalateRefusalText(error.bodyText))

        assertNull("отказ без message — строка SDK", ControllerLogic.escalateRefusalText("""{"error":"not found"}"""))
        assertNull(ControllerLogic.escalateRefusalText("<html>bad gateway</html>"))
        assertNull(ControllerLogic.escalateRefusalText("""{"message":42}"""))
        assertNull(ControllerLogic.escalateRefusalText(""))
        assertNull(ControllerLogic.escalateRefusalText(null))
    }

    private fun message(json: String): MessageDto = respondoJson.decodeFromString(MessageDto.serializer(), json)
}
