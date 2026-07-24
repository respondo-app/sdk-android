package ai.respondo.sdk.realtime

/** Текущий предпочитаемый транспорт реалтайма. */
enum class Transport { NONE, WS, SSE, POLLING }

/**
 * Нужно ли переоткрыть SSE-поток при смене беседы. Гейт по ТЕКУЩЕМУ транспорту, а не по наличию живого
 * потока: SSE привязан к conversation в URL, и если поток был остановлен ранее (resolved →
 * `setConversation(null)` → `stopSse`), то при появлении новой беседы его всё равно надо поднять заново —
 * иначе реалтайм новой беседы окажется мёртвым (WS не вернулся, поллинг не запущен). Для WS/POLLING/NONE
 * переоткрывать SSE не нужно: WS сам шлёт subscribe, поллинг читает беседу из креденшелов на каждом тике.
 */
internal fun shouldReopenSse(transport: Transport): Boolean = transport == Transport.SSE

/** Действие, которое [RealtimeStateMachine] предписывает сетевому слою. */
sealed interface RealtimeAction {
    data object ConnectWs : RealtimeAction
    data object ScheduleWsReconnect : RealtimeAction
    data object StartSse : RealtimeAction
    data object StopSse : RealtimeAction
    data object StartPolling : RealtimeAction
    data object StopPolling : RealtimeAction
    data object CloseWs : RealtimeAction
}

/**
 * Чистая стейт-машина каскада **WS (основной) → SSE (fallback) → поллинг (последний резерв)** (см. behavior.md §3).
 *
 * Ключевые инварианты:
 * - WS переустанавливается всегда (reconnect с backoff 3с→30с, см. [nextReconnectDelayMs]), даже на SSE/поллинге — при успешном [onWsOpen]
 *   нижние транспорты гасятся (гейт «WS активен», аналог `wsActiveRef` веба);
 * - переход WS→SSE происходит, только когда WS не удаётся вернуть за [wsQuickRetries] быстрых попыток;
 * - SSE→поллинг — при недоступности SSE (503/нет NATS) или повторном `event: timeout` без переустановки.
 *
 * Класс не выполняет ввод-вывод и не зависит от Android — драйвится [RealtimeChannel] и покрыт unit-тестами.
 */
class RealtimeStateMachine(
    private val wsQuickRetries: Int = 2,
    private val sseRetries: Int = 1,
) {
    var transport: Transport = Transport.NONE
        private set

    var wsOpen: Boolean = false
        private set

    private var wsFailStreak = 0
    private var sseFailStreak = 0
    private var reconnectAttempt = 0

    /** Старт каскада: пытаемся поднять WS. */
    fun start(): List<RealtimeAction> {
        transport = Transport.WS
        wsOpen = false
        wsFailStreak = 0
        sseFailStreak = 0
        reconnectAttempt = 0
        return listOf(RealtimeAction.ConnectWs)
    }

    /** WS открылся — гасим нижние транспорты, сбрасываем счётчики (в т. ч. backoff реконнекта). */
    fun onWsOpen(): List<RealtimeAction> {
        wsOpen = true
        transport = Transport.WS
        wsFailStreak = 0
        sseFailStreak = 0
        reconnectAttempt = 0
        return listOf(RealtimeAction.StopSse, RealtimeAction.StopPolling)
    }

    /**
     * Задержка следующей попытки реконнекта WS с экспоненциальным backoff (behavior.md §11): база 3с,
     * удвоение на каждую неудачу до потолка 30с; успешный [onWsOpen] сбрасывает серию. Каждый вызов
     * увеличивает счётчик попыток — вызывается ровно раз на планируемый реконнект.
     */
    fun nextReconnectDelayMs(): Long {
        val shift = reconnectAttempt.coerceAtMost(MAX_BACKOFF_SHIFT)
        reconnectAttempt++
        return (BASE_RECONNECT_MS shl shift).coerceAtMost(MAX_RECONNECT_MS)
    }

    /** WS закрылся/ошибся — планируем reconnect; после [wsQuickRetries] неудач эскалируем на SSE. */
    fun onWsClosed(): List<RealtimeAction> {
        wsOpen = false
        wsFailStreak++
        val actions = mutableListOf<RealtimeAction>(RealtimeAction.ScheduleWsReconnect)
        if (wsFailStreak > wsQuickRetries && transport == Transport.WS) {
            transport = Transport.SSE
            actions += RealtimeAction.StartSse
        }
        return actions
    }

    /** Тик таймера reconnect — снова пробуем WS (он предпочтителен всегда). */
    fun onWsReconnectTick(): List<RealtimeAction> = listOf(RealtimeAction.ConnectWs)

    /** SSE открылся. Если WS уже вернулся — SSE не нужен; иначе SSE становится активным и гасит поллинг. */
    fun onSseOpen(): List<RealtimeAction> {
        return if (wsOpen) {
            listOf(RealtimeAction.StopSse)
        } else {
            transport = Transport.SSE
            listOf(RealtimeAction.StopPolling)
        }
    }

    /** SSE недоступен (503 / нет NATS / не открылся) — падаем на поллинг. */
    fun onSseUnavailable(): List<RealtimeAction> {
        if (wsOpen) return emptyList()
        transport = Transport.POLLING
        return listOf(RealtimeAction.StopSse, RealtimeAction.StartPolling)
    }

    /** SSE закрылся по `event: timeout`. Переустанавливаем до [sseRetries] раз, затем — поллинг. */
    fun onSseTimeout(): List<RealtimeAction> {
        if (wsOpen) return listOf(RealtimeAction.StopSse)
        sseFailStreak++
        return if (sseFailStreak <= sseRetries) {
            listOf(RealtimeAction.StartSse)
        } else {
            transport = Transport.POLLING
            listOf(RealtimeAction.StopSse, RealtimeAction.StartPolling)
        }
    }

    /** Полная остановка (destroy/смена беседы). */
    fun stop(): List<RealtimeAction> {
        transport = Transport.NONE
        wsOpen = false
        return listOf(RealtimeAction.CloseWs, RealtimeAction.StopSse, RealtimeAction.StopPolling)
    }

    companion object {
        /** База backoff реконнекта WS. */
        const val BASE_RECONNECT_MS = 3_000L
        /** Потолок backoff реконнекта WS. */
        const val MAX_RECONNECT_MS = 30_000L
        // 3с<<3 = 24с < 30с, 3с<<4 = 48с → упирается в потолок; дальше сдвиг не растим.
        private const val MAX_BACKOFF_SHIFT = 4
    }
}
