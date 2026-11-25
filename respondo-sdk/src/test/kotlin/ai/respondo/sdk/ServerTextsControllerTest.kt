package ai.respondo.sdk

import ai.respondo.sdk.core.ChatMessage
import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.i18n.Strings
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpRequest
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.dto.StatusTextsDto
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проводка [RespondoController] для системных карточек на языке КЛИЕНТА: ответ
 * `/chat` с передачей (стандартное уведомление, ответ модели, humans-only, эхо реплики клиента),
 * «Talk to a person» (ответ и отказ `/escalate`), переходы статуса по WS и поллингу на запомненных
 * `status_texts`, сброс в `reset`. Чистые решения — в [ServerTextsTest].
 */
class ServerTextsControllerTest {

    private val conv = "c0ffee00-1111-4222-8333-444455556666"
    private val zhCard = "您的请求已转交人工客服，请稍候。"
    private val zhBack = "对话已转回 AI 助手。有什么可以帮您？"
    private val zhEnded = "对话已结束。感谢您的咨询！"
    private val zhFailed = "暂时无法为您转接人工客服，请一分钟后再试。"

    /** Контекст без Android-рантайма: контроллеру нужен только applicationContext. */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun controller(handler: (HttpRequest) -> HttpResponse) = RespondoController(
        TestContext(),
        RespondoConfig(agentId = "agent-1", channelId = "chan-1"),
        RespondoIdentity(),
        ApiClient("https://api.respondo.ai", FakeHttpEngine(handler)),
        OkHttpClient(),
        InMemoryKeyValueStore(),
    )

    /** Ответ `/chat` на [chatBody], прочее — `{}`. */
    private fun chatController(chatBody: String) = controller { req ->
        if (req.url.endsWith("/api/v1/chat")) HttpResponse(200, chatBody) else HttpResponse(200, "{}")
    }

    private fun RespondoController.awaitMessage(id: String): ChatMessage = runBlocking {
        withTimeout(5_000) { messages.first { list -> list.any { it.id == id } } }.first { it.id == id }
    }

    private fun RespondoController.awaitEscalated(value: Boolean) = runBlocking {
        withTimeout(5_000) { escalated.first { it == value } }
    }

    private fun RespondoController.message(id: String): ChatMessage? = messages.value.firstOrNull { it.id == id }

    private fun RespondoController.escalatedFallback() = Strings.get(lang.value, "escalatedMessage")

    @Test
    fun `standard notice is the card itself, no second card`() {
        val noticeId = "b2b2b2b2-0000-4000-8000-000000000002"
        val c = chatController(
            """{"conversation_id":"$conv","session_token":"t","human_handover":true,"handover_card_text":"$zhCard",
               "message":{"id":"$noticeId","role":"assistant","content":"$zhCard",
               "metadata":{"handover_notice":true,"handover_canned":true}}}""",
        )
        c.sendMessage("转人工")
        c.awaitEscalated(true)
        assertEquals(zhCard, c.message(noticeId)?.content)
        assertNull("стандартное уведомление — само карточка", c.message("escalated-system"))
        assertEquals(1, c.messages.value.count { it.content == zhCard })
        c.destroy()
    }

    @Test
    fun `answer with a handover gets the card in the customer language`() {
        val answerId = "a1a1a1a1-0000-4000-8000-000000000001"
        val c = chatController(
            """{"conversation_id":"$conv","human_handover":true,"handover_card_text":"$zhCard",
               "message":{"id":"$answerId","role":"assistant","content":"关于提现问题，我为您转接人工客服。",
               "metadata":{"handover_notice":true}}}""",
        )
        c.sendMessage("我要提现")
        assertEquals(zhCard, c.awaitMessage("escalated-system").content)
        assertEquals("关于提现问题，我为您转接人工客服。", c.message(answerId)?.content)
        c.awaitEscalated(true)
        c.destroy()
    }

    @Test
    fun `humans-only handover without a notice draws the server card`() {
        val c = chatController("""{"conversation_id":"$conv","human_handover":true,"handover_card_text":"$zhCard"}""")
        c.sendMessage("你好")
        assertEquals(zhCard, c.awaitMessage("escalated-system").content)
        c.awaitEscalated(true)
        c.destroy()
    }

    @Test
    fun `echo of the customer message draws the card, not a second bubble`() {
        val echoId = "e1e1e1e1-0000-4000-8000-000000000003"
        val c = chatController(
            """{"conversation_id":"$conv","human_handover":true,"handover_card_text":"$zhCard",
               "message":{"id":"$echoId","role":"user","content":"我要提现"}}""",
        )
        c.sendMessage("我要提现")
        assertEquals(zhCard, c.awaitMessage("escalated-system").content)
        c.awaitEscalated(true)
        assertNull("эхо реплики клиента — не второй пузырь", c.message(echoId))
        c.destroy()
    }

    @Test
    fun `older backend without card text falls back to the SDK string`() {
        val c = chatController("""{"conversation_id":"$conv","human_handover":true}""")
        c.sendMessage("I need a human please")
        val card = c.awaitMessage("escalated-system")
        assertEquals(c.escalatedFallback(), card.content)
        c.destroy()
    }

