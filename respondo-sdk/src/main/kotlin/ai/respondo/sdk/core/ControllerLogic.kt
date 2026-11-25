package ai.respondo.sdk.core

import ai.respondo.sdk.RespondoIdentity
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.StatusTextsDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    // ==================== системные карточки на языке клиента ====================
    //
    // «Передано человеку», «вернулся к ИИ», «чат завершён», ответ и отказ «Talk to a person» сервер
    // отдаёт текстами каталога на языке беседы (`handover_card_text`, `status_texts`, `message`
    // ответа `/escalate`). Строки SDK — только фолбэк (офлайн, старый бэкенд): UI-локаль
    // приложения — не язык клиента, и китайский клиент английского приложения после китайского
    // разговора получал английские карточки.

    /** Запомненные карточки статусов на языке клиента (и тексты блока оценки беседы). */
    data class StatusTexts(
        val backToAi: String? = null,
        val resolved: String? = null,
        val ratingQuestion: String? = null,
        val ratingThanks: String? = null,
        val ratingRemarkPrompt: String? = null,
        val ratingRemarkPlaceholder: String? = null,
        val ratingSend: String? = null,
        val ratingEdit: String? = null,
    )

    /** Непустая строка с обрезанными пробелами или `null`. */
    fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Серверный текст на языке клиента, иначе строка SDK. */
    fun serverTextOr(server: String?, fallback: String): String = nonBlank(server) ?: fallback

    /** Новые тексты поверх прежних: пустое поле известный текст не затирает. */
    fun mergeStatusTexts(prev: StatusTexts, next: StatusTextsDto?): StatusTexts {
        if (next == null) return prev
        return StatusTexts(
            backToAi = nonBlank(next.backToAi) ?: prev.backToAi,
            resolved = nonBlank(next.resolved) ?: prev.resolved,
            ratingQuestion = nonBlank(next.ratingQuestion) ?: prev.ratingQuestion,
            ratingThanks = nonBlank(next.ratingThanks) ?: prev.ratingThanks,
            ratingRemarkPrompt = nonBlank(next.ratingRemarkPrompt) ?: prev.ratingRemarkPrompt,
            ratingRemarkPlaceholder = nonBlank(next.ratingRemarkPlaceholder) ?: prev.ratingRemarkPlaceholder,
            ratingSend = nonBlank(next.ratingSend) ?: prev.ratingSend,
            ratingEdit = nonBlank(next.ratingEdit) ?: prev.ratingEdit,
        )
    }

    // ==================== оценка беседы (CSAT) ====================

    private fun remarkStepFor(prev: RemarkStep, remark: String, editable: Boolean): RemarkStep = when {
        remark.isEmpty() -> RemarkStep.OPEN
        prev == RemarkStep.EDITING && editable -> RemarkStep.EDITING
        else -> RemarkStep.SENT
    }

    /**
     * Ответ сервера (resume, опрос) для беседы [cid]. [value] null — выбранная цифра остаётся;
     * [remark] null — отзыв остаётся. Нет запроса — нет ни оценки, ни отзыва.
     */
    fun nextRating(
        prev: ConversationRating,
        cid: String?,
        requested: Boolean,
        value: Int? = null,
        remark: Pair<String, Boolean>? = null,
    ): ConversationRating {
        if (cid == null) return ConversationRating()
        val same = prev.conversationId == cid
        val keep = same && requested
        val v = if (!requested) 0 else value ?: if (same) prev.value else 0
        val text = if (!requested) "" else remark?.first ?: if (keep) prev.remark else ""
        val editable = if (!requested) true else remark?.second ?: if (keep) prev.remarkEditable else true
        return ConversationRating(
            conversationId = cid,
            requested = requested,
            value = v,
            remark = text,
            remarkEditable = editable,
            remarkStep = remarkStepFor(if (keep) prev.remarkStep else RemarkStep.OPEN, text, editable),
        )
    }

    /** `rating_remark*` ответа: полей нет — null (старый бэкенд). */
    fun serverRemark(text: String?, editable: Boolean?): Pair<String, Boolean>? =
        if (text == null && editable == null) null else (text ?: "").trim() to (editable ?: true)

    fun editRemark(r: ConversationRating): ConversationRating =
        if (r.remarkEditable && r.remark.isNotEmpty()) r.copy(remarkStep = RemarkStep.EDITING) else r

    /** Поле правки покинули без изменений — назад к сохранённой строке; правка остаётся. */
    fun cancelRemarkEdit(r: ConversationRating): ConversationRating =
        if (r.remarkStep == RemarkStep.EDITING && r.remark.isNotEmpty()) r.copy(remarkStep = RemarkStep.SENT) else r

    fun savedRemark(r: ConversationRating, text: String, editable: Boolean): ConversationRating {
        val t = text.trim()
        return r.copy(remark = t, remarkEditable = editable, remarkStep = if (t.isEmpty()) RemarkStep.OPEN else RemarkStep.SENT)
    }

    fun lockedRemark(r: ConversationRating): ConversationRating =
        r.copy(remarkEditable = false, remarkStep = if (r.remark.isEmpty()) RemarkStep.OPEN else RemarkStep.SENT)

    /**
     * Уведомление о передаче в ответе `/chat`: сообщение ответа, кроме эха реплики самого клиента
     * (тред уже у людей — сервер возвращает её эхом): та уже нарисована оптимистично, и уведомлением
     * она не является. `null` — уведомления нет, рисуется только карточка.
     */
    fun handoverNotice(message: MessageDto?): MessageDto? =
        message?.takeIf { ChatMessage.normalizeRole(it.role) != MessageRole.USER }

    /** Стандартное уведомление о передаче (`metadata.handover_canned`) — само карточка. */
    fun isCannedHandover(notice: MessageDto?): Boolean = notice?.metadata?.handoverCanned == true

    /**
     * Текст карточки «передано человеку» под ответом `/chat`: `handover_card_text` ответа (сервер
     * заполняет его на каждой ветке с `human_handover`), иначе `metadata.handover_card_text`
     * уведомления, иначе строка SDK.
     */
    fun handoverCardText(responseCardText: String?, notice: MessageDto?, fallback: String): String =
        nonBlank(responseCardText) ?: nonBlank(notice?.metadata?.handoverCardText) ?: fallback

    /** Текст отказа `/escalate` для клиента (`message` тела ошибки) на его языке; `null` — нет. */
    fun escalateRefusalText(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val obj = runCatching { respondoJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return nonBlank((obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
    }
}
