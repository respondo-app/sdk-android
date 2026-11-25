package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.ConversationRating
import ai.respondo.sdk.core.RemarkStep
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.ApiException
import ai.respondo.sdk.transport.AuthParams
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.RatingRequestDto
import ai.respondo.sdk.transport.dto.StatusTextsDto
import ai.respondo.sdk.transport.dto.WidgetMessagesResponseDto
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оценка беседы и отзыв (behavior.md §12, test-plan «Оценка беседы и отзыв»): блок только под
 * закрытой беседой, чьё закрытие просило оценку; отзыв — одна запись и одна правка; тело POST
 * несёт `client=android`.
 */
class ConversationRatingTest {
    @Test
    fun visibleOnlyForAskedClose() {
        val asked = ControllerLogic.nextRating(ConversationRating(), "c1", true)
        assertTrue(asked.visibleFor("c1", true))
        assertFalse(asked.visibleFor("c1", false))
        assertFalse(asked.visibleFor("c2", true))
        assertFalse(ControllerLogic.nextRating(ConversationRating(), "c1", false).visibleFor("c1", true))
    }

    @Test
    fun remarkStepsOneEdit() {
        var r = ControllerLogic.nextRating(ConversationRating(), "c1", true).copy(value = 2)
        assertEquals(RemarkStep.OPEN, r.remarkStep)
        assertTrue(r.remarkVisible)
        r = ControllerLogic.savedRemark(r, "  slow ", true)
        assertEquals("slow", r.remark)
        assertEquals(RemarkStep.SENT, r.remarkStep)
        r = ControllerLogic.editRemark(r)
        assertEquals(RemarkStep.EDITING, r.remarkStep)
        // Ушёл из поля без изменений — назад к строке, правка цела.
        r = ControllerLogic.cancelRemarkEdit(r)
        assertEquals(RemarkStep.SENT, r.remarkStep)
        assertTrue(r.remarkEditable)
        assertSame(r, ControllerLogic.cancelRemarkEdit(r))
        r = ControllerLogic.editRemark(r)
        // Опрос во время правки её не сбивает.
        r = ControllerLogic.nextRating(r, "c1", true, value = 2, remark = "slow" to true)
        assertEquals(RemarkStep.EDITING, r.remarkStep)
        r = ControllerLogic.savedRemark(r, "slow, fixed", false)
        assertSame(r, ControllerLogic.editRemark(r))
        assertFalse(ControllerLogic.lockedRemark(r).remarkEditable)
    }

    @Test
    fun newCloseStartsEmpty() {
        val rated = ControllerLogic.nextRating(ConversationRating(), "c1", true, value = 2, remark = "waited" to false)
        assertEquals(RemarkStep.SENT, rated.remarkStep)
        assertEquals("", ControllerLogic.nextRating(rated, "c1", false).remark)
        assertEquals(0, ControllerLogic.nextRating(rated, "c2", true).value)
        assertNull(ControllerLogic.serverRemark(null, null))
    }

    /**
     * Язык беседы после хода едет и в ответе `/chat` (`lang`): на новую беседу SDK подписывается только после
     * ответа, и событие `conversation_updated{lang}` первого хода до него не доходит. Старый бэкенд — поля нет.
     */
    @Test
    fun chatResponseCarriesTurnLanguage() {
        val zh = respondoJson.decodeFromString(
            ChatResponseDto.serializer(),
            """{"conversation_id":"c1","human_handover":false,"lang":"zh-Hant"}""",
        )
        assertEquals("zh-Hant", zh.lang)
        val old = respondoJson.decodeFromString(ChatResponseDto.serializer(), """{"conversation_id":"c1","human_handover":false}""")
        assertNull(old.lang)
    }

    @Test
    fun pollFieldsAndTexts() {
        val poll = respondoJson.decodeFromString(
            WidgetMessagesResponseDto.serializer(),
            """{"messages":[],"rating_requested":true,"visitor_rating":4,"rating_remark":"ok","rating_remark_editable":false,
               "status_texts":{"rating_question":"Wie war Ihre Erfahrung?"}}""",
        )
        assertTrue(poll.ratingRequested)
        assertEquals(4, poll.visitorRating)
        assertEquals("ok" to false, ControllerLogic.serverRemark(poll.ratingRemark, poll.ratingRemarkEditable))
        val texts = ControllerLogic.mergeStatusTexts(ControllerLogic.StatusTexts(), poll.statusTexts)
        assertEquals("Wie war Ihre Erfahrung?", texts.ratingQuestion)
        assertEquals("Wie war Ihre Erfahrung?", ControllerLogic.mergeStatusTexts(texts, StatusTextsDto()).ratingQuestion)
    }

    @Test
    fun rateConversationPostsClientAndRaisesOn409() {
        val engine = FakeHttpEngine { HttpResponse(200, """{"ok":true,"score":2,"remark":"too slow","remark_editable":false}""") }
        val api = ApiClient("https://api.respondo.ai", engine)
        val resp = kotlinx.coroutines.runBlocking {
            api.rateConversation("c1", RatingRequestDto(score = 2, remark = "too slow"), AuthParams())
        }
        assertEquals("too slow", resp.remark)
        assertEquals(false, resp.remarkEditable)
        val sent = engine.lastRequestFor("/rating")!!
        val body = (sent.body as HttpBody.Json).text
        // Сырое тело, а не decode: decode снова подставил бы client по умолчанию и скрыл бы, что
        // поле не ушло (encodeDefaults=false).
        val json = respondoJson.parseToJsonElement(body).jsonObject
        assertEquals("android", json["client"]?.jsonPrimitive?.content)
        assertEquals(2, json["score"]?.jsonPrimitive?.int)
        assertEquals("too slow", json["remark"]?.jsonPrimitive?.content)
        // Без отзыва поле remark не уходит (null не пишется), client — уходит.
        val bare = respondoJson.parseToJsonElement(
            respondoJson.encodeToString(RatingRequestDto.serializer(), RatingRequestDto(score = 5)),
        ).jsonObject
        assertEquals("android", bare["client"]?.jsonPrimitive?.content)
        assertFalse(bare.containsKey("remark"))

        engine.handler = { HttpResponse(409, """{"error":"remark locked"}""") }
        val e = assertThrows(ApiException::class.java) {
            kotlinx.coroutines.runBlocking { api.rateConversation("c1", RatingRequestDto(score = 2, remark = "x"), AuthParams()) }
        }
        assertEquals(409, e.code)
    }
}