    @Test
    fun `talk to a person card is the escalate message, back to AI uses remembered texts`() {
        val c = controller { req ->
            when {
                req.url.endsWith("/api/v1/chat") -> HttpResponse(
                    200,
                    """{"conversation_id":"$conv","message":{"id":"d1d1d1d1-0000-4000-8000-000000000004","role":"assistant","content":"您好"}}""",
                )
                req.url.contains("/escalate") -> HttpResponse(
                    200,
                    """{"conversation_id":"$conv","status":"escalated","message":"$zhCard",
                       "status_texts":{"back_to_ai":"$zhBack","resolved":"$zhEnded"}}""",
                )
                else -> HttpResponse(200, "{}")
            }
        }
        c.sendMessage("你好")
        c.awaitMessage("d1d1d1d1-0000-4000-8000-000000000004")
        c.escalate()
        assertEquals(zhCard, c.awaitMessage("escalate-card").content)
        c.awaitEscalated(true)
        assertEquals(ControllerLogic.StatusTexts(zhBack, zhEnded), c.rememberedStatusTexts)

        // Кадр WS status_changed языка не несёт — карточка запомненным текстом.
        c.onRealtimeEvent(RealtimeEvent.StatusChanged(conv, "bot"))
        assertEquals(zhBack, c.awaitMessage("back-to-ai-ws").content)
        c.destroy()
    }

    @Test
    fun `talk to a person refusal carries the customer text, SDK string without it`() {
        var body = """{"error":"Internal error","message":"$zhFailed"}"""
        val c = controller { req ->
            when {
                req.url.endsWith("/api/v1/chat") -> HttpResponse(
                    200,
                    """{"conversation_id":"$conv","message":{"id":"d2d2d2d2-0000-4000-8000-000000000005","role":"assistant","content":"ok"}}""",
                )
                req.url.contains("/escalate") -> HttpResponse(500, body)
                else -> HttpResponse(200, "{}")
            }
        }
        c.sendMessage("hello there")
        c.awaitMessage("d2d2d2d2-0000-4000-8000-000000000005")
        c.escalate()
        assertEquals(zhFailed, c.awaitMessage("escalate-error").content)
        c.destroy()

        body = """{"error":"Internal error"}"""
        val legacy = controller { req ->
            when {
                req.url.endsWith("/api/v1/chat") -> HttpResponse(
                    200,
                    """{"conversation_id":"$conv","message":{"id":"d3d3d3d3-0000-4000-8000-000000000006","role":"assistant","content":"ok"}}""",
                )
                req.url.contains("/escalate") -> HttpResponse(500, body)
                else -> HttpResponse(200, "{}")
            }
        }
        legacy.sendMessage("hello there")
        legacy.awaitMessage("d3d3d3d3-0000-4000-8000-000000000006")
        legacy.escalate()
        assertEquals(Strings.get(legacy.lang.value, "networkError"), legacy.awaitMessage("escalate-error").content)
        legacy.destroy()
    }

    /**
     * Поллинг шлёт тексты и сразу переход статуса двумя событиями. Каждое событие обрабатывается своей
     * корутиной, поэтому тексты запоминаются синхронно: переход всегда рисует карточку уже ими.
     */
    @Test
    fun `polled status texts are remembered before the status transition`() {
        repeat(20) {
            val c = chatController("{}")
            c.onRealtimeEvent(RealtimeEvent.StatusTexts(conv, StatusTextsDto(resolved = zhEnded)))
            c.onRealtimeEvent(RealtimeEvent.StatusChanged(conv, "resolved"))
            assertEquals(zhEnded, c.awaitMessage("resolved-card").content)
            c.destroy()
        }
    }

    @Test
    fun `without status texts the SDK string, reset forgets the remembered ones`() {
        // Каталоги опросов reset запрашивает последним шагом — к этому моменту лента и тексты уже
        // сброшены, и карточка, нарисованная после, не стирается сбросом.
        val catalogsRequested = AtomicBoolean(false)
        val c = controller { req ->
            if (req.url.contains("/widget/surveys")) catalogsRequested.set(true)
            HttpResponse(200, "{}")
        }
        c.onRealtimeEvent(RealtimeEvent.StatusTexts(conv, StatusTextsDto(backToAi = zhBack, resolved = zhEnded)))
        assertEquals(zhBack, c.rememberedStatusTexts.backToAi)

        c.reset()
        runBlocking {
            withTimeout(5_000) {
                while (!catalogsRequested.get()) delay(10)
            }
        }
        assertEquals(ControllerLogic.StatusTexts(), c.rememberedStatusTexts)
        c.onRealtimeEvent(RealtimeEvent.StatusChanged(conv, "resolved"))
        assertEquals(
            "другой пользователь — другой язык: строка SDK",
            Strings.get(c.lang.value, "resolvedMessage"),
            c.awaitMessage("resolved-card").content,
        )
        assertTrue(c.messages.value.none { it.content == zhEnded })
        c.destroy()
    }
}
