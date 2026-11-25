package ai.respondo.sdk

import ai.respondo.sdk.core.AppSessionTracker
import ai.respondo.sdk.core.IdentifySync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило сессий приложения (Intercom: «backgrounded for more than 20 seconds and then resumed» — новая
 * сессия; открытия ближе 20 с не считаются): первый ON_START процесса — запуск, возврат после ≥ 20 с в
 * фоне (от первого ухода эпизода фона), смена контакта в identify(). Чистая логика на инъектированных
 * часах.
 */
class AppSessionTrackerTest {

    private var now = 1_000_000L
    private fun tracker() = AppSessionTracker(clock = { now })

    @Test
    fun `launch reports, a relaunch within 20 s does not`() {
        val t = tracker()
        assertTrue(t.launched())
        now += 5_000
        assertFalse("повторное открытие в те же 20 с — та же сессия", t.launched())
        now += 15_000
        assertTrue("ровно 20 с — новая сессия", t.launched())
    }

    @Test
    fun `foreground without an observed background is not a session`() {
        val t = tracker()
        assertTrue(t.launched())
        now += 60_000
        assertFalse("первый старт активити / поворот — не возврат из фона", t.cameForeground())
    }

    @Test
    fun `background for 5 s is the same session, 25 s is a new one`() {
        val t = tracker()
        assertTrue(t.launched())
        now += 60_000
        t.wentBackground()
        now += 5_000
        assertFalse("5 с в фоне — та же сессия", t.cameForeground())
        now += 30_000
        assertFalse("фон уже израсходован прошлым возвратом", t.cameForeground())
        t.wentBackground()
        now += 25_000
        assertTrue("25 с в фоне — новая сессия", t.cameForeground())
    }

    @Test
    fun `time in background counts, not time since the last report`() {
        val t = tracker()
        assertTrue(t.launched())
        // Сразу после запуска ушли в фон на 20 с — новая сессия, хотя отчёт был 20 с назад ровно.
        t.wentBackground()
        now += 20_000
        assertTrue(t.cameForeground())
        // Долго на переднем плане, затем 3 с в фоне — та же сессия.
        now += 600_000
        t.wentBackground()
        now += 3_000
        assertFalse(t.cameForeground())
    }

    @Test
    fun `first background moment of the episode counts`() {
        val t = tracker()
        assertTrue(t.launched())
        t.wentBackground() // t0
        now += 30_000
        t.wentBackground() // t0 + 30 с — повторный сигнал, момент ухода не сдвигается
        now += 10_000
        assertTrue("от первого ухода 40 с — новая сессия", t.cameForeground())
        assertFalse("второй сигнал переднего плана — без нового фона не сессия", t.cameForeground())
        // Следующий эпизод начинается с нового ухода.
        now += 600_000
        t.wentBackground()
        now += 5_000
        assertFalse("новый эпизод: 5 с в фоне", t.cameForeground())
    }

    @Test
    fun `launch is counted once per process - later ON_START is a return, not a launch`() {
        val t = tracker()
        assertTrue("первый ON_START процесса — запуск", t.processStarted())
        // Час на переднем плане, 5 с в фоне: правило запуска дало бы сессию (отчёт давно), но второй
        // ON_START процесса — возврат из фона, и 5 с в фоне — та же сессия. Повторный init ON_START
        // не получает (наблюдатель ставится один раз), запуск не повторяется.
        now += 3_600_000
        t.wentBackground()
        now += 5_000
        assertFalse(t.processStarted())
        now += 3_600_000
        assertFalse("ON_START без ухода в фон — не запуск", t.processStarted())
    }

    @Test
    fun `process start - first is the launch, later ones need 20 s in background`() {
        val t = tracker()
        assertTrue("первый ON_START процесса — открытие приложения", t.processStarted())
        now += 60_000
        assertFalse("ON_START без ON_STOP — не сессия", t.processStarted())
        t.wentBackground()
        now += 5_000
        assertFalse("5 с в фоне", t.processStarted())
        t.wentBackground()
        now += 25_000
        assertTrue("25 с в фоне", t.processStarted())
    }

    @Test
    fun `contact switch before the first ON_START sends nothing - the launch does`() {
        val t = tracker()
        // identify() из очереди или из фонового старта (FCM) до ON_START — не открытие приложения.
        assertFalse(t.contactChanged())
        now += 1_000
        assertTrue("первый ON_START — сессия запуска под новой личностью", t.processStarted())
        // В фоне смена контакта тоже ничего не шлёт.
        t.wentBackground()
        assertFalse(t.contactChanged())
    }

    @Test
    fun `contact change always reports`() {
        val t = tracker()
        assertTrue(t.processStarted())
        now += 1_000
        assertTrue("смена контакта — без порога", t.contactChanged())
        assertTrue(t.contactChanged())
        now += 1_000
        assertFalse("перезапуск в 20 с после сессии нового контакта — та же", t.launched())
    }

    @Test
    fun `no channel - ON_START reports nothing and does not use up the launch`() {
        val t = tracker()
        assertFalse("без канала отметка не уходит", t.processStarted(canReport = false))
        now += 1_000
        assertTrue("SDK с каналом — запуск процесса ещё не сообщён", t.processStarted())
        // Момент ухода разрешается и без канала: отброшен, до следующего возврата не доживает.
        t.wentBackground()
        now += 30_000
        assertFalse(t.processStarted(canReport = false))
        assertFalse("метка фона отброшена", t.processStarted())
    }

    @Test
    fun `launch clears a stale background mark`() {
        val t = tracker()
        t.wentBackground()
        assertTrue(t.launched())
        now += 60_000
        assertFalse(t.cameForeground())
    }

    @Test
    fun `identify sync sends a body once and retries after a failure`() {
        val sync = IdentifySync()
        assertTrue(sync.claim("""{"properties":{"plan":"pro"}}"""))
        assertFalse("тот же кузов — не шлём", sync.claim("""{"properties":{"plan":"pro"}}"""))
        assertTrue("другой кузов — шлём", sync.claim("""{"properties":{"plan":"team"}}"""))
        sync.failed("""{"properties":{"plan":"team"}}""")
        assertTrue("после неудачи тот же кузов уходит снова", sync.claim("""{"properties":{"plan":"team"}}"""))
        sync.failed("""{"properties":{"plan":"other"}}""")
        assertFalse("неудача другого кузова память не трогает", sync.claim("""{"properties":{"plan":"team"}}"""))
    }

    @Test
    fun `a dropped launch is reported again by the next controller, once`() {
        val t = tracker()
        assertTrue("первый ON_START — запуск", t.processStarted())
        t.launchDropped()
        assertTrue("процесс на переднем плане — запуск забирает новый контроллер", t.takeDroppedLaunch())
        assertFalse("забирают один раз", t.takeDroppedLaunch())
        assertTrue("запуск снова не сообщён — его отметка уходит, порог 20 с не мешает", t.processStarted())
        now += 60_000
        assertFalse("дальше — обычный возврат из фона", t.processStarted())
    }

    @Test
    fun `a launch dropped in the background waits for the next ON_START`() {
        val t = tracker()
        assertTrue(t.processStarted())
        t.wentBackground()
        t.launchDropped()
        assertFalse("в фоне новый контроллер запуск не сообщает", t.takeDroppedLaunch())
        assertTrue("следующий ON_START — запуск", t.processStarted())
    }
}
