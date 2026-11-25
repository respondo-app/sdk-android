package ai.respondo.sdk

import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpResponse
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Решение 2026-11-01, RC4: текст, который набрал SDK за посетителя (пилюля быстрого вопроса,
 * AI-подсказка), уходит с `input_source` — сервер не выбирает по нему язык беседы. Набранный текст —
 * `typed`.
 */
class InputSourceControllerTest {

    /** Контекст без Android-рантайма: контроллеру нужен только applicationContext. */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `typed text and chips carry their input source`() {
        val bodies = CopyOnWriteArrayList<String>()
        val c = RespondoController(
            TestContext(),
            RespondoConfig(agentId = "agent-1", channelId = "chan-1"),
            RespondoIdentity(),
            ApiClient(
                "https://api.respondo.ai",
                FakeHttpEngine { req ->
                    if (req.url.endsWith("/api/v1/chat")) {
                        (req.body as? HttpBody.Json)?.let { bodies.add(it.text) }
                        HttpResponse(200, Fixtures.read("chat-response-first.json"))
                    } else {
                        HttpResponse(200, "{}")
                    }
                },
            ),
            OkHttpClient(),
            InMemoryKeyValueStore(),
        )
        c.sendMessage("hello there")
        awaitCount(bodies, 1)
        c.sendQuickQuestion("How do I start trading?")
        awaitCount(bodies, 2)
        c.sendSuggestion("How to connect an exchange?")
        awaitCount(bodies, 3)

        val source = Regex("\"input_source\":\"([a-z_]+)\"")
        assertEquals(
            listOf("typed", "quick_question", "suggestion"),
            bodies.map { source.find(it)?.groupValues?.get(1) },
        )
        c.destroy()
    }

    private fun awaitCount(list: List<String>, n: Int) = runBlocking {
        withTimeout(5_000) { while (list.size < n) delay(10) }
    }
}
