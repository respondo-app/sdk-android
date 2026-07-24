package ai.respondo.sdk.core

import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.AuthParams
import ai.respondo.sdk.transport.dto.BannerResponseRequestDto
import ai.respondo.sdk.transport.dto.ChecklistProgressRequestDto
import ai.respondo.sdk.transport.dto.SubmitAnswerRequestDto
import ai.respondo.sdk.transport.dto.SubmitSurveyRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Параметры контакта для engagement-запросов (query-часть публичных ручек). */
internal data class EngagementParams(
    val agentId: String?,
    val channelId: String?,
    val visitorId: String?,
    val email: String?,
    val userId: String?,
    val userHash: String?,
)

/**
 * Мост между [EngagementController] и [RespondoController]: параметры контакта, текущий экран,
 * состояние панели и открытие URL. Реализуется движком.
 */
internal interface EngagementHost {
    fun engagementParams(): EngagementParams
    fun engagementVisitorId(): String
    fun engagementLang(): String
    fun engagementScreen(): String?
    fun isChatOpen(): Boolean
    fun requestOpenUrl(url: String): Boolean
    /** Открыть http(s)-URL внешним приложением (браузером) — если host-листенер ссылку не забрал. */
    fun openUrlExternally(url: String)
}

/** Конвертация публичного ответа опроса в [JsonElement] тела запроса. */
internal fun RespondoSurveyAnswer.toJsonElement(): JsonElement = when (this) {
    is RespondoSurveyAnswer.Text -> JsonPrimitive(value)
    is RespondoSurveyAnswer.Choice -> JsonPrimitive(value)
    is RespondoSurveyAnswer.Number -> JsonPrimitive(value)
    is RespondoSurveyAnswer.MultiChoice -> buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}

/**
 * Ядро engagement-слоя: каталоги (news/surveys/banners/checklists), арбитр оверлеев, проактив,
 * отправка ответов/прогресса. Наблюдаемые ([news], [checklists], [activeOverlay], …) потребляет UI;
 * host-приложение читает [banners]/[newsUnread]/[proactive]. Все мутации внутреннего состояния
 * сериализуются под [stateLock]; сеть выполняется на [scope].
 */
