package ai.respondo.sdk.core

import ai.respondo.sdk.RespondoChatState
import ai.respondo.sdk.RespondoConfig
import ai.respondo.sdk.RespondoIdentity
import ai.respondo.sdk.RespondoListener
import ai.respondo.sdk.RespondoPushPayload
import ai.respondo.sdk.config.ConfigStore
import ai.respondo.sdk.i18n.LocaleDetect
import ai.respondo.sdk.i18n.OfficeHoursDisplay
import ai.respondo.sdk.i18n.OfficeHoursFormatter
import ai.respondo.sdk.i18n.Strings
import ai.respondo.sdk.identity.IdentityStore
import ai.respondo.sdk.internal.DeviceContext
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.SdkInfo
import ai.respondo.sdk.internal.isBackendId
import ai.respondo.sdk.internal.toJsonElement
import ai.respondo.sdk.push.PushHost
import ai.respondo.sdk.push.PushManager
import ai.respondo.sdk.push.PushRegistrationContext
import ai.respondo.sdk.realtime.RealtimeChannel
import ai.respondo.sdk.realtime.RealtimeCredentials
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.theme.ResolvedTheme
import ai.respondo.sdk.theme.ThemeMapper
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.ApiException
import ai.respondo.sdk.transport.HistoryOutcome
import ai.respondo.sdk.transport.ResumeOutcome
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.ChatIdentityDto
import ai.respondo.sdk.transport.dto.ChatRequestDto
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.DocLinkDto
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.MessageUpdateDto
import ai.respondo.sdk.transport.dto.TrackEventRequestDto
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Центральный оркестратор чат-ядра: optimistic send, каскад реалтайма, resume/история, unread, эскалация,
 * локализация, спец-сообщения, push. Публичные наблюдаемые ([messages], [chatState], [unreadCount], …) —
 * единственный источник состояния для UI и фасада. Мутации состояния сериализуются (Mutex/StateFlow),
 * колбэки [RespondoListener] вызываются на главном потоке.
 */
