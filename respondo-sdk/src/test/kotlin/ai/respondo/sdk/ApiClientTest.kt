package ai.respondo.sdk

import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.AuthParams
import ai.respondo.sdk.transport.HistoryOutcome
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.ResumeOutcome
import ai.respondo.sdk.transport.dto.ChatRequestDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            kotlinx.coroutines.runBlocking { api(engine).uploadFile(tooBig, "big.png", "image/png") }
        }
    }

    @Test
    fun wsBaseUrl_derivedFromApi() {
        assertEquals("wss://wss.respondo.ai", api(FakeHttpEngine()).wsBaseUrl)
    }
}
