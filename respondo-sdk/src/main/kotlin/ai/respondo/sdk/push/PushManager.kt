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

    fun onUnhandledDeepLink(payload: RespondoPushPayload)

    fun registrationContext(): PushRegistrationContext
}

/**
 * Клиентская часть пушей: регистрация/снятие токена, обработка тапа с дедупликацией по message_id
 * (общей с сообщениями, полученными по WS/REST — через [PushHost.hasSeenMessage]).
 */
class PushManager(
    private val apiClient: ApiClient,
    private val host: PushHost,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var deviceToken: String? = null

    /** Запоминает токен и регистрирует его на бэкенде. Токен переживает reset (перерегистрируется под новым визитёром). */
    fun setPushToken(token: String) {
        deviceToken = token
        register()
    }

    /** Снимает регистрацию токена (logout). */
    fun clearPushToken() {
        val token = deviceToken ?: return
        val ctx = host.registrationContext()
        val channelId = ctx.channelId
        deviceToken = null
        if (channelId.isNullOrEmpty()) return
        scope.launch {
            apiClient.pushUnregister(PushUnregisterRequestDto(channelId = channelId, token = token))
        }
    }

    /** Перерегистрирует текущий токен под актуальным визитёром (вызывается после reset). */
    fun reRegister() {
        if (deviceToken != null) register()
    }

    private fun register() {
        val token = deviceToken ?: return
        val ctx = host.registrationContext()
        val channelId = ctx.channelId
        if (channelId.isNullOrEmpty()) {
            RespondoLog.w("push register пропущен: channel_id не задан")
            return
        }
        scope.launch {
            apiClient.pushRegister(
                PushRegisterRequestDto(
                    agentId = ctx.agentId,
                    channelId = channelId,
                    platform = SdkInfo.PLATFORM,
                    token = token,
                    appId = ctx.appId,
                    locale = ctx.locale,
                    sdkVersion = SdkInfo.VERSION,
                    visitorId = ctx.visitorId,
                    email = ctx.email,
                    userId = ctx.userId,
                    userHash = ctx.userHash,
                    sessionToken = ctx.sessionToken,
                ),
            )
        }
    }

    /** Признак «это распознанный Respondo-пуш» — для синхронного возврата из фасада (в т. ч. до init). */
    fun isRecognized(payload: RespondoPushPayload): Boolean =
        payload.type != null || payload.conversationId != null

    /**
     * Обрабатывает тап по пушу. Дедуп по message_id: повтор не открывает и не дублирует. Foreground-подавление:
     * если беседа уже открыта — эффектов нет (сообщение уже на экране). Иначе открывает беседу; если беседа
     * недоступна — [PushHost.onUnhandledDeepLink]. Телеметрия открытия — best-effort.
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
            host.onUnhandledDeepLink(payload)
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

    private fun reportOpened(payload: RespondoPushPayload) {
        val token = deviceToken ?: return
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
}
