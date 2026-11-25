package ai.respondo.sdk

import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.ApiException
import ai.respondo.sdk.transport.AuthParams
import ai.respondo.sdk.transport.HistoryOutcome
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.ResumeOutcome
import ai.respondo.sdk.transport.dto.ChatRequestDto
import ai.respondo.sdk.transport.dto.InputSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Транспортные сценарии через фейковый [FakeHttpEngine] — без сети и эмулятора. */
class ApiClientTest {

    private fun api(engine: FakeHttpEngine) = ApiClient("https://api.respondo.ai", engine)

    @Test
    fun postChat_parsesResponse() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("chat-response-first.json")) }
        val response = api(engine).postChat(ChatRequestDto(agentId = "a", message = "hi"))
        assertEquals("a1c4e7b2-5d38-4f6a-9e10-3b7c2d5f8a90", response.conversationId)
        assertEquals(3, response.suggestedQuestions.size)
        // Тело ушло как JSON с полем source в snake_case, identity внутри camelCase.
        val body = engine.lastRequestFor("/api/v1/chat")?.body as? HttpBody.Json
        assertTrue(body!!.text.contains("\"agent_id\""))
    }

    /**
     * Текст, который набрал SDK за посетителя (пилюля, AI-подсказка, заглушка вложения), уходит с
     * input_source — сервер не выбирает по нему язык беседы. Без источника поля нет (explicitNulls=false).
     */
    @Test
    fun chatRequest_carriesInputSource() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("chat-response-first.json")) }
        api(engine).postChat(ChatRequestDto(agentId = "a", message = "How do I start?", inputSource = InputSource.QUICK_QUESTION))
        val body = engine.lastRequestFor("/api/v1/chat")?.body as? HttpBody.Json
        assertTrue(body!!.text.contains("\"input_source\":\"quick_question\""))

        api(engine).postChat(ChatRequestDto(agentId = "a", message = "hi"))
        val plain = engine.lastRequestFor("/api/v1/chat")?.body as? HttpBody.Json
        assertFalse(plain!!.text.contains("input_source"))
    }

    @Test
    fun chatRequest_usesChatTimeout() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("chat-response-first.json")) }
        api(engine).postChat(ChatRequestDto(agentId = "a", message = "hi"))
        assertEquals(ApiClient.CHAT_TIMEOUT_MS, engine.lastRequestFor("/api/v1/chat")?.timeoutMs)
    }

    @Test
    fun resume_handlesStatusCodes() = runTest {
        val restored = api(FakeHttpEngine { HttpResponse(200, Fixtures.read("resume.json")) })
            .resume("cid", AuthParams(sessionToken = "t"), "a", null, null, null)
        assertTrue(restored is ResumeOutcome.Restored)

        val empty = api(FakeHttpEngine { HttpResponse(204, "") })
            .resume("cid", AuthParams(sessionToken = "t"), "a", null, null, null)
        assertTrue(empty is ResumeOutcome.Empty)

        val forbidden = api(FakeHttpEngine { HttpResponse(403, "{}") })
            .resume("cid", AuthParams(sessionToken = "t"), "a", null, null, null)
        assertTrue(forbidden is ResumeOutcome.Forbidden)
    }

    @Test
    fun history_stopsOn404() = runTest {
        val outcome = api(FakeHttpEngine { HttpResponse(404, "{}") })
            .history("cid", "before", AuthParams())
        assertTrue(outcome is HistoryOutcome.Stop)
    }

    @Test
    fun history_returnsPage() = runTest {
        val outcome = api(FakeHttpEngine { HttpResponse(200, Fixtures.read("history-page.json")) })
            .history("cid", "before", AuthParams())
        assertTrue(outcome is HistoryOutcome.Page)
        assertEquals(2, (outcome as HistoryOutcome.Page).data.messages.size)
    }

    @Test
    fun proactive_returnsNullOn204() = runTest {
        val result = api(FakeHttpEngine { HttpResponse(204, "") })
            .getProactive("a", "title", "/", null, "en")
        assertEquals(null, result)
    }

    @Test
    fun upload_rejectsOversizeFile() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("upload-response.json")) }
        val tooBig = ByteArray(ApiClient.MAX_UPLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { api(engine).uploadFile(tooBig, "big.png", "image/png", agentId = "agent-1") }
        }
    }

    private fun uploadFields(
        conversationId: String?,
        auth: AuthParams,
    ): Map<String, String> = runBlocking {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("upload-response.json")) }
        api(engine).uploadFile(ByteArray(3), "a.png", "image/png", "agent-1", conversationId, auth)
        (engine.lastRequestFor("/chat/upload")!!.body as HttpBody.Multipart).fields
    }

    @Test
    fun upload_withoutConversation_sendsAgentOnly() {
        val engine = FakeHttpEngine { HttpResponse(200, Fixtures.read("upload-response.json")) }
        runBlocking { api(engine).uploadFile(ByteArray(3), "a.png", "image/png", agentId = "agent-1") }
        val fields = (engine.lastRequestFor("/chat/upload")!!.body as HttpBody.Multipart).fields
        assertEquals(mapOf("agent_id" to "agent-1"), fields)
    }

    @Test
    fun upload_withConversationAndToken_sendsConvCreds() {
        val fields = uploadFields("conv-1", AuthParams(sessionToken = "tok", userHash = null, visitorId = "v_1"))
        assertEquals(
            mapOf("agent_id" to "agent-1", "conversation_id" to "conv-1", "session_token" to "tok", "visitor_id" to "v_1"),
            fields,
        )
    }

    @Test
    fun upload_conversationWithoutOwnershipKey_dropsConversation() {
        val fields = uploadFields("conv-1", AuthParams(sessionToken = "", userHash = "", visitorId = "v_1"))
        assertEquals(mapOf("agent_id" to "agent-1"), fields)
    }

    @Test
    fun upload_signedIdentityWithoutToken_sendsUserHash() {
        val fields = uploadFields("conv-1", AuthParams(sessionToken = null, userHash = "h", visitorId = "v_1"))
        assertEquals(
            mapOf("agent_id" to "agent-1", "conversation_id" to "conv-1", "user_hash" to "h", "visitor_id" to "v_1"),
            fields,
        )
    }

    @Test
    fun wsBaseUrl_derivedFromApi() {
        assertEquals("wss://wss.respondo.ai", api(FakeHttpEngine()).wsBaseUrl)
    }

    @Test
    fun apiException_flatBodyExposesBackendCode() {
        val e = ApiException(400, Fixtures.read("error-flat.json"))
        assertEquals(400, e.code)
        assertEquals("BAD_REQUEST", e.errorCode)
        assertEquals("invalid agent_id", e.errorMessage)
    }

    @Test
    fun apiException_nestedBodyExposesBackendCode() {
        val e = ApiException(404, Fixtures.read("error-nested.json"))
        assertEquals(404, e.code)
        assertEquals("NOT_FOUND", e.errorCode)
        assertEquals("Resource not found", e.errorMessage)
    }

    @Test
    fun apiException_legacyAndNonJsonBodiesGiveNull() {
        val legacy = ApiException(400, Fixtures.read("error-legacy.json"))
        assertNull(legacy.errorCode)
        assertNull(legacy.errorMessage)
        val html = ApiException(502, "<html>")
        assertNull(html.errorCode)
        assertNull(html.errorMessage)
        assertEquals(502, html.code)
    }
}