internal class EngagementController(
    private val apiClient: ApiClient,
    private val host: EngagementHost,
    private val scope: CoroutineScope,
) {
    // --- наблюдаемое состояние (UI) ---
    private val _news = MutableStateFlow<List<RespondoNewsItem>>(emptyList())
    val news: StateFlow<List<RespondoNewsItem>> = _news.asStateFlow()

    private val _newsUnread = MutableStateFlow(0)
    val newsUnread: StateFlow<Int> = _newsUnread.asStateFlow()

    private val _newsLoading = MutableStateFlow(false)
    val newsLoading: StateFlow<Boolean> = _newsLoading.asStateFlow()

    private val _checklists = MutableStateFlow<List<RespondoChecklist>>(emptyList())
    val checklists: StateFlow<List<RespondoChecklist>> = _checklists.asStateFlow()

    private val _checklistsLoading = MutableStateFlow(false)
    val checklistsLoading: StateFlow<Boolean> = _checklistsLoading.asStateFlow()

    private val _activeOverlay = MutableStateFlow<OverlayDecision>(OverlayDecision.None)
    val activeOverlay: StateFlow<OverlayDecision> = _activeOverlay.asStateFlow()

    private val _surveyStepIndex = MutableStateFlow(0)
    val surveyStepIndex: StateFlow<Int> = _surveyStepIndex.asStateFlow()

    private val _surveyFinished = MutableStateFlow(false)
    val surveyFinished: StateFlow<Boolean> = _surveyFinished.asStateFlow()

    private val _surveySubmitting = MutableStateFlow(false)
    val surveySubmitting: StateFlow<Boolean> = _surveySubmitting.asStateFlow()

    private val _surveyAnswers = MutableStateFlow<Map<String, RespondoSurveyAnswer>>(emptyMap())
    val surveyAnswers: StateFlow<Map<String, RespondoSurveyAnswer>> = _surveyAnswers.asStateFlow()

    /** Видимые (не закрытые) баннеры — для host-приложения. */
    private val _banners = MutableStateFlow<List<RespondoBanner>>(emptyList())
    val banners: StateFlow<List<RespondoBanner>> = _banners.asStateFlow()

    /** Актуальное проактивное сообщение под текущий экран (null — нет/потреблено). */
    private val _proactive = MutableStateFlow<RespondoProactiveMessage?>(null)
    val proactive: StateFlow<RespondoProactiveMessage?> = _proactive.asStateFlow()

    // --- внутреннее состояние (под stateLock) ---
    private val stateLock = Any()
    private var surveys: List<RespondoSurvey> = emptyList()
    private var bannerList: List<RespondoBanner> = emptyList()
    private val dismissed = HashSet<String>()
    private val startedChecklists = HashSet<String>()
    private var lightboxOpen = false
    private var composerHasText = false
    private val dismissedProactiveScreens = HashSet<String>()
    private var pendingProactive: RespondoProactiveMessage? = null
    private var proactiveJob: Job? = null

    // ==================== каталоги overlay (surveys + banners) ====================

    /** Грузит каталоги опросов и баннеров (на identify / overlay.show). */
    fun loadCatalogs() {
        val params = host.engagementParams()
        val auth = authParams(params)
        scope.launch {
            val surveysDto = runCatching {
                apiClient.getSurveys(params.agentId, params.channelId, null, auth, params.email, params.userId)
            }.getOrNull()
            val bannersDto = runCatching {
                apiClient.getBanners(params.agentId, params.channelId, null, auth, params.email, params.userId)
            }.getOrNull()
            synchronized(stateLock) {
                surveysDto?.let { surveys = it.surveys.map(EngagementMapper::toSurvey) }
                bannersDto?.let {
                    bannerList = it.banners.map(EngagementMapper::toBanner)
                    publishBanners()
                }
                recomputeOverlay()
            }
        }
    }

    /**
     * Обработка WS-события `overlay.show`: элементы каталога приходят пушем. Каждый item — сырой объект
     * с `content` (OutboundContent) + delivery_id/campaign_id; классифицируем по content:
     * survey_format/questions → опрос, banner_layout/banner_action → баннер.
     */
    fun applyOverlayItems(items: List<JsonObject>) {
        synchronized(stateLock) {
            val newSurveys = surveys.toMutableList()
            val newBanners = bannerList.toMutableList()
            var bannersChanged = false
            for (item in items) {
                val content = item["content"] as? JsonObject
                val isSurvey = content?.containsKey("survey_format") == true || content?.containsKey("questions") == true
                val isBanner = content?.containsKey("banner_layout") == true || content?.containsKey("banner_action") == true
                if (isSurvey) {
                    val dto = runCatching {
                        ai.respondo.sdk.internal.respondoJson.decodeFromJsonElement(
                            ai.respondo.sdk.transport.dto.SurveyCatalogItemDto.serializer(), item,
                        )
                    }.getOrNull() ?: continue
                    val survey = EngagementMapper.toSurvey(dto)
                    if (newSurveys.none { it.deliveryId == survey.deliveryId }) newSurveys.add(survey)
                } else if (isBanner) {
                    val dto = runCatching {
                        ai.respondo.sdk.internal.respondoJson.decodeFromJsonElement(
                            ai.respondo.sdk.transport.dto.BannerCatalogItemDto.serializer(), item,
                        )
                    }.getOrNull() ?: continue
                    val banner = EngagementMapper.toBanner(dto)
                    if (newBanners.none { it.deliveryId == banner.deliveryId }) {
                        newBanners.add(banner)
                        bannersChanged = true
                    }
                }
            }
            surveys = newSurveys
            bannerList = newBanners
            if (bannersChanged) publishBanners()
            recomputeOverlay()
        }
    }

    // ==================== News ====================

    fun loadNews() {
        _newsLoading.value = true
        val params = host.engagementParams()
        val auth = authParams(params)
        scope.launch {
            val response = runCatching {
                apiClient.getNews(params.agentId, null, auth, params.email, params.userId)
            }.getOrNull()
            _newsLoading.value = false
            response ?: return@launch
            val items = response.items.map(EngagementMapper::toNews)
            _news.value = items
            setNewsUnread(if (response.unread != 0) response.unread else items.count { !it.seen })
        }
    }

    fun markNewsSeen(id: String) {
        val current = _news.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0 || current[index].seen) return
        _news.value = current.mapIndexed { i, item -> if (i == index) item.copy(seen = true) else item }
        setNewsUnread(maxOf(0, _newsUnread.value - 1))
        val params = host.engagementParams()
        val auth = authParams(params)
        scope.launch {
            apiClient.markNewsSeen(id, params.agentId, auth, params.email, params.userId)
        }
    }

    // ==================== Checklists ====================

    fun loadChecklists() {
        _checklistsLoading.value = true
        val params = host.engagementParams()
        val auth = authParams(params)
        scope.launch {
            val response = runCatching {
                apiClient.getChecklists(params.channelId, params.agentId, auth, params.email, params.userId)
            }.getOrNull()
            _checklistsLoading.value = false
            response ?: return@launch
            val lists = response.checklists.map(EngagementMapper::toChecklist)
            _checklists.value = lists
            lists.forEach { markChecklistStarted(it.id) }
        }
    }

    /** Идемпотентно шлёт `started` при первом показе чек-листа. */
    private fun markChecklistStarted(id: String) {
        val isNew = synchronized(stateLock) { startedChecklists.add(id) }
        if (isNew) sendChecklistProgress(id, "started", null)
    }

    /**
     * Тап по задаче чек-листа. url → делегат [EngagementHost.requestOpenUrl] + отметка выполнения;
     * manual → переключение чекбокса; tour — скрыт (no-op).
     */
    fun performTask(checklistId: String, taskId: String) {
        val checklist = _checklists.value.firstOrNull { it.id == checklistId } ?: return
        val task = checklist.tasks.firstOrNull { it.id == taskId } ?: return
        when (val action = task.action) {
            is RespondoChecklistAction.Url -> {
                openEngagementUrl(action.url)
                setTaskDone(checklistId, taskId, done = true)
            }
            is RespondoChecklistAction.Manual -> {
                val isDone = checklist.doneTaskIds.contains(taskId)
                setTaskDone(checklistId, taskId, done = !isDone)
            }
            is RespondoChecklistAction.Tour -> Unit
        }
    }

    private fun setTaskDone(checklistId: String, taskId: String, done: Boolean) {
        _checklists.value = _checklists.value.map { checklist ->
            if (checklist.id != checklistId) return@map checklist
            val next = checklist.doneTaskIds.toMutableSet()
            if (done) next.add(taskId) else next.remove(taskId)
            checklist.copy(doneTaskIds = next)
        }
        sendChecklistProgress(checklistId, if (done) "task_done" else "task_undone", taskId)
    }

    fun dismissChecklist(id: String) {
        _checklists.value = _checklists.value.filterNot { it.id == id }
        sendChecklistProgress(id, "dismissed", null)
    }

    private fun sendChecklistProgress(id: String, event: String, taskId: String?) {
        val params = host.engagementParams()
        scope.launch {
            apiClient.checklistProgress(
                id,
                ChecklistProgressRequestDto(
                    agentId = params.agentId,
                    channelId = params.channelId,
                    visitorId = params.visitorId,
                    email = params.email,
                    userId = params.userId,
                    userHash = params.userHash,
                    event = event,
                    taskId = taskId,
                ),
            )
        }
    }

    // ==================== Survey overlay flow ====================

    /** Текущий опрос-оверлей (если арбитр выбрал опрос). */
    fun activeSurvey(): RespondoSurvey? = (_activeOverlay.value as? OverlayDecision.Survey)?.survey

    /** Вопросы текущего шага опроса. */
    fun currentStepQuestions(survey: RespondoSurvey): List<RespondoQuestion> {
        val index = _surveyStepIndex.value
        if (index >= survey.steps.size) return emptyList()
        val ids = survey.steps[index]
        return ids.mapNotNull { id -> survey.questions.firstOrNull { it.id == id } }
    }

    /** Записывает ответ на вопрос (локально + `POST /survey/answer`). */
    fun answerQuestion(survey: RespondoSurvey, questionId: String, answer: RespondoSurveyAnswer) {
        _surveyAnswers.value = _surveyAnswers.value + (questionId to answer)
        val visitorId = host.engagementVisitorId()
        scope.launch {
            apiClient.surveyAnswer(
                SubmitAnswerRequestDto(
                    deliveryId = survey.deliveryId,
                    visitorId = visitorId,
                    questionId = questionId,
                    value = answer.toJsonElement(),
                ),
            )
        }
    }

    fun answeredValue(questionId: String): RespondoSurveyAnswer? = _surveyAnswers.value[questionId]

    /** Все ли обязательные вопросы текущего шага отвечены. */
    fun canAdvance(survey: RespondoSurvey): Boolean =
        currentStepQuestions(survey).all { !it.required || _surveyAnswers.value.containsKey(it.id) }

    /** Переход к следующему шагу или завершение опроса. */
    fun advanceSurvey(survey: RespondoSurvey) {
        if (!canAdvance(survey)) return
        if (_surveyStepIndex.value + 1 < survey.steps.size) {
            _surveyStepIndex.value = _surveyStepIndex.value + 1
        } else {
            finishSurvey(survey)
        }
    }

    private fun finishSurvey(survey: RespondoSurvey) {
        _surveyFinished.value = true
        _surveySubmitting.value = true
        val visitorId = host.engagementVisitorId()
        val answers = _surveyAnswers.value.mapValues { it.value.toJsonElement() }
        scope.launch {
            apiClient.surveySubmit(SubmitSurveyRequestDto(survey.deliveryId, visitorId, answers))
            _surveySubmitting.value = false
        }
    }

    /** Закрыть текущий опрос (крестик или после благодарности). */
    fun dismissSurvey(deliveryId: String) {
        synchronized(stateLock) {
            dismissed.add(deliveryId)
            resetSurveyProgress()
            recomputeOverlay()
        }
    }

    private fun resetSurveyProgress() {
        _surveyStepIndex.value = 0
        _surveyFinished.value = false
        _surveySubmitting.value = false
        _surveyAnswers.value = emptyMap()
    }

    // ==================== Banner ====================

    fun bannerReaction(banner: RespondoBanner, emoji: String) {
        sendBannerResponse(banner, "reaction", emoji)
        if (banner.dismissAfterAction) dismissBanner(banner.deliveryId)
    }

    fun bannerEmail(banner: RespondoBanner, email: String) {
        sendBannerResponse(banner, "email", email)
        if (banner.dismissAfterAction) dismissBanner(banner.deliveryId)
    }

    /** Клик по CTA баннера (action=url). */
    fun bannerOpenUrl(banner: RespondoBanner) {
        banner.url?.let { openEngagementUrl(it) }
        if (banner.dismissAfterAction) dismissBanner(banner.deliveryId)
    }

    private fun sendBannerResponse(banner: RespondoBanner, kind: String, value: String) {
        val visitorId = host.engagementVisitorId()
        scope.launch {
            apiClient.bannerResponse(BannerResponseRequestDto(banner.deliveryId, visitorId, kind, value))
        }
    }

    fun dismissBanner(deliveryId: String) {
        synchronized(stateLock) {
            dismissed.add(deliveryId)
            publishBanners()
            recomputeOverlay()
        }
    }

    // ==================== Проактив ====================

    /** Запланировать запрос проактива под текущий экран с задержкой [delaySeconds] секунд. */
    fun scheduleProactive(delaySeconds: Int) {
        proactiveJob?.cancel()
        val params = host.engagementParams()
        if (params.agentId.isNullOrEmpty()) return
        val screen = host.engagementScreen()
        if (screen != null && synchronized(stateLock) { dismissedProactiveScreens.contains(screen) }) return
        proactiveJob = scope.launch {
            delay(maxOf(0, delaySeconds) * 1000L)
            fetchProactive()
        }
    }

    private suspend fun fetchProactive() {
        if (host.isChatOpen()) return
        val params = host.engagementParams()
        val agentId = params.agentId?.takeIf { it.isNotEmpty() } ?: return
        val screen = host.engagementScreen()
        if (screen != null && synchronized(stateLock) { dismissedProactiveScreens.contains(screen) }) return
        val dto = runCatching {
            apiClient.getProactive(
                agentId = agentId,
                pageTitle = screen,
                pagePath = screen,
                pageDescription = null,
                lang = host.engagementLang(),
            )
        }.getOrNull() ?: return
        val message = dto.message?.takeIf { it.isNotEmpty() } ?: return
        val proactive = RespondoProactiveMessage(text = message, pagePath = dto.pagePath ?: screen)
        synchronized(stateLock) { pendingProactive = proactive }
        _proactive.value = proactive
    }

    /** Забрать проактив для показа в треде (и очистить). */
    fun consumeProactive(): RespondoProactiveMessage? {
        val message = synchronized(stateLock) {
            val m = pendingProactive
            pendingProactive = null
            m
        }
        if (message != null) _proactive.value = null
        return message
    }

    fun dismissProactive() {
        synchronized(stateLock) {
            host.engagementScreen()?.let { dismissedProactiveScreens.add(it) }
            pendingProactive = null
        }
        _proactive.value = null
    }

    // ==================== подавление оверлеев ====================

    fun setLightboxOpen(open: Boolean) {
        synchronized(stateLock) {
            if (lightboxOpen == open) return
            lightboxOpen = open
            recomputeOverlay()
        }
    }

    fun setComposerHasText(hasText: Boolean) {
        synchronized(stateLock) {
            if (composerHasText == hasText) return
            composerHasText = hasText
            recomputeOverlay()
        }
    }

    // ==================== служебное ====================

    /** Должно вызываться под [stateLock]. */
    private fun publishBanners() {
        _banners.value = bannerList.filter { !dismissed.contains(it.deliveryId) }
    }

    /** Пересчитывает выбранный оверлей и сбрасывает прогресс при смене опроса. Под [stateLock]. */
    private fun recomputeOverlay() {
        val previous = _activeOverlay.value
        val decision = OverlayArbiter.decide(
            OverlayArbiterInput(
                surveys = surveys,
                banners = bannerList,
                dismissed = dismissed,
                lightboxOpen = lightboxOpen,
                composerHasText = composerHasText,
            ),
        )
        val sameSurvey = decision is OverlayDecision.Survey && previous is OverlayDecision.Survey &&
            decision.survey.deliveryId == previous.survey.deliveryId
        if (!sameSurvey && decision != previous) resetSurveyProgress()
        _activeOverlay.value = decision
    }

    private fun setNewsUnread(value: Int) {
        _newsUnread.value = value
    }

    /**
     * Маршрутизация engagement-ссылки: сперва предлагаем host-листенеру (deep link в приложение),
     * иначе открываем внешним приложением только безопасную http(s)-схему. Симметрично ссылкам чата.
     */
    private fun openEngagementUrl(url: String) {
        when (ControllerLogic.resolveEngagementUrlPath(host.requestOpenUrl(url), UrlSafety.isHttp(url))) {
            ControllerLogic.UrlOpenPath.HANDLED_BY_HOST -> Unit
            ControllerLogic.UrlOpenPath.OPEN_EXTERNAL -> host.openUrlExternally(url)
            ControllerLogic.UrlOpenPath.REJECTED_SCHEME ->
                RespondoLog.w("engagement: небезопасная схема URL отклонена: $url")
        }
    }

    fun clear() {
        proactiveJob?.cancel()
        synchronized(stateLock) {
            surveys = emptyList()
            bannerList = emptyList()
            dismissed.clear()
            startedChecklists.clear()
            dismissedProactiveScreens.clear()
            pendingProactive = null
        }
        _news.value = emptyList()
        _checklists.value = emptyList()
        _banners.value = emptyList()
        setNewsUnread(0)
        _activeOverlay.value = OverlayDecision.None
        _proactive.value = null
        resetSurveyProgress()
    }

    private fun authParams(params: EngagementParams): AuthParams =
        AuthParams(sessionToken = null, userHash = params.userHash, visitorId = params.visitorId)
}
