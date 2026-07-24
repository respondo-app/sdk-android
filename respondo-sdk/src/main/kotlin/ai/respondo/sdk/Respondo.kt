package ai.respondo.sdk

import ai.respondo.sdk.core.CommandQueue
import ai.respondo.sdk.core.QueuedCommand
import ai.respondo.sdk.core.RespondoBanner
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.core.RespondoProactiveMessage
import ai.respondo.sdk.identity.AndroidKeyValueStore
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.SdkInfo
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.OkHttpEngine
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Единая точка входа в SDK — синглтон-фасад. Все методы идемпотентны к повторным вызовам и безопасны к
 * вызову из любого потока. Вызовы до завершения [init] буферизуются в command-queue и проигрываются после
 * инициализации (см. api-surface §7).
 */
object Respondo {

    private val queue = CommandQueue()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var controller: RespondoController? = null

    // HTTP-движок текущего инстанса: держим ссылку, чтобы закрыть пул/executor OkHttp при destroy/re-init.
    @Volatile
    private var engine: OkHttpEngine? = null

    private val bridgeJobs = mutableListOf<Job>()

    private val _unreadCount = MutableStateFlow(0)
    /** Число непрочитанных сообщений визитёра. Эмиссия на главном потоке. После `reset` → 0. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _chatState = MutableStateFlow(RespondoChatState.CLOSED)
    /** Состояние модалки чата. */
    val chatState: StateFlow<RespondoChatState> = _chatState.asStateFlow()

    private val _banners = MutableStateFlow<List<RespondoBanner>>(emptyList())
    /** Активные (не закрытые) баннеры для host-приложения (можно рисовать вне Respondo-экранов). */
    val banners: StateFlow<List<RespondoBanner>> = _banners.asStateFlow()

    private val _proactiveMessage = MutableStateFlow<RespondoProactiveMessage?>(null)
    /** Текущее проактивное сообщение-тизер под текущий экран (null — нет/потреблено). */
    val proactiveMessage: StateFlow<RespondoProactiveMessage?> = _proactiveMessage.asStateFlow()

    private val _newsUnreadCount = MutableStateFlow(0)
    /** Число непрочитанных новостей (для бейджа «Что нового» в host-приложении). */
    val newsUnreadCount: StateFlow<Int> = _newsUnreadCount.asStateFlow()

    /** Внутренний доступ к активному контроллеру (для Compose-UI и Fragment-обёртки). */
    internal val activeController: RespondoController? get() = controller

    // ==================== жизненный цикл ====================

    /**
     * Инициализация SDK. Обязателен первым. Повторный `init` эквивалентен `destroy` + `init`.
     * Если не задан `agentId` — логируется ошибка, SDK остаётся в no-op-состоянии.
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, config: RespondoConfig, identity: RespondoIdentity? = null) {
        if (config.agentId.isBlank()) {
            RespondoLog.e("init: обязателен непустой agentId — вызов проигнорирован")
            return
        }
        // Повторный init уничтожает старый инстанс, закрывает его HTTP-пул и очищает очередь.
        controller?.destroy()
        engine?.close()
        engine = null
        queue.clear()

        val appContext = context.applicationContext
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() } ?: SdkInfo.DEFAULT_BASE_URL
        val engine = OkHttpEngine()
        this.engine = engine
        val apiClient = ApiClient(baseUrl, engine)
        val store = AndroidKeyValueStore.create(appContext)

        val newController = RespondoController(appContext, config, identity, apiClient, engine.okHttpClient, store)
        controller = newController
        newController.start()
        bridgeObservables(newController)

        // Проигрываем очередь в порядке поступления.
        queue.drain().forEach { replay(it, newController) }
    }

    @JvmStatic
    fun identify(identity: RespondoIdentity) = dispatch(QueuedCommand.Identify(identity)) { it.identify(identity) }

    @JvmStatic
    fun reset() {
        val c = controller
        if (c == null) {
            RespondoLog.w("reset до init — нечего сбрасывать")
            return
        }
        c.reset()
    }

    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) =
        dispatch(QueuedCommand.Track(name, properties)) { it.track(name, properties) }

    @JvmStatic
    fun open() = dispatch(QueuedCommand.Open) { it.open() }

    @JvmStatic
    fun close() = dispatch(QueuedCommand.Close) { it.close() }

    @JvmStatic
    fun openNews() = dispatch(QueuedCommand.OpenNews) { it.openNews() }

    @JvmStatic
    fun openChecklists() = dispatch(QueuedCommand.OpenChecklists) { it.openChecklists() }

    /** Закрыть проактив-тизер под текущим экраном (host-приложение скрыло пузырь без открытия чата). */
    @JvmStatic
    fun dismissProactive() {
        controller?.engagement?.dismissProactive()
    }

