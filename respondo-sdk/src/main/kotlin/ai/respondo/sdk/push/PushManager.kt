package ai.respondo.sdk.push

import ai.respondo.sdk.RespondoPushPayload
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.SdkInfo
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.dto.PushOpenedRequestDto
import ai.respondo.sdk.transport.dto.PushRegisterRequestDto
import ai.respondo.sdk.transport.dto.PushUnregisterRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Контекст регистрации push-токена (реквизиты контакта/канала на момент регистрации). */
data class PushRegistrationContext(
    val agentId: String?,
    val channelId: String?,
    val appId: String?,
    val locale: String?,
    val visitorId: String?,
    val email: String?,
    val userId: String?,
    val userHash: String?,
    val sessionToken: String?,
)

/** Мост между [PushManager] и чат-контроллером: дедуп, foreground-подавление, открытие беседы. */
interface PushHost {
    fun hasSeenMessage(messageId: String): Boolean
    fun markMessageSeen(messageId: String)

    /** Открыт ли сейчас чат этой беседы (для подавления системного уведомления). */
    fun isConversationForeground(conversationId: String?): Boolean

    /** Открывает беседу; возвращает true, если она отображена (иначе — [RespondoListener.onUnhandledDeepLink]). */
    fun openConversation(conversationId: String): Boolean

    /**
     * Открывает диплинк экрана приложения из push-кампании (как Intercom: ссылку отдаём системе, приложение
     * принимает её своим intent-filter). true — ссылку кто-то принял.
     */
    fun openDeepLink(link: String): Boolean

    fun onUnhandledDeepLink(payload: RespondoPushPayload)

    fun registrationContext(): PushRegistrationContext
}

/**
 * Клиентская часть пушей: регистрация/снятие токена, обработка тапа с дедупликацией по message_id
 * (общей с сообщениями, полученными по WS/REST — через [PushHost.hasSeenMessage]).
 */
