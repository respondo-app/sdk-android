package ai.respondo.sdk.realtime

import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.AuthParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** Снимок актуальных реквизитов для WS/SSE/поллинга. Поставляется контроллером на каждый запрос. */
data class RealtimeCredentials(
    val visitorId: String,
    val channelId: String?,
    val sessionToken: String?,
    val userHash: String?,
    val email: String?,
    val userId: String?,
    val lang: String?,
    val conversationId: String?,
    val lastBackendMsgId: String?,
)

/**
 * Сетевой канал реалтайма: поднимает WebSocket, при недоступности — SSE, затем REST-поллинг, следуя решениям
 * [RealtimeStateMachine]. Все переходы сериализуются на выделенном одно-поточном скоупе; события беседы
 * отдаются через [onEvent] (фильтрация ролей/дедуп — в контроллере).
 *
 * Reconnect WS — экспоненциальный backoff 3с→30с со сбросом при успехе (behavior.md §11). Pong на Ping бэкенда OkHttp шлёт автоматически, удерживая
 * read-deadline 60с. Порядок кадров при открытии WS строго **identify → subscribe**.
 */
class RealtimeChannel(
    private val agentId: String,
    private val apiClient: ApiClient,
    private val okHttpClient: OkHttpClient,
    private val credentialsProvider: () -> RealtimeCredentials,
    private val onEvent: (RealtimeEvent) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val sm = RealtimeStateMachine()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var eventSource: EventSource? = null
    @Volatile private var subscribedConversationId: String? = null

    @Volatile var panelOpen: Boolean = false
    @Volatile var escalated: Boolean = false

    private var reconnectJob: Job? = null
    private var pollingJob: Job? = null

    private val wsUrl: String = apiClient.wsBaseUrl + "/api/v1/chat/ws?agent_id=" + agentId

    /** Запускает каскад (обычно при init). Идемпотентно к повторному вызову. */
    fun connect() {
        scope.launch { apply(sm.start()) }
    }

    /** Обновляет целевую беседу: при живом WS сразу шлёт subscribe; при активном SSE — переподнимает поток. */
    fun setConversation(conversationId: String?) {
        scope.launch {
            if (conversationId != null && conversationId != subscribedConversationId) {
                subscribedConversationId = null
                sendSubscribeIfPossible(conversationId)
                // SSE-поток привязан к conversation в URL — при смене беседы его надо переоткрыть.
                // Гейтим по транспорту, а НЕ по наличию eventSource: если поток был остановлен ранее
                // (resolved → setConversation(null) → stopSse), а транспорт остался SSE, то без этого
                // реалтайм новой беседы был бы мёртв. startSse() сам гасит прежний поток перед стартом.
                if (shouldReopenSse(sm.transport)) startSse()
            } else if (conversationId == null) {
                subscribedConversationId = null
                if (eventSource != null) stopSse()
            }
        }
    }

    /** Повторно шлёт identify (смена личности/локали). Пересинхронизирует contact-scoped подписки. */
    fun reIdentify() {
        scope.launch { sendIdentify() }
    }

    /** Отсылает read-receipt (дебаунс — на стороне контроллера). */
    fun sendRead() {
        scope.launch {
            val creds = credentialsProvider()
            val cid = creds.conversationId ?: return@launch
            val frame = ReadFrame(cid, creds.sessionToken, creds.userHash, creds.visitorId)
            webSocket?.send(respondoJson.encodeToString(ReadFrame.serializer(), frame))
        }
    }

    /** Приложение ушло в фон — отпускаем WS, чтобы бэкенд мог доставлять push. */
    fun notifyBackground() {
        scope.launch {
            webSocket?.send(respondoJson.encodeToString(BackgroundFrame.serializer(), BackgroundFrame()))
        }
    }

    /** Приложение вернулось — восстанавливаем WS (заново identify→subscribe). */
    fun notifyForeground() {
        scope.launch {
            webSocket?.send(respondoJson.encodeToString(ForegroundFrame.serializer(), ForegroundFrame()))
            if (!sm.wsOpen) apply(sm.onWsReconnectTick())
        }
    }

    fun destroy() {
        // Синхронный teardown ДО отмены скоупа. Раньше очистка шла через scope.launch, а следом
        // scope.cancel() убивал корутину до её выполнения → WS/SSE-сокет и polling-корутина утекали.
        reconnectJob?.cancel()
        closeWs()
        stopSse()
        stopPolling()
        scope.cancel()
    }

    // --- применение действий стейт-машины ---

    private fun apply(actions: List<RealtimeAction>) {
        for (action in actions) {
            when (action) {
                RealtimeAction.ConnectWs -> openWs()
                RealtimeAction.ScheduleWsReconnect -> scheduleReconnect()
                RealtimeAction.StartSse -> startSse()
                RealtimeAction.StopSse -> stopSse()
                RealtimeAction.StartPolling -> startPolling()
                RealtimeAction.StopPolling -> stopPolling()
                RealtimeAction.CloseWs -> closeWs()
            }
        }
    }

    private fun openWs() {
        closeWs()
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    private fun closeWs() {
        webSocket?.cancel()
        webSocket = null
        subscribedConversationId = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = sm.nextReconnectDelayMs()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!sm.wsOpen) apply(sm.onWsReconnectTick())
        }
    }

    private fun startSse() {
        val creds = credentialsProvider()
        val cid = creds.conversationId ?: return // SSE — поток беседы; без беседы поднимать нечего
        stopSse()
        val url = apiClient.streamUrl(cid, AuthParams(creds.sessionToken, creds.userHash, creds.visitorId))
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        eventSource = EventSources.createFactory(okHttpClient).newEventSource(request, sseListener)
    }

    private fun stopSse() {
        eventSource?.cancel()
        eventSource = null
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            delay(FIRST_POLL_MS)
            while (isActive) {
                val creds = credentialsProvider()
                val cid = creds.conversationId
                if (cid != null) {
                    val auth = AuthParams(creds.sessionToken, creds.userHash, creds.visitorId)
                    val resp = apiClient.getMessages(cid, creds.lastBackendMsgId, auth)
                    resp?.messages?.forEach { onEvent(RealtimeEvent.NewMessage(cid, it)) }
                    resp?.status?.let { onEvent(RealtimeEvent.StatusChanged(cid, it)) }
                }
                delay(pollingInterval())
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // internal (не private) для регрессионного теста интервалов поллинга.
    internal fun pollingInterval(): Long = when {
        !panelOpen -> BACKGROUND_POLL_MS
        escalated -> ESCALATED_POLL_MS
        else -> OPEN_POLL_MS
    }

    private fun sendSubscribeIfPossible(conversationId: String) {
        val ws = webSocket ?: return
        val creds = credentialsProvider()
        val frame = SubscribeFrame(conversationId, creds.sessionToken, creds.userHash, creds.visitorId)
        ws.send(respondoJson.encodeToString(SubscribeFrame.serializer(), frame))
        subscribedConversationId = conversationId
    }

    private fun sendIdentify() {
        val ws = webSocket ?: return
        val creds = credentialsProvider()
        val frame = IdentifyFrame(
            visitorId = creds.visitorId,
            email = creds.email,
            userId = creds.userId,
            channelId = creds.channelId,
            sessionToken = creds.sessionToken,
            userHash = creds.userHash,
            lang = creds.lang,
            conversationId = creds.conversationId,
        )
        ws.send(respondoJson.encodeToString(IdentifyFrame.serializer(), frame))
    }

    // --- слушатели ---

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                apply(sm.onWsOpen())
                // Строгий порядок: сначала identify, затем subscribe для текущей беседы.
                sendIdentify()
                credentialsProvider().conversationId?.let { sendSubscribeIfPossible(it) }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            WsParser.parse(text)?.let { event ->
                if (event is RealtimeEvent.Subscribed) subscribedConversationId = event.conversationId
                onEvent(event)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { if (webSocket === this@RealtimeChannel.webSocket) apply(sm.onWsClosed()) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            RespondoLog.w("WS onFailure: ${t.message}")
            scope.launch { if (webSocket === this@RealtimeChannel.webSocket) apply(sm.onWsClosed()) }
        }
    }

    private val sseListener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            scope.launch { apply(sm.onSseOpen()) }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (type == "timeout") {
                scope.launch { apply(sm.onSseTimeout()) }
                return
            }
            WsParser.parse(data)?.let { onEvent(it) }
        }

        override fun onClosed(eventSource: EventSource) {
            scope.launch { apply(sm.onSseTimeout()) }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            RespondoLog.w("SSE onFailure: code=${response?.code} ${t?.message}")
            scope.launch { apply(sm.onSseUnavailable()) }
        }
    }

    companion object {
        const val FIRST_POLL_MS = 500L
        const val OPEN_POLL_MS = 3_000L
        const val ESCALATED_POLL_MS = 2_000L
        const val BACKGROUND_POLL_MS = 10_000L
    }
}
