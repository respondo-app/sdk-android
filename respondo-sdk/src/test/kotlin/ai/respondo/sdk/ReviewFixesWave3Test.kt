package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.UrlSafety
import ai.respondo.sdk.realtime.RealtimeChannel
import ai.respondo.sdk.realtime.RealtimeCredentials
import ai.respondo.sdk.realtime.RealtimeStateMachine
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.OkHttpEngine
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Регрессионные тесты фиксов волны ревью №3 Android SDK. */
class ReviewFixesWave3Test {

    // --- P2-1: маршрутизация engagement-ссылок ---

    @Test
    fun resolveEngagementUrlPath_hostThenSchemeGuard() {
        // Host-листенер забрал ссылку (в т. ч. кастомная deep-link схема) → внешний Intent не запускаем.
        assertEquals(
            ControllerLogic.UrlOpenPath.HANDLED_BY_HOST,
            ControllerLogic.resolveEngagementUrlPath(hostHandled = true, isHttp = false),
        )
        // Host не забрал, схема http(s) → открываем внешним приложением.
        assertEquals(
            ControllerLogic.UrlOpenPath.OPEN_EXTERNAL,
            ControllerLogic.resolveEngagementUrlPath(hostHandled = false, isHttp = true),
        )
        // Host не забрал, схема небезопасная → отклоняем (не запускаем произвольный Intent).
        assertEquals(
            ControllerLogic.UrlOpenPath.REJECTED_SCHEME,
            ControllerLogic.resolveEngagementUrlPath(hostHandled = false, isHttp = false),
        )
    }

    // --- minor: scheme-guard http(s) ---

    @Test
    fun urlSafety_allowsOnlyHttpSchemes() {
        assertTrue(UrlSafety.isHttp("https://a.b/x"))
        assertTrue(UrlSafety.isHttp("http://a.b"))
        assertTrue(UrlSafety.isHttp("  HTTPS://A.B  "))
        assertFalse(UrlSafety.isHttp("javascript:alert(1)"))
        assertFalse(UrlSafety.isHttp("data:text/html,x"))
        assertFalse(UrlSafety.isHttp("file:///etc/passwd"))
        assertFalse(UrlSafety.isHttp("myapp://deep/link"))
        assertFalse(UrlSafety.isHttp(null))
        assertFalse(UrlSafety.isHttp(""))
    }

    // --- minor: экспоненциальный backoff реконнекта WS ---

    @Test
    fun reconnectBackoff_growsThenCapsAndResets() {
        val sm = RealtimeStateMachine()
        sm.start()
        assertEquals(RealtimeStateMachine.BASE_RECONNECT_MS, sm.nextReconnectDelayMs()) // 3с
        assertEquals(6_000L, sm.nextReconnectDelayMs())
        assertEquals(12_000L, sm.nextReconnectDelayMs())
        assertEquals(24_000L, sm.nextReconnectDelayMs())
        assertEquals(RealtimeStateMachine.MAX_RECONNECT_MS, sm.nextReconnectDelayMs()) // потолок 30с
        assertEquals(RealtimeStateMachine.MAX_RECONNECT_MS, sm.nextReconnectDelayMs()) // держим потолок
        // Успешный онлайн сбрасывает серию.
        sm.onWsOpen()
        assertEquals(RealtimeStateMachine.BASE_RECONNECT_MS, sm.nextReconnectDelayMs())
    }

    // --- P1: закрытие HTTP-движка идемпотентно и глушит executor ---

    @Test
    fun okHttpEngine_closeIsIdempotent() {
        val engine = OkHttpEngine()
        engine.close()
        engine.close() // повторный close не бросает
        assertTrue(engine.okHttpClient.dispatcher.executorService.isShutdown)
    }

    // --- P1: destroy детерминированно закрывает WS-сокет (до отмены скоупа) ---

    @Test
    fun destroy_closesWebSocketDeterministically() {
        val server = MockWebServer()
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed.countDown()
            }),
        )
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val channel = RealtimeChannel(
                agentId = "agent-1",
                apiClient = ApiClient(base, FakeHttpEngine()),
                okHttpClient = OkHttpClient(),
                credentialsProvider = {
                    RealtimeCredentials(
                        visitorId = "v_test",
                        channelId = null,
                        sessionToken = null,
                        userHash = null,
                        email = null,
                        userId = null,
                        lang = null,
                        conversationId = null,
                        lastBackendMsgId = null,
                    )
                },
                onEvent = {},
            )
            channel.connect()
            assertTrue("WS должен открыться", opened.await(5, TimeUnit.SECONDS))
            channel.destroy()
            assertTrue("сокет должен закрыться детерминированно после destroy", closed.await(5, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }
}
