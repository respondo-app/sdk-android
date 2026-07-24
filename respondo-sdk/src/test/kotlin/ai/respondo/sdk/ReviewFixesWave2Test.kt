package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.realtime.RealtimeStateMachine
import ai.respondo.sdk.realtime.Transport
import ai.respondo.sdk.realtime.shouldReopenSse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Регрессионные тесты фиксов волны ревью №2 Android SDK (SSE-редирект при смене беседы + автоскролл своей отправки). */
class ReviewFixesWave2Test {

    // --- P2-1: переоткрытие SSE при смене беседы гейтится транспортом, а не наличием потока ---

    @Test
    fun shouldReopenSse_onlyForSseTransport() {
        assertTrue(shouldReopenSse(Transport.SSE))
        assertFalse(shouldReopenSse(Transport.WS))
        assertFalse(shouldReopenSse(Transport.POLLING))
        assertFalse(shouldReopenSse(Transport.NONE))
    }

    @Test
    fun sseReopensOnNewConversation_whileSseTransportAfterResolvedNull() {
        // WS не поднимается: после серии неудач каскад эскалирует на SSE.
        val sm = RealtimeStateMachine(wsQuickRetries = 2)
        sm.start()
        sm.onWsClosed()
        sm.onWsClosed()
        sm.onWsClosed()
        assertEquals(Transport.SSE, sm.transport)
        sm.onSseOpen()
        assertEquals(Transport.SSE, sm.transport)
        // Сценарий бага: беседа завершилась (resolved → setConversation(null) → stopSse), поток остановлен,
        // но транспорт остался SSE (WS не вернулся). Появляется новая беседа. Решение переоткрыть SSE
        // не должно зависеть от того, что предыдущий поток был погашен, — гейт по транспорту его поднимает.
        assertTrue(shouldReopenSse(sm.transport))
    }

    // --- P2-2: автоскролл к собственной только что отправленной реплике даже при чтении истории выше ---

    @Test
    fun shouldAutoScroll_firstShowNearBottomOrOwnSend() {
        // Первый показ — всегда скроллим к последнему.
        assertTrue(ControllerLogic.shouldAutoScroll(firstScroll = true, nearBottom = false, lastIsOwnSend = false))
        // Пользователь у низа — скроллим.
        assertTrue(ControllerLogic.shouldAutoScroll(firstScroll = false, nearBottom = true, lastIsOwnSend = false))
        // Собственная отправка при чтении истории выше — скроллим (ключевой кейс P2-2).
        assertTrue(ControllerLogic.shouldAutoScroll(firstScroll = false, nearBottom = false, lastIsOwnSend = true))
        // Чужое сообщение, пользователь читает историю выше и не у низа — вниз НЕ дёргаем.
        assertFalse(ControllerLogic.shouldAutoScroll(firstScroll = false, nearBottom = false, lastIsOwnSend = false))
    }
}
