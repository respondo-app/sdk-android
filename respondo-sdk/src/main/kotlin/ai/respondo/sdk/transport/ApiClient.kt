package ai.respondo.sdk.transport

import ai.respondo.sdk.core.SurveyTargeting
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.SdkInfo
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.ChatRequestDto
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.ChecklistProgressRequestDto
import ai.respondo.sdk.transport.dto.ChecklistsCatalogResponseDto
import ai.respondo.sdk.transport.dto.BannerResponseRequestDto
import ai.respondo.sdk.transport.dto.BannersCatalogResponseDto
import ai.respondo.sdk.transport.dto.ContinueResponseDto
import ai.respondo.sdk.transport.dto.EscalationResponseDto
import ai.respondo.sdk.transport.dto.HistoryResponseDto
import ai.respondo.sdk.transport.dto.NewsListResponseDto
import ai.respondo.sdk.transport.dto.OkResponseDto
import ai.respondo.sdk.transport.dto.SubmitAnswerRequestDto
import ai.respondo.sdk.transport.dto.SubmitSurveyRequestDto
import ai.respondo.sdk.transport.dto.SurveysCatalogResponseDto
import ai.respondo.sdk.transport.dto.ProactiveResponseDto
import ai.respondo.sdk.transport.dto.PushOpenedRequestDto
import ai.respondo.sdk.transport.dto.PushRegisterRequestDto
import ai.respondo.sdk.transport.dto.PushUnregisterRequestDto
import ai.respondo.sdk.transport.dto.ResumeResponseDto
import ai.respondo.sdk.transport.dto.RevokeResponseDto
import ai.respondo.sdk.transport.dto.TrackEventRequestDto
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import ai.respondo.sdk.transport.dto.WidgetMessagesResponseDto
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import java.io.IOException

/** Параметры доказательства владения беседой (query-часть виджет-эндпоинтов). */
data class AuthParams(
    val sessionToken: String? = null,
    val userHash: String? = null,
    val visitorId: String? = null,
)

/** Исключение при не-2xx ответе, который не обрабатывается специальным кодом. */
class ApiException(val code: Int, val bodyText: String) :
    IOException("HTTP $code: ${bodyText.take(200)}")

/** Итог `resume`: восстановление, пусто (204), запрет (403) или сбой сети. */
sealed interface ResumeOutcome {
    data class Restored(val data: ResumeResponseDto) : ResumeOutcome
    data object Empty : ResumeOutcome
    data object Forbidden : ResumeOutcome
    data object Failed : ResumeOutcome
}

/** Итог `history`: страница, остановка листания (403/404), или сбой сети. */
sealed interface HistoryOutcome {
    data class Page(val data: HistoryResponseDto) : HistoryOutcome
    data object Stop : HistoryOutcome
    data object Failed : HistoryOutcome
}

/**
 * REST-клиент виджет-эндпоинтов Respondo (см. `sdks/spec/openapi.widget.yaml`). Все пути даны полностью,
 * включая префикс `/api/v1`. Идемпотентные GET-и ретраятся с backoff; `POST /chat` — с 20с-таймаутом
 * и без внутреннего ретрая (повтор — на уровне UI как send-retry).
 */