class PushManager @JvmOverloads constructor(
    private val apiClient: ApiClient,
    private val host: PushHost,
    private val scope: CoroutineScope,
    /** Ждёт ответа `/widget/identify` в полёте: регистрация не создаёт контакт раньше identify. */
    private val awaitIdentity: suspend () -> Unit = {},
) {
    @Volatile
    private var deviceToken: String? = null

    /**
     * Тапы, обработанные раньше, чем стал известен токен ([reportOpened]): бикон открытия уходит, как только
     * токен появится ([setPushToken]). Под `this`.
     */
    private val openedWithoutToken = ArrayDeque<RespondoPushPayload>()

    /** Тело последней регистрации, ушедшей или в полёте (без `session_token` — сервер его не читает). Под `this`. */
    private var lastRegistration: PushRegisterRequestDto? = null

    /**
     * Запоминает токен и регистрирует его на бэкенде. Токен переживает reset (перерегистрируется под новым
     * визитёром). Биконы открытия, ждавшие токена, уходят сейчас.
     */
    fun setPushToken(token: String) {
        val waiting = synchronized(this) {
            deviceToken = token
            openedWithoutToken.toList().also { openedWithoutToken.clear() }
        }
        waiting.forEach(::reportOpened)
        register()
    }

    /** Выход (reset): тапы прежнего пользователя, ждавшие токена, бикон открытия не отправят. */
    internal fun dropPendingOpens() {
        synchronized(this) { openedWithoutToken.clear() }
    }

    /** Снимает регистрацию токена (logout). */
    fun clearPushToken() {
        synchronized(this) {
            openedWithoutToken.clear()
            lastRegistration = null
        }
        val token = deviceToken ?: return
        val ctx = host.registrationContext()
        val channelId = ctx.channelId
        deviceToken = null
        if (channelId.isNullOrEmpty()) return
        scope.launch {
            apiClient.pushUnregister(PushUnregisterRequestDto(channelId = channelId, token = token))
        }
    }

    /** Перерегистрирует текущий токен под актуальной личностью/визитёром (после identify и reset). */
    fun reRegister() {
        if (deviceToken != null) register()
    }

    private fun register() {
        if (deviceToken == null) return
        if (host.registrationContext().channelId.isNullOrEmpty()) {
            RespondoLog.w("push register пропущен: channel_id не задан")
            return
        }
        scope.launch {
            awaitIdentity()
            // Токен и личность — на момент отправки: за ожидание токен могли снять или сменить.
            val token = deviceToken ?: return@launch
            val ctx = host.registrationContext()
            val channelId = ctx.channelId ?: return@launch
            val body = PushRegisterRequestDto(
                agentId = ctx.agentId,
                channelId = channelId,
                platform = SdkInfo.PLATFORM,
                token = token,
                appId = ctx.appId,
                locale = ctx.locale,
                sdkVersion = SdkInfo.VERSION,
                sdkName = SdkInfo.SOURCE,
                visitorId = ctx.visitorId,
                email = ctx.email,
                userId = ctx.userId,
                userHash = ctx.userHash,
                sessionToken = ctx.sessionToken,
            )
            // То же тело (токен, личность, визитёр) уже ушло или в полёте — не повторяем: на холодном старте
            // регистрацию после ответа identify просят и setPushToken, и identify(). Сбой снимает отметку.
            val key = body.copy(sessionToken = null)
            synchronized(this@PushManager) {
                if (key == lastRegistration) return@launch
                lastRegistration = key
            }
            if (!apiClient.pushRegister(body)) {
                synchronized(this@PushManager) { if (lastRegistration == key) lastRegistration = null }
            }
        }
    }

    /** Признак «это распознанный Respondo-пуш» — для синхронного возврата из фасада (в т. ч. до init). */
    fun isRecognized(payload: RespondoPushPayload): Boolean =
        payload.type != null || payload.conversationId != null

    /**
     * Обрабатывает тап по пушу. Дедуп по message_id: повтор не открывает и не дублирует. Foreground-подавление:
     * если беседа уже открыта — эффектов нет (сообщение уже на экране). Иначе открывает беседу; если беседа
     * недоступна — [PushHost.onUnhandledDeepLink]. Пуш без беседы (кампания) открывает свой диплинк через
     * [PushHost.openDeepLink]; нет ссылки или её никто не принял — [PushHost.onUnhandledDeepLink].
     * Телеметрия открытия — best-effort.
     */
    fun handlePush(payload: RespondoPushPayload) {
        val messageId = payload.messageId
        if (messageId != null && host.hasSeenMessage(messageId)) {
            RespondoLog.d("push дубликат по message_id=$messageId — пропуск")
            reportOpened(payload)
            return
        }
        if (messageId != null) host.markMessageSeen(messageId)

        val conversationId = payload.conversationId
        if (conversationId.isNullOrEmpty()) {
            val link = payload.deepLink
            if (link.isNullOrBlank() || !host.openDeepLink(link)) host.onUnhandledDeepLink(payload)
            reportOpened(payload)
            return
        }

        // Foreground-подавление: беседа уже открыта — просто фиксируем открытие.
        if (host.isConversationForeground(conversationId)) {
            reportOpened(payload)
            return
        }

        val displayed = host.openConversation(conversationId)
        if (!displayed) host.onUnhandledDeepLink(payload)
        reportOpened(payload)
    }

    /**
     * Бикон открытия. Сервер находит доставку по токену устройства; токена ещё нет (тап холодного старта:
     * getInitialMessage раньше getToken) — бикон ждёт [setPushToken], не больше [OPENED_WITHOUT_TOKEN_CAP].
     */
    private fun reportOpened(payload: RespondoPushPayload) {
        val token = synchronized(this) {
            deviceToken ?: run {
                openedWithoutToken.addLast(payload)
                if (openedWithoutToken.size > OPENED_WITHOUT_TOKEN_CAP) openedWithoutToken.removeFirst()
                null
            }
        } ?: return
        scope.launch {
            apiClient.pushOpened(
                PushOpenedRequestDto(
                    deliveryId = payload.deliveryId,
                    messageId = payload.messageId,
                    token = token,
                ),
            )
        }
    }

    private companion object {
        const val OPENED_WITHOUT_TOKEN_CAP = 10
    }
}
