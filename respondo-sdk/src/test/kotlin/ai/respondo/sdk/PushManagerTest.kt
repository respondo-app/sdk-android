package ai.respondo.sdk

import ai.respondo.sdk.push.PushHost
import ai.respondo.sdk.push.PushManager
import ai.respondo.sdk.push.PushRegistrationContext
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Регистрация токена и дедуп/подавление при обработке пуша. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushManagerTest {

    private class FakeHost(private val foreground: Boolean = false) : PushHost {
        val seen = HashSet<String>()
        val opened = mutableListOf<String>()
        val unhandled = mutableListOf<RespondoPushPayload>()

        override fun hasSeenMessage(messageId: String): Boolean = seen.contains(messageId)
        override fun markMessageSeen(messageId: String) { seen.add(messageId) }
        override fun isConversationForeground(conversationId: String?): Boolean = foreground
        override fun openConversation(conversationId: String): Boolean {
            opened += conversationId
            return true
        }
        override fun onUnhandledDeepLink(payload: RespondoPushPayload) { unhandled += payload }
        override fun registrationContext(): PushRegistrationContext =
            PushRegistrationContext("agent", "channel", "app", "en", "v_1", null, null, null, null)
    }

    private fun api(engine: FakeHttpEngine) = ApiClient("https://api.respondo.ai", engine)

    private fun payload(msgId: String?, convId: String? = "conv-1") = RespondoPushPayload(
        conversationId = convId,
        messageId = msgId,
        title = "t",
        body = "b",
        raw = mapOf("__respondo_type" to "message"),
    )

    @Test
    fun setPushToken_registersOnBackend() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, "{\"ok\":true}") }
        val pm = PushManager(api(engine), FakeHost(), this)
        pm.setPushToken("device-token")
        advanceUntilIdle()
        assertTrue(engine.requests.any { it.url.contains("/api/v1/widget/push/register") })
    }

    @Test
    fun handlePush_opensConversationOnce_deduped() = runTest {
        val host = FakeHost()
        val pm = PushManager(api(FakeHttpEngine { HttpResponse(200, "{}") }), host, this)
        pm.setPushToken("t")
        pm.handlePush(payload("m-1"))
        pm.handlePush(payload("m-1")) // дубль по message_id
        advanceUntilIdle()
        assertEquals(1, host.opened.size)
        assertEquals("conv-1", host.opened.first())
    }

    @Test
    fun handlePush_suppressedWhenForeground() = runTest {
        val host = FakeHost(foreground = true)
        val pm = PushManager(api(FakeHttpEngine { HttpResponse(200, "{}") }), host, this)
        pm.setPushToken("t")
        pm.handlePush(payload("m-2"))
        advanceUntilIdle()
        assertTrue(host.opened.isEmpty()) // беседа уже открыта — подавление
    }

    @Test
    fun handlePush_noConversation_reportsUnhandled() = runTest {
        val host = FakeHost()
        val pm = PushManager(api(FakeHttpEngine { HttpResponse(200, "{}") }), host, this)
        pm.handlePush(payload(msgId = "m-3", convId = null))
        advanceUntilIdle()
        assertEquals(1, host.unhandled.size)
    }

    @Test
    fun isRecognized_trueForRespondoPayload() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val pm = PushManager(api(FakeHttpEngine()), FakeHost(), scope)
        assertTrue(pm.isRecognized(payload("m")))
    }
}