    @JvmStatic
    fun setPushToken(token: String) = dispatch(QueuedCommand.SetPushToken(token)) { it.setPushToken(token) }

    @JvmStatic
    fun clearPushToken() = dispatch(QueuedCommand.ClearPushToken) { it.clearPushToken() }

    /**
     * Обрабатывает входящий пуш. Возвращает `true`, если это Respondo-пуш (в т. ч. до init — распознавание
     * синхронно по маркеру; фактическое открытие откладывается до конца init).
     */
    @JvmStatic
    fun handlePush(payload: RespondoPushPayload): Boolean {
        val c = controller
        return if (c != null) {
            c.handlePush(payload)
        } else {
            queue.enqueue(QueuedCommand.HandlePush(payload))
            payload.type != null || payload.conversationId != null
        }
    }

    @JvmStatic
    fun destroy() {
        // Отменяем мост наблюдаемых — иначе коллекторы висят на старом контроллере (утечка).
        bridgeJobs.forEach { it.cancel() }
        bridgeJobs.clear()
        controller?.destroy()
        controller = null
        // Закрываем HTTP-пул/executor OkHttp — иначе idle-потоки диспетчера живут после teardown.
        engine?.close()
        engine = null
        queue.clear()
    }

    // ==================== наблюдаемые/колбэки ====================

    @JvmStatic
    fun setListener(listener: RespondoListener?) {
        controller?.setListener(listener) ?: RespondoLog.w("setListener до init — слушатель будет утерян")
    }

    /** Опционально задаёт имя текущего экрана host-приложения (попадает в metadata.screen). */
    @JvmStatic
    fun setCurrentScreen(name: String?) {
        controller?.setCurrentScreen(name)
    }

    /** Сообщает SDK о переходе приложения в фон (отпустить WS, разрешить push-доставку). */
    @JvmStatic
    fun onBackground() {
        controller?.onBackground()
    }

    /** Сообщает SDK о возврате приложения на передний план. */
    @JvmStatic
    fun onForeground() {
        controller?.onForeground()
    }

    /** Включает/выключает debug-логи SDK (без изменения публичной поверхности). */
    @JvmStatic
    fun setDebugLogging(enabled: Boolean) {
        RespondoLog.debugEnabled = enabled
    }

    // ==================== внутреннее ====================

    private fun bridgeObservables(c: RespondoController) {
        bridgeJobs.forEach { it.cancel() }
        bridgeJobs.clear()
        bridgeJobs += bridgeScope.launch { c.unreadCount.collect { _unreadCount.value = it } }
        bridgeJobs += bridgeScope.launch { c.chatState.collect { _chatState.value = it } }
        bridgeJobs += bridgeScope.launch { c.engagement.banners.collect { _banners.value = it } }
        bridgeJobs += bridgeScope.launch { c.engagement.proactive.collect { _proactiveMessage.value = it } }
        bridgeJobs += bridgeScope.launch { c.engagement.newsUnread.collect { _newsUnreadCount.value = it } }
    }

    private inline fun dispatch(command: QueuedCommand, action: (RespondoController) -> Unit) {
        val c = controller
        if (c != null) action(c) else queue.enqueue(command)
    }

    private fun replay(command: QueuedCommand, c: RespondoController) {
        when (command) {
            is QueuedCommand.Identify -> c.identify(command.identity)
            is QueuedCommand.Track -> c.track(command.name, command.properties)
            QueuedCommand.Open -> c.open()
            QueuedCommand.Close -> c.close()
            QueuedCommand.OpenNews -> c.openNews()
            QueuedCommand.OpenChecklists -> c.openChecklists()
            is QueuedCommand.SetPushToken -> c.setPushToken(command.token)
            QueuedCommand.ClearPushToken -> c.clearPushToken()
            is QueuedCommand.HandlePush -> c.handlePush(command.payload)
        }
    }
}
