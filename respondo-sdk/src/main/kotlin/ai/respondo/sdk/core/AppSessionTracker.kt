package ai.respondo.sdk.core

/**
 * Правило сессий приложения — формулировка Intercom: «if an app is backgrounded for more than 20 seconds
 * and then resumed we treat that as a new session», открытия ближе 20 секунд не считаются. Решает, слать
 * ли `POST /widget/app-session`. События — переходы ПРОЦЕССА между фоном и передним планом
 * ([ProcessForegroundWatcher], `ProcessLifecycleOwner`), а не `Respondo.init`: процесс, поднятый в фоне
 * (FCM data-пуш, WorkManager, ресивер), сессию не открывает.
 *
 * 1. [processStarted] — переход процесса на передний план (ON_START). Первый за процесс — [launched]:
 *    запуск приложения; true, если с прошлого отчёта прошло не меньше [minGapMs] (метка общая на процесс,
 *    [shared]: смена контакта прямо перед открытием вторую сессию не заводит). Следующие — [cameForeground].
 * 2. [wentBackground] — процесс ушёл в фон (ON_STOP): запоминается ПЕРВЫЙ момент ухода эпизода фона
 *    (повторный сигнал фона до возврата его не сдвигает — время в фоне не «молодеет»).
 *    [cameForeground] — true, только если уход замечен и процесс пробыл в фоне не меньше [minGapMs];
 *    метка фона снимается в любом случае, следующий эпизод начинается с нового ухода.
 *
 * Запуск считается один раз за процесс ([foregroundSeen] на общем [shared]; наблюдатель процесса
 * ставится один раз): повторный `Respondo.init` запуском не считается.
 * 3. [contactChanged] — `identify()` сменил контакт на другого известного (userId/email): сессия
 *    достаётся новому контакту сразу, без порога.
 *
 * `init` и `reset()` сессию не заводят. Сервер повторяет 20-секундный порог по своей строке приложения.
 * Чистая логика без Android: часы инъектируются ([clock] — монотонные миллисекунды).
 */
internal class AppSessionTracker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private var lastReportAt: Long? = null
    private var backgroundAt: Long? = null
    private var foregroundSeen = false
    /** Процесс на переднем плане (между ON_START и ON_STOP). */
    private var inForeground = false
    /** Запуск вернул разобранный контроллер ([launchDropped]); забирает его [takeDroppedLaunch]. */
    private var dropped = false

    /** Запуск процесса уже решён (первый ON_START с каналом был): следующий [processStarted] — не запуск. */
    val launchSeen: Boolean
        @Synchronized get() = foregroundSeen

    /**
     * Процесс перешёл на передний план (ON_START). Первый переход за процесс — запуск ([launched]),
     * следующие — возврат из фона ([cameForeground]). true — слать сессию.
     *
     * [canReport] false — отметка не уйдёт (у конфига нет `channelId`, контроллера нет после `destroy`):
     * момент ухода отбрасывается (передний план разрешает его всегда), но запуск и отчёт НЕ считаются
     * сделанными — SDK с каналом сообщит запуск на следующем переходе процесса.
     */
    @Synchronized
    fun processStarted(canReport: Boolean = true): Boolean {
        inForeground = true
        if (!canReport) {
            backgroundAt = null
            return false
        }
        if (!foregroundSeen) {
            foregroundSeen = true
            dropped = false
            return launched()
        }
        return cameForeground()
    }

    /** Открытие приложения (первый передний план процесса). true — слать сессию. */
    @Synchronized
    fun launched(): Boolean {
        backgroundAt = null
        val now = clock()
        val last = lastReportAt
        if (last != null && now - last < minGapMs) return false
        lastReportAt = now
        return true
    }

    /**
     * Процесс ушёл в фон (ON_STOP): запоминается первый момент ухода эпизода; повторный сигнал фона до
     * возврата его не сдвигает.
     */
    @Synchronized
    fun wentBackground() {
        if (backgroundAt == null) backgroundAt = clock()
        inForeground = false
    }

    /** Процесс вернулся на передний план. true — в фоне пробыл не меньше [minGapMs], слать сессию. */
    @Synchronized
    fun cameForeground(): Boolean {
        val since = backgroundAt ?: return false
        backgroundAt = null
        val now = clock()
        if (now - since < minGapMs) return false
        lastReportAt = now
        return true
    }

    /**
     * Контроллер разобран (`destroy`, повторный `init`), пока отметка запуска ждала ответа identify: она не
     * ушла, запуск снова не сообщён. Его сообщает следующий контроллер сразу, если процесс на переднем плане
     * ([takeDroppedLaunch]), иначе — следующий ON_START. Метка последнего отчёта снимается: отметка запуска
     * не ушла (сервер держит свой порог по строке приложения — лишняя не посчитается).
     */
    @Synchronized
    fun launchDropped() {
        foregroundSeen = false
        lastReportAt = null
        dropped = true
    }

    /** true — запуск вернули ([launchDropped]) и процесс на переднем плане: новый контроллер сообщает его сейчас. */
    @Synchronized
    fun takeDroppedLaunch(): Boolean {
        val take = dropped && inForeground
        dropped = false
        return take
    }

    /**
     * `identify()` сменил контакт на другого известного. true — слать сессию сразу, без порога, но только
     * если процесс на переднем плане: identify из фонового старта (FCM-обработчик, WorkManager) — не
     * открытие приложения; сессию под новой личностью даст первый ON_START.
     */
    @Synchronized
    fun contactChanged(): Boolean {
        if (!inForeground) return false
        lastReportAt = clock()
        return true
    }

    companion object {
        /** Порог Intercom: время в фоне, после которого возврат — новая сессия (20 секунд). */
        const val MIN_GAP_MS: Long = 20_000

        /** Общий на процесс трекер: жизненный цикл процесса один, переживает re-init SDK. */
        val shared = AppSessionTracker()
    }
}

/**
 * Дедуп `POST /widget/identify` — как `lastIdentifySent` веб-виджета: тот же закодированный кузов
 * второй раз не шлётся; неудачная отправка сбрасывает память, и следующий `identify()` повторит её.
 */
internal class IdentifySync {
    private var lastSent: String? = null

    /** true — тело новое, слать (и запомнить); false — такое уже отправлено. */
    @Synchronized
    fun claim(body: String): Boolean {
        if (body == lastSent) return false
        lastSent = body
        return true
    }

    /** Отправка [body] не удалась — забыть его, чтобы повтор прошёл. */
    @Synchronized
    fun failed(body: String) {
        if (lastSent == body) lastSent = null
    }

    companion object {
        /** Общий на процесс (как модульная переменная веб-виджета). */
        val shared = IdentifySync()
    }
}
