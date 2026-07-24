package ai.respondo.sdk

import ai.respondo.sdk.realtime.RealtimeAction
import ai.respondo.sdk.realtime.RealtimeStateMachine
import ai.respondo.sdk.realtime.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переходы каскада WS→SSE→поллинг. */
class RealtimeStateMachineTest {

    @Test
    fun start_connectsWs() {
        val sm = RealtimeStateMachine()
        val actions = sm.start()
        assertEquals(listOf(RealtimeAction.ConnectWs), actions)
        assertEquals(Transport.WS, sm.transport)
    }

    @Test
    fun wsOpen_stopsLowerTransports() {
        val sm = RealtimeStateMachine()
        sm.start()
        val actions = sm.onWsOpen()
        assertTrue(actions.contains(RealtimeAction.StopSse))
        assertTrue(actions.contains(RealtimeAction.StopPolling))
        assertTrue(sm.wsOpen)
    }

    @Test
    fun repeatedWsFailure_escalatesToSse() {
        val sm = RealtimeStateMachine(wsQuickRetries = 2)
        sm.start()
        assertEquals(listOf(RealtimeAction.ScheduleWsReconnect), sm.onWsClosed()) // 1
        assertEquals(listOf(RealtimeAction.ScheduleWsReconnect), sm.onWsClosed()) // 2
        val third = sm.onWsClosed() // > quickRetries → эскалация
        assertTrue(third.contains(RealtimeAction.StartSse))
        assertEquals(Transport.SSE, sm.transport)
    }

    @Test
    fun sseUnavailable_fallsToPolling() {
        val sm = RealtimeStateMachine(wsQuickRetries = 0)
        sm.start()
        sm.onWsClosed() // сразу эскалация на SSE (quickRetries=0)
        val actions = sm.onSseUnavailable()
        assertTrue(actions.contains(RealtimeAction.StartPolling))
        assertEquals(Transport.POLLING, sm.transport)
    }

    @Test
    fun sseTimeout_retriesThenPolling() {
        val sm = RealtimeStateMachine(wsQuickRetries = 0, sseRetries = 1)
        sm.start()
        sm.onWsClosed()
        assertEquals(listOf(RealtimeAction.StartSse), sm.onSseTimeout()) // первый timeout → переустановка
        val second = sm.onSseTimeout() // второй → поллинг
        assertTrue(second.contains(RealtimeAction.StartPolling))
        assertEquals(Transport.POLLING, sm.transport)
    }

    @Test
    fun wsRecovers_returnsFromPolling() {
        val sm = RealtimeStateMachine(wsQuickRetries = 0)
        sm.start()
        sm.onWsClosed()
        sm.onSseUnavailable()
        assertEquals(Transport.POLLING, sm.transport)
        val actions = sm.onWsOpen()
        assertEquals(Transport.WS, sm.transport)
        assertTrue(actions.contains(RealtimeAction.StopPolling))
    }
}
