package ai.respondo.sdk

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.dto.EscalationResponseDto
import ai.respondo.sdk.transport.dto.ResumeResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ГРАНИЦА СЕССИИ НА КЛИЕНТЕ.
 *
 * Одна беседа — один решённый вопрос. Закрытую строку следующее сообщение пользователя не
 * воскрешает: сервер форкает от неё follow-up и сцепляет их. Пользователю про это не сообщают — у
 * него один непрерывный чат, и ленту этого чата собираем МЫ, идя по цепочке.
 *
 * Значит несущая половина правила — клиентская, и до этих тестов её не проверял никто: бэкендовый
 * контракт запинен e2e-набором, а то, что КЛИЕНТ подхватывает выданные ему conversation_id и
 * session_token, не утверждал ни один тест ни в одном из трёх клиентов. Поэтому все три спокойно
 * это правило нарушали с зелёными наборами: SDK обнулял хэндлы через три секунды после закрытия,
 * и каждое закрытие рождало осиротевший корень, а нажатие «нужен человек» тихо переставало
 * работать.
 */
class SessionBoundaryTest {

    private val convA = "a1c4e7b2-5d38-4f6a-9e10-3b7c2d5f8a90"
    private val convB = "b2d5f8c3-6e49-4a7b-8f21-4c8d3e6a9b01"

    // ==================== закрытость ====================

    @Test
    fun `both closing statuses count`() {
        assertTrue(ControllerLogic.isClosedStatus("resolved"))
        assertTrue(
            "archived — второй статус закрытия; клиент, знающий только resolved, слушал бы мёртвую строку вечно",
            ControllerLogic.isClosedStatus("archived"),
        )
        assertFalse(ControllerLogic.isClosedStatus("open"))
        assertFalse(ControllerLogic.isClosedStatus("snoozed"))
        assertFalse(ControllerLogic.isClosedStatus("escalated"))
        assertFalse(ControllerLogic.isClosedStatus(null))
    }

    // ==================== хэндлы переживают закрытие ====================

    @Test
    fun `resume of a closed thread still carries the handles to fork from`() {
        val json = """
            {"status":"resolved","messages":[],"has_more":false,
             "conversation_id":"$convA","session_token":"token-A"}
        """.trimIndent()
        val dto = respondoJson.decodeFromString(ResumeResponseDto.serializer(), json)

        assertEquals(
            "id закрытой строки — единственное, от чего можно форкнуть follow-up",
            convA, dto.conversationId,
        )
        assertEquals("token-A", dto.sessionToken)
        assertTrue(ControllerLogic.isClosedStatus(dto.status))
    }

    @Test
    fun `closing keeps both handles - closed is a flag, not an erase`() {
        // Регрессия, ради которой файл и существует: сброс через 3с после resolved обнулял
        // conversationId и sessionToken. После него следующий POST /chat уходил с пустым
        // conversation_id (сервер создавал осиротевший корень), а escalate() выходил на первой
        // строке `conversationId ?: return` — кнопка молча переставала работать.
        val closed = ControllerLogic.SessionHandles(convA, "token-A")
        val afterClose = ControllerLogic.adoptSession(closed, responseId = null, responseToken = null)

        assertEquals(convA, afterClose.conversationId)
        assertEquals("token-A", afterClose.sessionToken)
    }

    // ==================== следующее сообщение / «нужен человек» ====================

    @Test
    fun `the follow-up handles are adopted`() {
        val closed = ControllerLogic.SessionHandles(convA, "token-A")
        val next = ControllerLogic.adoptSession(closed, responseId = convB, responseToken = "token-B")

        assertEquals(
            "остаться на закрытой строке значит форкать её снова каждым сообщением, а кейс в «Needs human» не получит ни одного",
            convB, next.conversationId,
        )
        assertEquals(
            "без ключа от новой строки собственная лента ответит 403: follow-up рождается с access=token",
            "token-B", next.sessionToken,
        )
    }

    @Test
    fun `a partial response never blanks out handles we already hold`() {
        val live = ControllerLogic.SessionHandles(convA, "token-A")

        // Ответ эскалации выписывает токен только когда сессия действительно сменилась.
        assertEquals(live, ControllerLogic.adoptSession(live, convA, null))
        assertEquals(live, ControllerLogic.adoptSession(live, null, null))
        assertEquals(live, ControllerLogic.adoptSession(live, "", ""))
    }

    @Test
    fun `escalation response decodes the follow-up handles`() {
        val json = """
            {"conversation_id":"$convB","status":"escalated","message":"Оператор подключится",
             "session_token":"token-B","previous_conversation_id":"$convA"}
        """.trimIndent()
        val dto = respondoJson.decodeFromString(EscalationResponseDto.serializer(), json)

        assertEquals(convB, dto.conversationId)
        assertEquals(
            "поля session_token в DTO не было вовсе — ключ от новой строки молча терялся при разборе",
            "token-B", dto.sessionToken,
        )
        assertEquals(
            "по previous_conversation_id клиент понимает, что тред тот же, и не сбрасывает ленту",
            convA, dto.previousConversationId,
        )
    }

    @Test
    fun `escalation response without a fork decodes to nulls, not a failure`() {
        // Живая беседа: сессия не менялась, сервер токен не выписывает.
        val json = """{"conversation_id":"$convA","status":"escalated","message":"…"}"""
        val dto = respondoJson.decodeFromString(EscalationResponseDto.serializer(), json)

        assertEquals(convA, dto.conversationId)
        assertEquals(null, dto.sessionToken)
        assertEquals(null, dto.previousConversationId)
    }
}