internal class RespondoController(
    appContext: Context,
    private val config: RespondoConfig,
    initialIdentity: RespondoIdentity?,
    private val apiClient: ApiClient,
    okHttpClient: OkHttpClient,
    private val store: KeyValueStore,
) {
    private val app: Context = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val identityStore = IdentityStore(store)
    private val conversationCache = ConversationCache(store)
    private val configStore = ConfigStore(store)
    private val device = DeviceContext.from(app)

    private val realtimeChannel = RealtimeChannel(
        agentId = config.agentId,
        apiClient = apiClient,
        okHttpClient = okHttpClient,
        credentialsProvider = ::realtimeCredentials,
        onEvent = ::onRealtimeEvent,
    )

    private val pushManager = PushManager(apiClient, PushHostImpl(), scope)

    /** Engagement-слой (news/surveys/banners/checklists/proactive). UI и фасад читают его наблюдаемые. */
    internal val engagement = EngagementController(apiClient, EngagementHostImpl(), scope)

    // --- наблюдаемое состояние ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _chatState = MutableStateFlow(RespondoChatState.CLOSED)
    val chatState: StateFlow<RespondoChatState> = _chatState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMapper.resolve(null, config.themeOverride))
    val theme: StateFlow<ResolvedTheme> = _theme.asStateFlow()

    private val _lang = MutableStateFlow(Strings.normalize(config.locale))
    val lang: StateFlow<String> = _lang.asStateFlow()

    private val _officeHours = MutableStateFlow<OfficeHoursDisplay?>(null)
    val officeHours: StateFlow<OfficeHoursDisplay?> = _officeHours.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _typing = MutableStateFlow<TypingState?>(null)
    val typing: StateFlow<TypingState?> = _typing.asStateFlow()

    private val _escalated = MutableStateFlow(false)
    val escalated: StateFlow<Boolean> = _escalated.asStateFlow()

    private val _agentHasReplied = MutableStateFlow(false)
    val agentHasReplied: StateFlow<Boolean> = _agentHasReplied.asStateFlow()

    private val _suggestedQuestions = MutableStateFlow<List<String>>(emptyList())
    val suggestedQuestions: StateFlow<List<String>> = _suggestedQuestions.asStateFlow()

    private val _docLinks = MutableStateFlow<Map<String, List<DocLinkDto>>>(emptyMap())
    val docLinks: StateFlow<Map<String, List<DocLinkDto>>> = _docLinks.asStateFlow()

    private val _surface = MutableStateFlow(ChatSurface.CHAT)
    val surface: StateFlow<ChatSurface> = _surface.asStateFlow()

    private val _emailCollectorRequired = MutableStateFlow(false)
    val emailCollectorRequired: StateFlow<Boolean> = _emailCollectorRequired.asStateFlow()

    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<ChatAttachmentDto>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachmentDto>> = _pendingAttachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    private val _messageError = MutableStateFlow<String?>(null)
    val messageError: StateFlow<String?> = _messageError.asStateFlow()

    // --- рабочее состояние ---
    @Volatile private var conversationId: String? = null
    @Volatile private var sessionToken: String? = null

    /**
     * Беседа, на которую мы смотрим, ЗАКРЫТА (resolved/archived).
     *
     * Это флаг, а не забвение: id и токен остаются, потому что именно от этой строки бэкенд форкает
     * follow-up — и на следующем сообщении, и на нажатии «нужен человек». Обоснование целиком —
     * в [ControllerLogic] («граница сессии»). Отвечает флаг ровно за одно: на закрытой строке
     * слушать нечего, поэтому реалтайм от неё отцепляется.
     */
    @Volatile internal var conversationClosed: Boolean = false
        private set
    @Volatile private var historyConversationId: String? = null
    @Volatile private var oldestMessageId: String? = null
    @Volatile private var lastBackendMsgId: String? = null
    @Volatile private var configLoaded = false
    @Volatile private var listener: RespondoListener? = null

    private val seenMessageIds = java.util.Collections.synchronizedSet(HashSet<String>())
    // Адоптированные беседы campaign_conversation — для дедупа бейджа по кадрам без тела сообщения.
    private val adoptedCampaigns = java.util.Collections.synchronizedSet(HashSet<String>())
    private val messagesMutex = Mutex()
    private val unreadTracker = UnreadTracker()

    private var typingResetJob: Job? = null
    private var readJob: Job? = null
    private var resolvedResetJob: Job? = null

    init {
        initialIdentity?.let { identityStore.setIdentity(it) }
    }

    // ==================== жизненный цикл ====================

    /** Синхронная часть init + запуск асинхронной загрузки. Возвращается быстро, UI работает на дефолтах/кэше. */
    fun start() {
        if (config.agentId.isBlank()) {
            RespondoLog.e("init: не задан непустой agentId — SDK в no-op-состоянии")
            return
        }
        identityStore.visitorId() // гарантируем создание анонимного визитёра
        val initialLang = LocaleDetect.detect(
            storedLang = identityStore.lang,
            firstUserText = null,
            systemLocale = config.locale ?: systemLocale(),
        )
        setLang(initialLang, persist = false)

        // Мгновенно применяем кэш конфига и беседы.
        configStore.load(config.agentId, config.channelId, currentLang())?.let { applyConfig(it) }
        restoreCachedConversation()

        realtimeChannel.connect()

        // Проактив → императивный колбэк host-приложения.
        scope.launch {
            engagement.proactive.collect { pm -> if (pm != null) notifyMain { it.onProactiveMessage(pm) } }
        }

        scope.launch { loadRemoteConfigAndResume() }
    }

    private fun restoreCachedConversation() {
        val cached = conversationCache.load(config.agentId, config.channelId) ?: run {
            scope.launch { ensureGreeting() }
            return
        }
        conversationId = cached.conversationId
        sessionToken = cached.sessionToken
        setEscalated(cached.escalated)
        val restored = cached.messages
        restored.forEach { it.id.takeIf { id -> id.isBackendId() }?.let { id -> seenMessageIds.add(id) } }
        lastBackendMsgId = ControllerLogic.lastBackendId(restored)
        // Запись _messages сериализуем под messagesMutex — иначе гонка с входящими событиями беседы.
        scope.launch {
            messagesMutex.withLock {
                _messages.value = if (restored.isEmpty()) messagesWithGreeting(emptyList()) else restored
            }
        }
        cached.conversationId?.let { realtimeChannel.setConversation(it) }
    }

    private suspend fun loadRemoteConfigAndResume() {
        try {
            val remote = apiClient.getWidgetConfig(
                agentId = config.agentId,
                channelId = config.channelId,
                lang = currentLang(),
                visitorId = identityStore.visitorId(),
            )
            configStore.save(config.agentId, config.channelId, currentLang(), remote)
            applyConfig(remote)
        } catch (e: Exception) {
            RespondoLog.w("не удалось загрузить конфиг — работаем на дефолтах", e)
        } finally {
            configLoaded = true
        }
        resumeOnce()
        // Каталоги overlay и проактив — после того как известны конфиг и восстановленная беседа.
        engagement.loadCatalogs()
        if (_theme.value.proactiveEnabled) engagement.scheduleProactive(_theme.value.proactiveDelaySeconds)
    }

    private fun applyConfig(dto: WidgetConfigDto) {
        _theme.value = ThemeMapper.resolve(dto, config.themeOverride)
        _officeHours.value = OfficeHoursFormatter.format(dto.officeHours, currentLang())
        // visitor_language с бэкенда переключает локаль и персистится.
        dto.visitorLanguage?.takeIf { Strings.normalize(it) != currentLang() }?.let { setLang(it, persist = true) }
        // Обновить приветственный пузырь, если тред пуст (под messagesMutex, поэтому на скоупе).
        scope.launch { refreshGreetingContent() }
    }

    private suspend fun resumeOnce() {
        val hasToken = conversationId != null && sessionToken != null
        val id = identityStore.currentIdentity()
        val hasIdentityPath = id.userHash != null
        if (!hasToken && !hasIdentityPath) return

        val outcome = apiClient.resume(
            conversationId = conversationId,
            auth = identityStore.authParams(sessionToken),
            agentId = config.agentId.ifEmpty { null },
            channelId = config.channelId,
            email = id.email,
            userId = id.userId,
        )
        when (outcome) {
            is ResumeOutcome.Restored -> applyResume(outcome.data)
            ResumeOutcome.Empty, ResumeOutcome.Forbidden, ResumeOutcome.Failed -> {
                // Локальный кэш НЕ стираем (fail-closed по доступу).
            }
        }
    }

    /**
     * Переехать в беседу, которую назвал сервер, и снова считать сессию живой.
     *
     * Решение целиком лежит в [ControllerLogic.adoptSession] — там же и обоснование, почему
     * пустые поля ответа не имеют права затирать уже имеющиеся хэндлы.
     */
    private fun adoptSession(responseId: String?, responseToken: String?) {
        val next = ControllerLogic.adoptSession(
            ControllerLogic.SessionHandles(conversationId, sessionToken),
            responseId,
            responseToken,
        )
        sessionToken = next.sessionToken
        val switched = next.conversationId != conversationId
        if (switched) {
            // Новая беседа — отменяем отложенный сброс после resolved, чтобы он не осиротил свежую.
            resolvedResetJob?.cancel()
            conversationId = next.conversationId
            historyConversationId = next.conversationId
        }
        if (conversationClosed || switched) {
            conversationClosed = false
            next.conversationId?.let { realtimeChannel.setConversation(it) }
        }
    }

    private suspend fun applyResume(data: ai.respondo.sdk.transport.dto.ResumeResponseDto) {
        historyConversationId = data.historyConversationId ?: data.conversationId
        oldestMessageId = data.oldestMessageId
        _hasMoreHistory.value = data.hasMore
        // id приходит при ЛЮБОМ статусе, включая закрытый: сервер отдаёт его именно затем, чтобы
        // следующему сообщению и нажатию «нужен человек» было от чего форкаться. Статус решает
        // здесь только одно — есть ли ещё что слушать на этой строке.
        conversationId = data.conversationId
        sessionToken = data.sessionToken
        conversationClosed = ControllerLogic.isClosedStatus(data.status)
        setEscalated(data.status == "escalated")
        val restored = data.messages.map { ChatMessage.fromDto(it) }
        restored.forEach { seenMessageIds.add(it.id) }
        lastBackendMsgId = ControllerLogic.lastBackendId(restored)
        messagesMutex.withLock {
            // Сохраняем локальные неподтверждённые пузыри (SENDING/FAILED), которых нет в восстановленном треде.
            val merged = ControllerLogic.mergeResumeMessages(restored, _messages.value)
            _messages.value = if (merged.isEmpty()) messagesWithGreeting(emptyList()) else merged
        }
        // Закрытую строку не слушаем — на ней уже ничего не произойдёт.
        if (!conversationClosed) data.conversationId?.let { realtimeChannel.setConversation(it) }
        persistCache()
    }

    // ==================== публичные действия фасада ====================

    fun setListener(l: RespondoListener?) { listener = l }

    fun setCurrentScreen(name: String?) {
        device.screen = name
        // Смена экрана приложения — аналог SPA-навигации: перепланировать проактив.
        if (_theme.value.proactiveEnabled) engagement.scheduleProactive(_theme.value.proactiveDelaySeconds)
    }

    fun identify(identity: RespondoIdentity) {
        identityStore.setIdentity(identity)
        // Устройство — к контакту новой личности: токен, полученный до логина,
        // иначе навсегда оставался на анонимном контакте визитёра.
        pushManager.reRegister()
        realtimeChannel.reIdentify()
        conversationId?.let { realtimeChannel.setConversation(it) }
        // Каталоги overlay пересчитываются под известного контакта.
        engagement.loadCatalogs()
    }

    fun track(name: String, properties: Map<String, Any?>) {
        if (name.isBlank()) return
        val id = identityStore.currentIdentity()
        scope.launch {
            apiClient.track(
                TrackEventRequestDto(
                    agentId = config.agentId.ifEmpty { null },
                    channelId = config.channelId,
                    visitorId = identityStore.visitorId(),
                    email = id.email,
                    userId = id.userId,
                    userHash = id.userHash,
                    name = name.trim(),
                    properties = properties.mapValues { it.value.toJsonElement() },
                ),
            )
        }
    }

    fun open() {
        _surface.value = ChatSurface.CHAT
        setChatState(RespondoChatState.OPENING)
        realtimeChannel.panelOpen = true
        resetUnread()
        setChatState(RespondoChatState.OPEN)
        // Тап по проактив-тизеру (host вызывает open) — вносим проактивное сообщение в тред.
        engagement.consumeProactive()?.let { pm -> scope.launch { addProactiveMessage(pm.text) } }
        notifyMain { it.onChatOpened() }
        scheduleReadReceipt()
    }

    fun close() {
        setChatState(RespondoChatState.CLOSING)
        realtimeChannel.panelOpen = false
        setChatState(RespondoChatState.CLOSED)
        notifyMain { it.onChatClosed() }
    }

    fun openNews() {
        _surface.value = ChatSurface.NEWS
        setChatState(RespondoChatState.OPEN)
        realtimeChannel.panelOpen = true
        engagement.loadNews()
        notifyMain { it.onChatOpened() }
    }

    fun openChecklists() {
        _surface.value = ChatSurface.CHECKLISTS
        setChatState(RespondoChatState.OPEN)
        realtimeChannel.panelOpen = true
        engagement.loadChecklists()
        notifyMain { it.onChatOpened() }
    }

    fun setPushToken(token: String) = pushManager.setPushToken(token)
    fun clearPushToken() = pushManager.clearPushToken()

    /** Синхронно возвращает, распознан ли пуш; эффект (открытие/дедуп) — асинхронно. */
    fun handlePush(payload: RespondoPushPayload): Boolean {
        val recognized = pushManager.isRecognized(payload)
        if (recognized) scope.launch { pushManager.handlePush(payload) }
        return recognized
    }

    fun reset() {
        scope.launch {
            // 1. revoke-session по каждой хранимой беседе (best-effort).
            conversationCache.allConversationsWithToken().forEach { (cid, token) ->
                apiClient.revokeSession(cid, identityStore.authParams(token))
            }
            // 2. Стереть всё хранилище Respondo и сгенерировать нового анонима.
            identityStore.wipeAll()
            identityStore.clearIdentity()
            identityStore.regenerateVisitor()
            // 3. Обнулить рантайм-состояние.
            conversationId = null
            sessionToken = null
            conversationClosed = false
            historyConversationId = null
            oldestMessageId = null
            lastBackendMsgId = null
            seenMessageIds.clear()
            adoptedCampaigns.clear()
            resolvedResetJob?.cancel()
            setEscalated(false)
            _agentHasReplied.value = false
            _suggestedQuestions.value = emptyList()
            _emailCollectorRequired.value = false
            resetUnread()
            messagesMutex.withLock { _messages.value = messagesWithGreeting(emptyList()) }
            // 4. Сбросить engagement-состояние (каталоги, оверлеи, проактив) под новым визитёром.
            engagement.clear()
            // 5. Переустановить реалтайм под новым визитёром и перерегистрировать push.
            realtimeChannel.setConversation(null)
            realtimeChannel.reIdentify()
            pushManager.reRegister()
            engagement.loadCatalogs()
        }
    }

    fun destroy() {
        realtimeChannel.destroy()
        listener = null
        scope.cancel()
    }

    fun onBackground() = realtimeChannel.notifyBackground()
    fun onForeground() = realtimeChannel.notifyForeground()

    // ==================== отправка сообщения ====================

    /** Отправляет сообщение с optimistic UI, забирая ожидающие вложения. При email_collector-гейте без email — блокирует. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val attachments = _pendingAttachments.value
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        // Клиентский кэп: бэкенд валидирует лимит и отдаёт 400 при превышении — не отправляем.
        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            _messageError.value = Strings.get(currentLang(), "messageTooLong")
            return
        }
        if (_emailCollectorRequired.value && collectedEmail() == null) {
            // Отправка блокируется до валидного email (UI показывает поле ввода).
            return
        }
        _messageError.value = null
        _pendingAttachments.value = emptyList()
        scope.launch { performSend(trimmed, attachments) }
    }

    fun clearMessageError() { _messageError.value = null }

    /** Загружает файл-вложение (multipart) и добавляет его в очередь отправки. Проверяет лимит 20МБ и тип. */
    fun attachFile(bytes: ByteArray, filename: String, contentType: String) {
        if (bytes.size > ApiClient.MAX_UPLOAD_BYTES) {
            _attachmentError.value = Strings.get(currentLang(), "attachmentTooLarge")
            return
        }
        if (contentType !in ALLOWED_UPLOAD_TYPES && !contentType.startsWith("image/")) {
            _attachmentError.value = Strings.get(currentLang(), "attachmentUnsupported")
            return
        }
        scope.launch {
            try {
                val uploaded = apiClient.uploadFile(bytes, filename, contentType)
                _pendingAttachments.value = _pendingAttachments.value + uploaded
                _attachmentError.value = null
            } catch (e: Exception) {
                RespondoLog.w("загрузка вложения не удалась", e)
                _attachmentError.value = Strings.get(currentLang(), "networkError")
            }
        }
    }

    fun removePendingAttachment(id: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
    }

    fun clearAttachmentError() { _attachmentError.value = null }

    /** Повторная отправка ранее упавшего сообщения. */
    fun retry(localId: String) {
        scope.launch {
            val failed = _messages.value.firstOrNull { it.id == localId && it.sendStatus == SendStatus.FAILED }
                ?: return@launch
            updateMessage(localId) { it.copy(sendStatus = SendStatus.SENDING) }
            // Отправляем исходный текст (для файловых сообщений он пуст), а не displayContent с именами файлов —
            // так performSend снова выберет fileSentPlaceholder и не пошлёт имена файлов как текст.
            performSend(failed.originalText ?: failed.content, failed.attachments, existingLocalId = localId)
        }
    }

    private suspend fun performSend(
        rawText: String,
        attachments: List<ChatAttachmentDto>,
        existingLocalId: String? = null,
    ) {
        // Отложенный resolved-сброс не должен обнулить беседу под отправляемое сообщение.
        resolvedResetJob?.cancel()
        // Клиентский кэп длины (совпадает с серверной валидацией); повторяется и на пути retry.
        val text = rawText.take(MAX_MESSAGE_LENGTH)
        val displayContent = when {
            text.isNotEmpty() -> text
            attachments.isNotEmpty() -> attachments.joinToString(", ") { it.filename }
            else -> ""
        }
        val apiMessage = if (text.isEmpty() && attachments.isNotEmpty()) {
            Strings.get(currentLang(), "fileSentPlaceholder")
        } else {
            text
        }

        val localId = existingLocalId ?: ai.respondo.sdk.internal.Ids.newLocalMessageId()
        if (existingLocalId == null) {
            val optimistic = ChatMessage(
                id = localId,
                role = MessageRole.USER,
                content = displayContent,
                attachments = attachments,
                sendStatus = SendStatus.SENDING,
                isLocal = true,
                // Храним исходный текст пользователя отдельно от displayContent (для файлов это имена файлов),
                // чтобы retry воспроизвёл ту же apiMessage-логику (пустой текст + вложения → fileSentPlaceholder).
                originalText = text,
            )
            messagesMutex.withLock { _messages.value = _messages.value + optimistic }
        }
        _suggestedQuestions.value = emptyList()
        _isLoading.value = true
        // Переопределяем локаль по тексту пользователя (как веб).
        LocaleDetect.detectFromText(text)?.let { setLang(it, persist = true) }

        val request = ChatRequestDto(
            agentId = config.agentId,
            channelId = config.channelId,
            conversationId = conversationId,
            message = apiMessage.ifEmpty { " " },
            userEmail = collectedEmail() ?: identityStore.currentIdentity().email,
            source = SdkInfo.SOURCE,
            attachments = attachments.ifEmpty { null },
            identity = buildIdentityDto(),
            sessionToken = sessionToken,
        )

        try {
            val response = apiClient.postChat(request)
            updateMessage(localId) { it.copy(sendStatus = SendStatus.SENT) }
            applyChatResponse(response)
        } catch (e: ApiException) {
            if (e.code == 410) {
                // Беседа исчезла на бэкенде (resolved/expired). Сбрасываем привязку — следующая отправка
                // (в т. ч. retry) форкнет новую беседу вместо повторных 410.
                conversationId = null
                sessionToken = null
                conversationClosed = false
                realtimeChannel.setConversation(null)
            }
            RespondoLog.w("отправка не удалась (HTTP ${e.code})", e)
            updateMessage(localId) { it.copy(sendStatus = SendStatus.FAILED) }
        } catch (e: IOException) {
            RespondoLog.w("отправка не удалась", e)
            updateMessage(localId) { it.copy(sendStatus = SendStatus.FAILED) }
        } finally {
            _isLoading.value = false
            persistCache()
        }
    }

    private suspend fun applyChatResponse(response: ChatResponseDto) {
        // Другой id означает, что закрытая строка форкнулась: с этого момента беседа пользователя —
        // новая, и остаться на старой значило бы форкать её снова каждым сообщением.
        adoptSession(response.conversationId, response.sessionToken)

        val msg = response.message
        when {
            response.humanHandover && _escalated.value -> {
                // Уже escalated — держим состояние.
            }
            response.humanHandover && msg != null && ChatMessage.normalizeRole(msg.role) != MessageRole.USER -> {
                addBackendMessage(msg)
                addSystemMessage(Strings.get(currentLang(), "escalatedMessage"), "escalated-system")
                setEscalated(true)
            }
            msg != null -> {
                addBackendMessage(msg)
                // email_collector-гейт: если email неизвестен — требуем ввод перед следующей отправкой.
                if (msg.metadata?.replyType == "email_collector" && collectedEmail() == null &&
                    identityStore.currentIdentity().email == null
                ) {
                    _emailCollectorRequired.value = true
                }
                if (response.docLinks.isNotEmpty()) {
                    _docLinks.value = _docLinks.value + (msg.id to response.docLinks)
                }
            }
        }

        _suggestedQuestions.value = if (_theme.value.suggestedQuestionsEnabled) {
            response.suggestedQuestions.take(3)
        } else {
            emptyList()
        }
    }

    // ==================== эскалация ====================

    /**
     * «Нужен человек». Работает и на ЗАКРЫТОЙ беседе — в этом весь смысл того, что хэндлы
     * переживают закрытие: [cid] и есть строка, ОТ которой бэкенд форкает follow-up.
     */
    fun escalate() {
        val cid = conversationId ?: return
        scope.launch {
            try {
                val result = apiClient.escalate(cid, identityStore.authParams(sessionToken))
                // ХЭНДЛЫ НОВОЙ СТРОКИ ПОДХВАТЫВАЮТСЯ. Из ответа читался только `message`, и клиент
                // оставался на закрытой беседе: оператор получал кейс без единого сообщения, его
                // ответ до пользователя не доходил, а следующее сообщение форкало третью строку,
                // выбивая вторую из цепочки.
                adoptSession(result.conversationId, result.sessionToken)
                setEscalated(true)
                _suggestedQuestions.value = emptyList()
                val text = result.message ?: Strings.get(currentLang(), "escalatedMessage")
                // Карточка передачи оператору — без ссылок. Тикет-трекер это
                // наша внутренняя кухня, клиенту она не показывается.
                addSystemMessage(text, "escalate-card")
            } catch (e: IOException) {
                addSystemMessage(Strings.get(currentLang(), "networkError"), "escalate-error")
            } finally {
                persistCache()
            }
        }
    }

    fun continueWithAi() {
        val cid = conversationId ?: return
        scope.launch {
            runCatching { apiClient.continueWithAi(cid, identityStore.authParams(sessionToken)) }
            // Переход разрешается локально даже при ошибке запроса.
            setEscalated(false)
            _agentHasReplied.value = false
            addSystemMessage(Strings.get(currentLang(), "backToAI"), "back-to-ai-local")
            persistCache()
        }
    }

    // ==================== история ====================

    fun loadMoreHistory() {
        if (_loadingHistory.value || !_hasMoreHistory.value) return
        val cid = historyConversationId ?: conversationId ?: return
        val before = oldestMessageId ?: return
        scope.launch {
            _loadingHistory.value = true
            when (val outcome = apiClient.history(cid, before, identityStore.authParams(sessionToken))) {
                is HistoryOutcome.Page -> {
                    val page = outcome.data.messages.map { ChatMessage.fromDto(it) }
                        .filter { seenMessageIds.add(it.id) }
                    if (page.isEmpty()) {
                        _hasMoreHistory.value = false
                    } else {
                        messagesMutex.withLock { _messages.value = page + _messages.value }
                        oldestMessageId = outcome.data.oldestMessageId ?: page.firstOrNull()?.id
                        _hasMoreHistory.value = outcome.data.hasMore
                    }
                }
                HistoryOutcome.Stop -> _hasMoreHistory.value = false
                HistoryOutcome.Failed -> Unit
            }
            _loadingHistory.value = false
        }
    }

    // ==================== email collector ====================

    fun submitCollectedEmail(email: String) {
        if (!EmailValidator.isValid(email)) return
        identityStore.collectedEmail = email
        _emailCollectorRequired.value = false
    }

    private fun collectedEmail(): String? = identityStore.collectedEmail

    // ==================== реалтайм ====================

    private fun realtimeCredentials(): RealtimeCredentials {
        val id = identityStore.currentIdentity()
        return RealtimeCredentials(
            visitorId = identityStore.visitorId(),
            channelId = config.channelId,
            sessionToken = sessionToken,
            userHash = id.userHash,
            email = id.email,
            userId = id.userId,
            lang = currentLang(),
            conversationId = conversationId,
            lastBackendMsgId = lastBackendMsgId,
        )
    }

    private fun onRealtimeEvent(event: RealtimeEvent) {
        scope.launch { handleRealtimeEvent(event) }
    }

    private suspend fun handleRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.NewMessage -> onNewMessageEvent(event.message)
            is RealtimeEvent.MessageUpdated -> onMessageUpdated(event.update)
            is RealtimeEvent.StatusChanged -> onStatusChanged(event.newStatus)
            is RealtimeEvent.Typing -> onTyping(event.isTyping, event.authorName)
            is RealtimeEvent.ConversationUpdated -> event.lang?.let { setLang(it, persist = true) }
            is RealtimeEvent.CampaignConversation -> onCampaignConversation(event.conversationId, event.message)
            is RealtimeEvent.Subscribed -> RespondoLog.d("subscribed ${event.conversationId}")
            is RealtimeEvent.Error -> onRealtimeError(event.message)
            is RealtimeEvent.OverlayShow -> engagement.applyOverlayItems(event.items)
            is RealtimeEvent.Ignored -> Unit // tooltip.catalog / tour.catalog / неизвестный type
        }
    }

    private suspend fun onNewMessageEvent(dto: MessageDto) {
        if (dto.isEchoedUserRole()) return // роль user/visitor уже отрисована оптимистично
        if (!seenMessageIds.add(dto.id)) return // дедуп
        val message = ChatMessage.fromDto(dto)
        if (dto.id.isBackendId()) lastBackendMsgId = dto.id
        if (message.authorName != null) _agentHasReplied.value = true
        clearTyping()
        _isLoading.value = false
        messagesMutex.withLock { _messages.value = _messages.value + message }
        if (_chatState.value != RespondoChatState.OPEN) {
            setUnread(unreadTracker.onIncomingWhileClosed(dto.id))
        } else {
            scheduleReadReceipt()
        }
        persistCache()
    }

    private suspend fun onMessageUpdated(update: MessageUpdateDto) {
        // Частичный объект: обновляем только статус доставки, контент сообщения не трогаем.
        updateMessage(update.id) {
            it.copy(deliveryStatus = update.deliveryStatus ?: it.deliveryStatus)
        }
    }

    private suspend fun onStatusChanged(newStatus: String) {
        when (newStatus) {
            "escalated" -> setEscalated(true)
            "agent", "bot" -> {
                if (_escalated.value) {
                    setEscalated(false)
                    addSystemMessage(Strings.get(currentLang(), "backToAI"), "back-to-ai-ws")
                }
            }
            "resolved" -> {
                addSystemMessage(Strings.get(currentLang(), "resolvedMessage"), "resolved-card")
                realtimeChannel.reIdentify() // ре-identify для пост-resolution CSAT
                scheduleResolvedReset()
            }
        }
    }

    /**
     * Беседу закрыли. Реалтайм отпускаем, ХЭНДЛЫ ОСТАВЛЯЕМ — см. [conversationClosed].
     *
     * Здесь стояло `conversationId = null; sessionToken = null`, и это ломало обе развилки сразу:
     * следующее сообщение уходило без id закрытой строки и рождало осиротевший корень, а
     * `escalate()` выходил на первой же строке (`conversationId ?: return`) — то есть кнопка
     * «нужен человек» через три секунды после закрытия молча переставала работать.
     */
    private fun scheduleResolvedReset() {
        resolvedResetJob?.cancel()
        resolvedResetJob = scope.launch {
            delay(RESOLVED_RESET_MS)
            conversationClosed = true
            setEscalated(false)
            _suggestedQuestions.value = emptyList()
            realtimeChannel.setConversation(null)
            persistCache()
        }
    }

    private fun onTyping(isTyping: Boolean, authorName: String?) {
        // Кадр is_typing=false обязан гасить индикатор, а не поднимать «печатает» с пустым автором.
        if (!isTyping) {
            clearTyping()
            return
        }
        _typing.value = ControllerLogic.typingState(isTyping = true, authorName = authorName)
        typingResetJob?.cancel()
        typingResetJob = scope.launch {
            delay(TYPING_RESET_MS)
            _typing.value = null
        }
    }

    private fun clearTyping() {
        typingResetJob?.cancel()
        _typing.value = null
    }

    private suspend fun onCampaignConversation(cid: String, dto: MessageDto?) {
        if (conversationId == null) {
            // Адоптируем беседу кампании — отменяем отложенный resolved-сброс, чтобы он её не осиротил.
            resolvedResetJob?.cancel()
            conversationId = cid
            realtimeChannel.setConversation(cid)
        }
        if (dto != null) {
            // Есть тело сообщения — unread учитывается внутри onNewMessageEvent (дедуп по id кадра).
            onNewMessageEvent(dto)
            return
        }
        // Кампания-парковка без сообщения: бейдж поднимаем один раз на беседу (дедуп повторного кадра).
        val alreadyAdopted = !adoptedCampaigns.add(cid)
        if (ControllerLogic.campaignBadgeShouldBump(
                hasDto = false,
                chatOpen = _chatState.value == RespondoChatState.OPEN,
                alreadyAdopted = alreadyAdopted,
            )
        ) {
            setUnread(unreadTracker.bumpBadge())
        }
    }

    private fun onRealtimeError(message: String) {
        // forbidden → не долбить subscribe в цикле; ждём свежих credentials из следующего /chat или resume.
        RespondoLog.w("realtime error: $message")
    }

    // ==================== read-receipt ====================

    private fun scheduleReadReceipt() {
        if (_chatState.value != RespondoChatState.OPEN || conversationId == null) return
        readJob?.cancel()
        readJob = scope.launch {
            delay(READ_DEBOUNCE_MS)
            realtimeChannel.sendRead()
        }
    }

    // ==================== вспомогательное ====================

    private suspend fun addBackendMessage(dto: MessageDto) {
        if (!seenMessageIds.add(dto.id)) return
        val message = ChatMessage.fromDto(dto)
        if (dto.id.isBackendId()) lastBackendMsgId = dto.id
        if (message.authorName != null) _agentHasReplied.value = true
        messagesMutex.withLock { _messages.value = _messages.value + message }
    }

    private suspend fun addSystemMessage(text: String, id: String) {
        if (!seenMessageIds.add(id)) return
        val message = ChatMessage(id = id, role = MessageRole.SYSTEM, content = text, isLocal = true)
        messagesMutex.withLock { _messages.value = _messages.value + message }
    }

    /** Вносит проактивное сообщение в тред как реплику ассистента (при открытии по тизеру). */
    private suspend fun addProactiveMessage(text: String) {
        val theme = _theme.value
        val message = ChatMessage(
            id = "proactive-teaser",
            role = MessageRole.ASSISTANT,
            content = text,
            authorName = theme.agentName,
            authorAvatarUrl = theme.avatarUrl,
            isLocal = true,
        )
        if (!seenMessageIds.add(message.id)) return
        messagesMutex.withLock {
            // Проактив заменяет одиночный приветственный пузырь, если тред ещё пуст.
            val current = _messages.value.filterNot { it.id == GREETING_ID }
            _messages.value = current + message
        }
    }

    private suspend fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        messagesMutex.withLock {
            _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
        }
    }

    private suspend fun ensureGreeting() {
        messagesMutex.withLock { _messages.value = messagesWithGreeting(_messages.value) }
    }

    private suspend fun refreshGreetingContent() {
        messagesMutex.withLock {
            val current = _messages.value
            if ((current.size == 1 && current.first().id == GREETING_ID) || current.isEmpty()) {
                _messages.value = messagesWithGreeting(emptyList())
            }
        }
    }

    private fun messagesWithGreeting(base: List<ChatMessage>): List<ChatMessage> {
        val theme = _theme.value
        if (!theme.greetingEnabled || base.isNotEmpty()) return base
        return listOf(
            ChatMessage(
                id = GREETING_ID,
                role = MessageRole.ASSISTANT,
                content = theme.greeting,
                authorName = theme.agentName,
                authorAvatarUrl = theme.avatarUrl,
                isLocal = true,
            ),
        )
    }

    private fun buildIdentityDto(): ChatIdentityDto {
        val id = identityStore.currentIdentity()
        val merged = LinkedHashMap<String, String>()
        merged.putAll(device.autoMetadata())
        merged.putAll(id.metadata) // host-значения выигрывают
        return ChatIdentityDto(
            email = id.email,
            name = id.name,
            userId = id.userId,
            userHash = id.userHash,
            visitorId = identityStore.visitorId(),
            metadata = merged,
            properties = id.properties,
        )
    }

    private fun persistCache() {
        val toCache = _messages.value.filter { it.id != GREETING_ID && !(it.isLocal && it.sendStatus == SendStatus.FAILED) }
        conversationCache.save(
            config.agentId,
            config.channelId,
            CachedConversation(
                conversationId = conversationId,
                sessionToken = sessionToken,
                escalated = _escalated.value,
                messages = toCache,
            ),
        )
    }

    private fun setChatState(state: RespondoChatState) { _chatState.value = state }

    /**
     * Меняет флаг эскалации И прокидывает его в реалтайм-канал, чтобы интервал REST-поллинга
     * переключался на ускоренный (2с) при живом операторе. Без этой связки [RealtimeChannel.escalated]
     * оставался бы всегда false и ускоренный интервал был бы мёртв.
     */
    private fun setEscalated(value: Boolean) {
        _escalated.value = value
        realtimeChannel.escalated = value
    }

    private fun resetUnread() {
        unreadTracker.reset()
        setUnread(0)
    }

    private fun setUnread(value: Int) {
        _unreadCount.value = value
        notifyMain { it.onUnreadChanged(value) }
    }

    private fun setLang(newLang: String, persist: Boolean) {
        val normalized = Strings.normalize(newLang)
        device.locale = normalized
        _lang.value = normalized
        if (persist) identityStore.lang = normalized
    }

    private fun currentLang(): String = _lang.value

    private fun systemLocale(): String =
        app.resources.configuration.locales[0].language

    private fun notifyMain(block: (RespondoListener) -> Unit) {
        val l = listener ?: return
        scope.launch(Dispatchers.Main) { runCatching { block(l) } }
    }

    /** Вызывается UI при тапе по ссылке. true → host сам открыл; false → SDK откроет внешним браузером. */
    fun onUrlRequested(url: String): Boolean = listener?.onUrlRequested(url) ?: false

    // ==================== мост для пушей ====================

    private inner class PushHostImpl : PushHost {
        override fun hasSeenMessage(messageId: String): Boolean = seenMessageIds.contains(messageId)
        override fun markMessageSeen(messageId: String) { seenMessageIds.add(messageId) }
        override fun isConversationForeground(conversationId: String?): Boolean =
            _chatState.value == RespondoChatState.OPEN && conversationId == this@RespondoController.conversationId

        override fun openConversation(conversationId: String): Boolean {
            this@RespondoController.conversationId = conversationId
            realtimeChannel.setConversation(conversationId)
            open()
            scope.launch { resumeOnce() }
            return true
        }

        override fun openDeepLink(link: String): Boolean = DeepLinkOpener.open(app, link)

        override fun onUnhandledDeepLink(payload: RespondoPushPayload) {
            notifyMain { it.onUnhandledDeepLink(payload) }
        }

        override fun registrationContext(): PushRegistrationContext {
            val id = identityStore.currentIdentity()
            return PushRegistrationContext(
                agentId = config.agentId.ifEmpty { null },
                channelId = config.channelId,
                appId = app.packageName,
                locale = currentLang(),
                visitorId = identityStore.visitorId(),
                email = id.email,
                userId = id.userId,
                userHash = id.userHash,
                sessionToken = sessionToken,
            )
        }
    }

    // ==================== мост для engagement ====================

    private inner class EngagementHostImpl : EngagementHost {
        override fun engagementParams(): EngagementParams {
            val id = identityStore.currentIdentity()
            return EngagementParams(
                agentId = config.agentId.ifEmpty { null },
                channelId = config.channelId,
                visitorId = identityStore.visitorId(),
                email = id.email,
                userId = id.userId,
                userHash = id.userHash,
            )
        }

        override fun engagementVisitorId(): String = identityStore.visitorId()
        override fun engagementLang(): String = currentLang()
        override fun engagementScreen(): String? = device.screen
        override fun isChatOpen(): Boolean = _chatState.value == RespondoChatState.OPEN
        override fun requestOpenUrl(url: String): Boolean = onUrlRequested(url)
        override fun openUrlExternally(url: String) = ExternalOpener.open(app, url)
    }

    companion object {
        const val GREETING_ID = "greeting"
        const val TYPING_RESET_MS = 5_000L
        const val READ_DEBOUNCE_MS = 400L
        const val RESOLVED_RESET_MS = 3_000L

        /** Клиентский лимит длины сообщения; совпадает с серверной валидацией (400 при превышении). */
        const val MAX_MESSAGE_LENGTH = 750

        /** Разрешённые MIME-типы вложений (плюс любые image-типы), как в `/chat/upload`. */
        val ALLOWED_UPLOAD_TYPES = setOf(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }
}
