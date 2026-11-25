package ai.respondo.sdk

import ai.respondo.sdk.push.PushHost
import ai.respondo.sdk.push.PushManager
import ai.respondo.sdk.push.PushRegistrationContext
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpBody
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

    private class FakeHost(
        private val foreground: Boolean = false,
        private val acceptsLinks: Boolean = true,
    ) : PushHost {
        val seen = HashSet<String>()
        val opened = mutableListOf<String>()
        val unhandled = mutableListOf<RespondoPushPayload>()
        val links = mutableListOf<String>()

        override fun hasSeenMessage(messageId: String): Boolean = seen.contains(messageId)
        override fun markMessageSeen(messageId: String) { seen.add(messageId) }
        override fun isConversationForeground(conversationId: String?): Boolean = foreground
        override fun openConversation(conversationId: String): Boolean {
            opened += conversationId
            return true
        }
        override fun openDeepLink(link: String): Boolean {
            links += link
            return acceptsLinks
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
    fun handlePush_campaignDeepLink_opensLink() = runTest {
        val host = FakeHost()
        val pm = PushManager(api(FakeHttpEngine { HttpResponse(200, "{}") }), host, this)
        val p = RespondoPushPayload.from(mapOf("respondo" to """{"type":"message","deep_link":"yourapp://orders/1"}"""))!!
        pm.handlePush(p)
        advanceUntilIdle()
        assertEquals(listOf("yourapp://orders/1"), host.links)
        assertTrue(host.unhandled.isEmpty())
        assertTrue(host.opened.isEmpty())
    }

    @Test
    fun handlePush_campaignDeepLink_notAccepted_reportsUnhandled() = runTest {
        val host = FakeHost(acceptsLinks = false)
        val pm = PushManager(api(FakeHttpEngine { HttpResponse(200, "{}") }), host, this)
        val p = RespondoPushPayload.from(mapOf("respondo" to """{"type":"message","deep_link":"yourapp://orders/1"}"""))!!
        pm.handlePush(p)
        advanceUntilIdle()
        assertEquals(1, host.unhandled.size)
    }

    private fun campaign(deliveryId: String) = RespondoPushPayload.from(
        mapOf("respondo" to """{"type":"campaign","message_id":"m-$deliveryId","delivery_id":"$deliveryId"}"""),
    )!!

    private fun FakeHttpEngine.opened() = requests.filter { it.url.contains("/api/v1/widget/push/opened") }

    /** Тап раньше токена (getInitialMessage быстрее getToken): бикон открытия уходит, когда токен пришёл. */
    @Test
    fun tapBeforeToken_reportsTheOpenWhenTheTokenArrives() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, "{}") }
        val pm = PushManager(api(engine), FakeHost(), this)
        pm.handlePush(campaign("d-1"))
        advanceUntilIdle()
        assertTrue("токена ещё нет", engine.opened().isEmpty())
        pm.setPushToken("t")
        advanceUntilIdle()
        val body = (engine.opened().single().body as HttpBody.Json).text
        assertTrue(body.contains("\"token\":\"t\""))
        assertTrue(body.contains("\"delivery_id\":\"d-1\""))
    }

    /** clearPushToken (выход) снимает биконы, ждавшие токена: следующий токен их не отправит. */
    @Test
    fun clearPushToken_dropsTheOpensWaitingForAToken() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, "{}") }
        val pm = PushManager(api(engine), FakeHost(), this)
        pm.handlePush(campaign("d-1"))
        pm.clearPushToken()
        pm.setPushToken("t")
        advanceUntilIdle()
        assertTrue(engine.opened().isEmpty())
    }

    /** Выход (reset): тапы прежнего пользователя, ждавшие токена, бикон не отправляют. */
    @Test
    fun dropPendingOpens_theOpensWaitingForATokenAreNotSent() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, "{}") }
        val pm = PushManager(api(engine), FakeHost(), this)
        pm.handlePush(campaign("d-1"))
        pm.dropPendingOpens()
        pm.setPushToken("t")
        advanceUntilIdle()
        assertTrue(engine.opened().isEmpty())
    }

    /** То же тело регистрации второй раз не уходит; сбой снимает отметку — повтор уходит. */
    @Test
    fun register_sameBodyOnce_failureAllowsARetry() = runTest {
        var status = 500
        val engine = FakeHttpEngine { HttpResponse(status, "{}") }
        val pm = PushManager(api(engine), FakeHost(), this)
        fun registers() = engine.requests.count { it.url.contains("/api/v1/widget/push/register") }
        pm.setPushToken("t")
        advanceUntilIdle()
        assertEquals(1, registers())
        status = 200
        pm.reRegister()
        advanceUntilIdle()
        assertEquals("после сбоя — повтор", 2, registers())
        pm.reRegister()
        advanceUntilIdle()
        assertEquals("то же тело — не повторяется", 2, registers())
    }

    @Test
    fun isRecognized_trueForRespondoPayload() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val pm = PushManager(api(FakeHttpEngine()), FakeHost(), scope)
        assertTrue(pm.isRecognized(payload("m")))
    }
}
