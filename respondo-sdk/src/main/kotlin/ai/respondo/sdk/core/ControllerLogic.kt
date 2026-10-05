package ai.respondo.sdk.core

import ai.respondo.sdk.RespondoIdentity

/**
 * Чистые решения оркестратора чата, вынесенные из [RespondoController] для детерминированного
 * юнит-тестирования (без Context, реалтайма и корутинного скоупа). Продакшн-путь вызывает ровно эти
 * функции — тест и рантайм используют одну и ту же логику.
 */
internal object ControllerLogic {

    /**
     * Ключ контакта для `identify()`: userId и email (email без регистра, пробелы по краям не
     * значимы). Смена ключа — другой контакт; name, user_hash и свойства контакт не меняют.
     */
    fun contactKey(identity: RespondoIdentity): Pair<String?, String?> = contactKey(identity.userId, identity.email)

    /** Ключ контакта по сырым полям (из `identify()` или штампа `overlay.show` `data.identity`). */
    fun contactKey(userId: String?, email: String?): Pair<String?, String?> = Pair(
        userId?.trim()?.takeIf { it.isNotEmpty() },
        email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
    )

    /**
     * Применять ли кадр `overlay.show` для текущей личности: только посчитанный для неё. Сокет
     * переживает `identify()` (реидентификация на том же соединении), и кадр прежнего контакта,
     * пришедший после `identify(B)`, иначе отдал бы B опросы и доставки A. Кадр без штампа
     * (`frameContact == null`) тоже отбрасывается — каталог по HTTP знает, для кого запрошен
     * (api-surface.md §3.3, как `survey-runtime.ts` на вебе).
     */
    fun acceptsOverlayFrame(frameContact: Pair<String?, String?>?, current: RespondoIdentity): Boolean =
        frameContact != null && frameContact == contactKey(current)

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

    // ==================== граница сессии ====================
    //
    // Одна беседа — один решённый вопрос. Закрытую строку следующее сообщение пользователя не
    // воскрешает: сервер форкает от неё follow-up и сцепляет их через
    // metadata.previous_conversation_id / next_conversation_id. Пользователю про это не сообщают —
    // у него один непрерывный чат, и ленту этого чата собираем МЫ, идя по цепочке.
    //
    // Поэтому несущая половина правила — клиентская. Сервер отдаёт conversation_id + session_token
    // при ЛЮБОМ статусе, закрытом в том числе, именно затем, чтобы форку было от чего оттолкнуться.
    // SDK их выбрасывал (сброс через 3с после resolved обнулял и id, и токен), следующий POST /chat
    // уходил с пустым conversation_id, сервер шёл в ветку «беседы нет» и создавал ОСИРОТЕВШИЙ
    // корень: без цепочки в обе стороны, без унаследованных «спама» и языка, без человеческой
    // передачи (оператор, закрывший тред минуту назад, получал бота поверх себя), без «Continued
    // from #N» в инбоксе — а собственный чат пользователя после каждого закрытия начинался с
    // середины разговора, уже навсегда.

    /**
     * Закрыта ли беседа с таким статусом. Статусов закрытия ДВА: `resolved` и `archived`
     * (осознанно убранная переписка) — тот же предикат, что у бэкендовой развилки
     * `isLiveConversation` (open|snoozed). Клиент, знающий только `resolved`, опрашивал бы
     * заархивированную строку вечно.
     */
    fun isClosedStatus(status: String?): Boolean = status == "resolved" || status == "archived"

    /** Пара «беседа + ключ от неё». */
    data class SessionHandles(val conversationId: String?, val sessionToken: String?)

    /**
     * Подхватить беседу, которую назвал сервер.
     *
     * Другой id означает, что закрытая строка форкнулась: с этого момента беседа пользователя —
     * новая. Остаться на старой значило бы форкать её снова каждым сообщением, а кейс, который
     * оператор уже взял в «Needs human», не получил бы ни единого сообщения.
     *
     * Пустые поля НЕ затирают имеющиеся: ответ эскалации выписывает токен только когда сессия
     * действительно сменилась, и «токена нет» здесь означает «остаёмся с прежним», а не «потеряли
     * доступ».
     */
    fun adoptSession(current: SessionHandles, responseId: String?, responseToken: String?): SessionHandles =
        SessionHandles(
            conversationId = responseId?.takeIf { it.isNotEmpty() } ?: current.conversationId,
            sessionToken = responseToken?.takeIf { it.isNotEmpty() } ?: current.sessionToken,
        )
}
