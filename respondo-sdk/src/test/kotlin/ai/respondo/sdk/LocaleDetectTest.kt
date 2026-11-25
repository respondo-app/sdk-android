package ai.respondo.sdk

import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.i18n.LocaleDetect
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpResponse
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Решение 2026-11-02: язык беседы решает сервер; SDK язык по тексту посетителя не
 * определяет. Стартовая локаль — сохранённый язык беседы, иначе локаль приложения/системы.
 */
class LocaleDetectTest {

    @Test
    fun `start locale is the stored server language, then the system`() {
        assertEquals("ru", LocaleDetect.detect(storedLang = "ru-RU", systemLocale = "en"))
        assertEquals("de", LocaleDetect.detect(storedLang = null, systemLocale = "de-DE"))
        assertEquals("en", LocaleDetect.detect(storedLang = "", systemLocale = "xx"))
    }

    /** Контекст без Android-рантайма: контроллеру нужен только applicationContext. */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `controller keeps the language the server gave`() {
        val chats = AtomicInteger(0)
        val c = RespondoController(
            TestContext(),
            RespondoConfig(agentId = "agent-1", channelId = "chan-1"),
            RespondoIdentity(),
            ApiClient(
                "https://api.respondo.ai",
                FakeHttpEngine { req ->
                    if (req.url.endsWith("/api/v1/chat")) {
                        // Первый ход — сервер называет язык беседы; второй — старый бэкенд без поля.
                        if (chats.incrementAndGet() == 1) {
                            HttpResponse(200, """{"conversation_id":"c1","human_handover":false,"lang":"uk"}""")
                        } else {
                            HttpResponse(200, """{"conversation_id":"c1","human_handover":false}""")
                        }
                    } else {
                        HttpResponse(200, "{}")
                    }
                },
            ),
            OkHttpClient(),
            InMemoryKeyValueStore(),
        )
        c.sendMessage("Добрый день")
        awaitTurn(c, chats, 1)
        assertEquals("uk", c.lang.value)
        // Старый бэкенд без поля lang — язык беседы остаётся тем, что дал сервер.
        c.sendMessage("Добрый вечер, всё работает?")
        awaitTurn(c, chats, 2)
        assertEquals("uk", c.lang.value)
        c.destroy()
    }

    private fun awaitTurn(c: RespondoController, chats: AtomicInteger, n: Int) = runBlocking {
        withTimeout(5_000) { while (chats.get() < n || c.isLoading.value) delay(10) }
    }
}