class ApiClient(
    baseUrl: String,
    private val engine: HttpEngine,
) {
    private val base: String = UrlUtils.normalizeBaseUrl(baseUrl)

    /** Базовый URL API (без хвостового слэша). */
    val apiBaseUrl: String get() = base

    /** Базовый URL WebSocket, выведенный из API-URL. */
    val wsBaseUrl: String = UrlUtils.detectWsBaseUrl(base)

    /** URL SSE-потока событий беседы (`GET /chat/conversations/{id}/stream`). */
    fun streamUrl(conversationId: String, auth: AuthParams): String =
        base + "/api/v1/chat/conversations/" + conversationId + "/stream" + UrlUtils.query(
            "session_token" to auth.sessionToken,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )

    // --- widget-config ---

    /** `GET /widget/config/{agentId}`. [agentId] обязателен (SDK не поддерживает канал без агента). */
    suspend fun getWidgetConfig(
        agentId: String,
        channelId: String?,
        lang: String?,
        visitorId: String?,
    ): WidgetConfigDto {
        val url = base + "/api/v1/widget/config/" + agentId +
            UrlUtils.query("lang" to lang, "visitor_id" to visitorId, "channel_id" to channelId)
        val resp = getWithRetry(url)
        return decode(WidgetConfigDto.serializer(), resp)
    }

    /** `GET /widget/proactive/{agentId}`. Возвращает null при 204 (нет проактива). */
    suspend fun getProactive(
        agentId: String,
        pageTitle: String?,
        pagePath: String?,
        pageDescription: String?,
        lang: String?,
    ): ProactiveResponseDto? {
        val url = base + "/api/v1/widget/proactive/" + agentId + UrlUtils.query(
            "page_title" to pageTitle,
            "page_path" to pagePath,
            "page_description" to pageDescription,
            "lang" to lang,
        )
        val resp = getWithRetry(url)
        if (resp.code == 204) return null
        ensureSuccess(resp)
        return decode(ProactiveResponseDto.serializer(), resp)
    }

    // --- chat ---

    /** `POST /chat`. Таймаут 20с; не-2xx → [ApiException]. */
    suspend fun postChat(request: ChatRequestDto): ChatResponseDto {
        val resp = engine.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = "$base/api/v1/chat",
                body = HttpBody.Json(respondoJson.encodeToString(ChatRequestDto.serializer(), request)),
                timeoutMs = CHAT_TIMEOUT_MS,
            ),
        )
        ensureSuccess(resp)
        return decode(ChatResponseDto.serializer(), resp)
    }

    /** `POST /chat/upload` (multipart). Клиентская проверка лимита 20МБ до отправки. */
    suspend fun uploadFile(bytes: ByteArray, filename: String, contentType: String): ChatAttachmentDto {
        require(bytes.size <= MAX_UPLOAD_BYTES) { "attachment exceeds 20MB limit" }
        val resp = engine.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = "$base/api/v1/chat/upload",
                body = HttpBody.Multipart("file", filename, contentType, bytes),
            ),
        )
        ensureSuccess(resp)
        return decode(ChatAttachmentDto.serializer(), resp)
    }

    /** `GET /chat/resume`. Обрабатывает 200/204/403; сетевой сбой → [ResumeOutcome.Failed]. */
    suspend fun resume(
        conversationId: String?,
        auth: AuthParams,
        agentId: String?,
        channelId: String?,
        email: String?,
        userId: String?,
        limit: Int = 20,
    ): ResumeOutcome {
        val url = base + "/api/v1/chat/resume" + UrlUtils.query(
            "conversation_id" to conversationId,
            "session_token" to auth.sessionToken,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
            "agent_id" to agentId,
            "channel_id" to channelId,
            "email" to email,
            "user_id" to userId,
            "limit" to limit.toString(),
        )
        return try {
            val resp = getWithRetry(url)
            when (resp.code) {
                204 -> ResumeOutcome.Empty
                403 -> ResumeOutcome.Forbidden
                in 200..299 -> ResumeOutcome.Restored(decode(ResumeResponseDto.serializer(), resp))
                else -> ResumeOutcome.Failed
            }
        } catch (e: IOException) {
            RespondoLog.w("resume: сетевой сбой", e)
            ResumeOutcome.Failed
        }
    }

    /** `GET /chat/history`. 403/404 → [HistoryOutcome.Stop] (прекратить листание). */
    suspend fun history(conversationId: String, before: String, auth: AuthParams, limit: Int = 20): HistoryOutcome {
        val cappedLimit = limit.coerceIn(1, HISTORY_MAX_LIMIT)
        val url = base + "/api/v1/chat/history" + UrlUtils.query(
            "conversation_id" to conversationId,
            "before" to before,
            "session_token" to auth.sessionToken,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
            "limit" to cappedLimit.toString(),
        )
        return try {
            val resp = getWithRetry(url)
            when (resp.code) {
                403, 404 -> HistoryOutcome.Stop
                in 200..299 -> HistoryOutcome.Page(decode(HistoryResponseDto.serializer(), resp))
                else -> HistoryOutcome.Failed
            }
        } catch (e: IOException) {
            RespondoLog.w("history: сетевой сбой", e)
            HistoryOutcome.Failed
        }
    }

    /** `GET /chat/conversations/{id}/messages` (поллинг). Любая ошибка → null (поллинг молча игнорирует). */
    suspend fun getMessages(conversationId: String, after: String?, auth: AuthParams): WidgetMessagesResponseDto? {
        val url = base + "/api/v1/chat/conversations/" + conversationId + "/messages" + UrlUtils.query(
            "after" to after,
            "session_token" to auth.sessionToken,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )
        return try {
            val resp = engine.execute(HttpRequest(HttpMethod.GET, url))
            if (resp.isSuccessful) decode(WidgetMessagesResponseDto.serializer(), resp) else null
        } catch (e: IOException) {
            null
        }
    }

    /** `POST /chat/conversations/{id}/escalate`. */
    suspend fun escalate(conversationId: String, auth: AuthParams): EscalationResponseDto {
        val resp = postWithAuth("/api/v1/chat/conversations/$conversationId/escalate", auth)
        ensureSuccess(resp)
        return decode(EscalationResponseDto.serializer(), resp)
    }

    /** `POST /chat/conversations/{id}/continue`. */
    suspend fun continueWithAi(conversationId: String, auth: AuthParams): ContinueResponseDto {
        val resp = postWithAuth("/api/v1/chat/conversations/$conversationId/continue", auth)
        ensureSuccess(resp)
        return decode(ContinueResponseDto.serializer(), resp)
    }

    /** `POST /chat/conversations/{id}/revoke-session`. Best-effort (logout); ошибки поглощаются. */
    suspend fun revokeSession(conversationId: String, auth: AuthParams): RevokeResponseDto? {
        return try {
            val resp = postWithAuth("/api/v1/chat/conversations/$conversationId/revoke-session", auth)
            if (resp.isSuccessful) decode(RevokeResponseDto.serializer(), resp) else null
        } catch (e: IOException) {
            RespondoLog.w("revoke-session: сетевой сбой (best-effort)", e)
            null
        }
    }

    // --- engagement (news / surveys / banners / checklists) ---

    /** `GET /widget/news`. */
    suspend fun getNews(agentId: String?, conversationId: String?, auth: AuthParams, email: String?, userId: String?): NewsListResponseDto {
        val url = base + "/api/v1/widget/news" + UrlUtils.query(
            "conversation_id" to conversationId,
            "agent_id" to agentId,
            "email" to email,
            "user_id" to userId,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )
        val resp = getWithRetry(url)
        ensureSuccess(resp)
        return decode(NewsListResponseDto.serializer(), resp)
    }

    /** `POST /widget/news/{id}/seen`. Идемпотентно. */
    suspend fun markNewsSeen(itemId: String, agentId: String?, auth: AuthParams, email: String?, userId: String?) {
        val url = base + "/api/v1/widget/news/" + itemId + "/seen" + UrlUtils.query(
            "agent_id" to agentId,
            "email" to email,
            "user_id" to userId,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )
        runCatching { engine.execute(HttpRequest(HttpMethod.POST, url)) }
    }

    /** `GET /widget/checklists`. */
    suspend fun getChecklists(channelId: String?, agentId: String?, auth: AuthParams, email: String?, userId: String?): ChecklistsCatalogResponseDto {
        val url = base + "/api/v1/widget/checklists" + UrlUtils.query(
            "channel_id" to channelId,
            "agent_id" to agentId,
            "email" to email,
            "user_id" to userId,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )
        val resp = getWithRetry(url)
        ensureSuccess(resp)
        return decode(ChecklistsCatalogResponseDto.serializer(), resp)
    }

    /** `POST /widget/checklists/{id}/progress`. Best-effort. */
    suspend fun checklistProgress(checklistId: String, request: ChecklistProgressRequestDto) {
        runCatching {
            engine.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = "$base/api/v1/widget/checklists/$checklistId/progress",
                    body = HttpBody.Json(respondoJson.encodeToString(ChecklistProgressRequestDto.serializer(), request)),
                ),
            )
        }
    }

    /** `GET /widget/surveys`. Каталог overlay-опросов (kind=survey, format=in_modal). */
    suspend fun getSurveys(
        agentId: String?,
        channelId: String?,
        conversationId: String?,
        auth: AuthParams,
        email: String?,
        userId: String?,
        surveyId: String? = null,
        explicit: Boolean = false,
    ): SurveysCatalogResponseDto {
        // SDK объявляет features=survey_targeting: он сам исполняет правила экранов, задержку и
        // событие, поэтому сервер отдаёт ему и таргетированные опросы (без delivery_id до открытия).
        // surveyId открывает один опрос — сервер минтит доставку; explicit — это startSurvey(id)
        // (source=api: без правил и аудитории, но с расписанием и уже данным ответом). user_hash
        // обязателен для каналов с identity verification: без подписи контакт понижается до анонима.
        val url = base + "/api/v1/widget/surveys" + UrlUtils.query(
            "agent_id" to agentId,
            "channel_id" to channelId,
            "conversation_id" to conversationId,
            "visitor_id" to auth.visitorId,
            "email" to email,
            "user_id" to userId,
            "user_hash" to auth.userHash,
            "features" to SurveyTargeting.FEATURE,
            // Опрос «только сайт» приложению не нужен — сервер его не отдаст.
            "platform" to SurveyTargeting.CLIENT_PLATFORM,
            "survey_id" to surveyId,
            "source" to (if (explicit) "api" else null),
        )
        val resp = getWithRetry(url)
        ensureSuccess(resp)
        return decode(SurveysCatalogResponseDto.serializer(), resp)
    }

    /** `GET /widget/banners`. Каталог page-level баннеров (kind=banner). */
    suspend fun getBanners(
        agentId: String?,
        channelId: String?,
        conversationId: String?,
        auth: AuthParams,
        email: String?,
        userId: String?,
    ): BannersCatalogResponseDto {
        val url = base + "/api/v1/widget/banners" + UrlUtils.query(
            "agent_id" to agentId,
            "channel_id" to channelId,
            "conversation_id" to conversationId,
            "visitor_id" to auth.visitorId,
            "email" to email,
            "user_id" to userId,
        )
        val resp = getWithRetry(url)
        ensureSuccess(resp)
        return decode(BannersCatalogResponseDto.serializer(), resp)
    }

    /** `POST /widget/survey/answer`. Один ответ на вопрос. Best-effort. */
    suspend fun surveyAnswer(request: SubmitAnswerRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/survey/answer", SubmitAnswerRequestDto.serializer(), request,
    )

    /** `POST /widget/survey/submit`. Весь in-widget опрос разом. Best-effort. */
    suspend fun surveySubmit(request: SubmitSurveyRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/survey/submit", SubmitSurveyRequestDto.serializer(), request,
    )

    /** `POST /widget/banner/response`. Реакция/email по delivery_id баннера. Best-effort. */
    suspend fun bannerResponse(request: BannerResponseRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/banner/response", BannerResponseRequestDto.serializer(), request,
    )

    /** `POST /widget/events` (Respondo.track). Best-effort. */
    suspend fun track(request: TrackEventRequestDto) {
        runCatching {
            engine.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = "$base/api/v1/widget/events",
                    body = HttpBody.Json(respondoJson.encodeToString(TrackEventRequestDto.serializer(), request)),
                ),
            )
        }
    }

    // --- push (planned endpoints) ---

    /** `POST /widget/push/register`. */
    suspend fun pushRegister(request: PushRegisterRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/push/register", PushRegisterRequestDto.serializer(), request,
    )

    /** `POST /widget/push/unregister`. */
    suspend fun pushUnregister(request: PushUnregisterRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/push/unregister", PushUnregisterRequestDto.serializer(), request,
    )

    /** `POST /widget/push/opened`. */
    suspend fun pushOpened(request: PushOpenedRequestDto): Boolean = postJsonBestEffort(
        "/api/v1/widget/push/opened", PushOpenedRequestDto.serializer(), request,
    )

    // --- внутреннее ---

    private suspend fun <T> postJsonBestEffort(path: String, serializer: KSerializer<T>, value: T): Boolean {
        return try {
            val resp = engine.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = "$base$path",
                    body = HttpBody.Json(respondoJson.encodeToString(serializer, value)),
                ),
            )
            resp.isSuccessful
        } catch (e: IOException) {
            RespondoLog.w("POST $path: сетевой сбой", e)
            false
        }
    }

    private suspend fun postWithAuth(path: String, auth: AuthParams): HttpResponse {
        val url = base + path + UrlUtils.query(
            "session_token" to auth.sessionToken,
            "user_hash" to auth.userHash,
            "visitor_id" to auth.visitorId,
        )
        return engine.execute(HttpRequest(HttpMethod.POST, url))
    }

    /** GET с ретраями по backoff при сетевых сбоях и 5xx (идемпотентные эндпоинты). */
    private suspend fun getWithRetry(url: String): HttpResponse {
        var lastError: IOException? = null
        for (attempt in 0 until MAX_GET_ATTEMPTS) {
            try {
                val resp = engine.execute(HttpRequest(HttpMethod.GET, url))
                if (resp.code < 500) return resp
                lastError = ApiException(resp.code, resp.body)
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < MAX_GET_ATTEMPTS - 1) delay(BACKOFF_MS[attempt])
        }
        throw lastError ?: IOException("request failed: $url")
    }

    private fun ensureSuccess(resp: HttpResponse) {
        if (!resp.isSuccessful) throw ApiException(resp.code, resp.body)
    }

    private fun <T> decode(serializer: KSerializer<T>, resp: HttpResponse): T =
        respondoJson.decodeFromString(serializer, resp.body)

    companion object {
        const val CHAT_TIMEOUT_MS: Long = 20_000
        const val MAX_UPLOAD_BYTES: Int = 20 * 1024 * 1024
        private const val HISTORY_MAX_LIMIT = 50
        private const val MAX_GET_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(300, 800)

        /** Значения `source`/User-Agent для внешних потребителей. */
        const val SOURCE: String = SdkInfo.SOURCE
    }
}
