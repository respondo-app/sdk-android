package ai.respondo.sdk.core

/**
 * Чистые решения оркестратора чата, вынесенные из [RespondoController] для детерминированного
 * юнит-тестирования (без Context, реалтайма и корутинного скоупа). Продакшн-путь вызывает ровно эти
 * функции — тест и рантайм используют одну и ту же логику.
 */
internal object ControllerLogic {

    /** Состояние индикатора «печатает» по кадру typing: `is_typing=false` гасит индикатор. */
    fun typingState(isTyping: Boolean, authorName: String?): TypingState? =
        if (!isTyping) null else TypingState(authorName)

    /**
     * Мержит восстановленный сервером тред с локальными оптимистичными сообщениями. Сохраняет ещё
     * не подтверждённые пузыри (SENDING/FAILED), которых нет в восстановленном списке по id, —
     * чтобы resume не стирал набранное/упавшее сообщение пользователя.
     */
    fun mergeResumeMessages(restored: List<ChatMessage>, current: List<ChatMessage>): List<ChatMessage> {
        if (current.isEmpty()) return restored
        val restoredIds = restored.mapTo(HashSet()) { it.id }
        val pending = current.filter {
            it.isLocal &&
                (it.sendStatus == SendStatus.SENDING || it.sendStatus == SendStatus.FAILED) &&
                it.id !in restoredIds
        }
        return restored + pending
    }

    /** id последнего действительно серверного сообщения (локальные карточки исключены). */
    fun lastBackendId(messages: List<ChatMessage>): String? =
        messages.lastOrNull { it.isBackendMessage() }?.id

    /**
     * Нужно ли поднять бейдж непрочитанного для кадра campaign_conversation. Поднимаем ТОЛЬКО когда
     * кампания без тела сообщения ([hasDto] == false): при наличии dto счётчик уже ведёт
     * `onNewMessageEvent` (с дедупом по id) — иначе получается двойной инкремент. Чат открыт или
     * кампания уже адоптирована — не поднимаем.
     */
    fun campaignBadgeShouldBump(hasDto: Boolean, chatOpen: Boolean, alreadyAdopted: Boolean): Boolean =
        !hasDto && !chatOpen && !alreadyAdopted

    /**
     * Нужно ли автопрокрутить чат к последнему сообщению. Скроллим при первом показе ([firstScroll]),
     * когда пользователь у низа ([nearBottom]) либо когда последнее сообщение — собственная локальная
     * отправка ([lastIsOwnSend]): свой только что отправленный пузырь показываем всегда, даже если
     * пользователь читал историю выше и формально не «у низа».
     */
    fun shouldAutoScroll(firstScroll: Boolean, nearBottom: Boolean, lastIsOwnSend: Boolean): Boolean =
        firstScroll || nearBottom || lastIsOwnSend

    /** Итог маршрутизации engagement-ссылки (баннер-CTA, url-задача чек-листа). */
    enum class UrlOpenPath { HANDLED_BY_HOST, OPEN_EXTERNAL, REJECTED_SCHEME }

    /**
     * Куда направить engagement-ссылку — симметрично поведению ссылок чата. Если host-листенер уже
     * обработал ([hostHandled]) — ничего не делаем. Иначе открываем внешним приложением только
     * безопасную http(s)-схему ([isHttp]); прочие схемы отклоняем, не запуская произвольный Intent.
     */
    fun resolveEngagementUrlPath(hostHandled: Boolean, isHttp: Boolean): UrlOpenPath = when {
        hostHandled -> UrlOpenPath.HANDLED_BY_HOST
        isHttp -> UrlOpenPath.OPEN_EXTERNAL
        else -> UrlOpenPath.REJECTED_SCHEME
    }
}
