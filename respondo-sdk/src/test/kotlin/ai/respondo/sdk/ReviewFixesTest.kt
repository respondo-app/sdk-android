package ai.respondo.sdk

import ai.respondo.sdk.core.ChatMessage
import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.MessageRole
import ai.respondo.sdk.core.SendStatus
import ai.respondo.sdk.core.isBackendMessage
import ai.respondo.sdk.realtime.RealtimeChannel
import ai.respondo.sdk.realtime.RealtimeCredentials
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Регрессионные тесты фиксов волны ревью №1 Android SDK (чистая логика оркестратора + интервалы поллинга). */
class ReviewFixesTest {

    // --- P2-2: индикатор «печатает» гаснет при is_typing=false ---

    @Test
    fun typingState_falseClearsIndicator() {
        assertNull(ControllerLogic.typingState(isTyping = false, authorName = "Анна"))
        assertEquals("Анна", ControllerLogic.typingState(isTyping = true, authorName = "Анна")?.authorName)
    }

    // --- P2-5: resume сохраняет локальные оптимистичные сообщения ---

    @Test
    fun mergeResumeMessages_preservesPendingLocalMessages() {
        val restored = listOf(
            ChatMessage(id = "aaaa-bbbb-cccc", role = MessageRole.ASSISTANT, content = "с бэкенда"),
        )
        val current = listOf(
            ChatMessage(id = "17001", role = MessageRole.USER, content = "черновик", sendStatus = SendStatus.SENDING, isLocal = true),
            ChatMessage(id = "17002", role = MessageRole.USER, content = "упало", sendStatus = SendStatus.FAILED, isLocal = true),
            ChatMessage(id = "17003", role = MessageRole.USER, content = "подтверждённое", sendStatus = SendStatus.SENT, isLocal = false),
        )
        val merged = ControllerLogic.mergeResumeMessages(restored, current)
        // Восстановленное + два незавершённых локальных; отправленное/подтверждённое не дублируем.
        assertEquals(listOf("aaaa-bbbb-cccc", "17001", "17002"), merged.map { it.id })
    }

    @Test
    fun mergeResumeMessages_dropsPendingAlreadyInRestored() {
        val restored = listOf(
            ChatMessage(id = "17001", role = MessageRole.USER, content = "уже на бэкенде", isLocal = false),
        )
        val current = listOf(
            ChatMessage(id = "17001", role = MessageRole.USER, content = "оптимистика", sendStatus = SendStatus.SENDING, isLocal = true),
        )
        assertEquals(listOf("17001"), ControllerLogic.mergeResumeMessages(restored, current).map { it.id })
    }

    // --- P2-6: lastBackendId игнорирует локальные системные карточки ---

    @Test
    fun lastBackendId_ignoresLocalCards() {
        val messages = listOf(
            ChatMessage(id = "aaaa-bbbb-1111", role = MessageRole.ASSISTANT, content = "ответ", isLocal = false),
            ChatMessage(id = "escalate-card", role = MessageRole.SYSTEM, content = "эскалация", isLocal = true),
            ChatMessage(id = "resolved-card", role = MessageRole.SYSTEM, content = "закрыто", isLocal = true),
        )
        assertEquals("aaaa-bbbb-1111", ControllerLogic.lastBackendId(messages))
    }

    @Test
    fun isBackendMessage_distinguishesCardsFromServerMessages() {
        assertTrue(ChatMessage(id = "aaaa-bbbb", role = MessageRole.ASSISTANT, content = "x", isLocal = false).isBackendMessage())
        assertFalse(ChatMessage(id = "escalate-card", role = MessageRole.SYSTEM, content = "x", isLocal = true).isBackendMessage())
        assertFalse(ChatMessage(id = "17001", role = MessageRole.USER, content = "x", isLocal = true).isBackendMessage())
    }

    // --- P2-1: бейдж кампании не удваивается ---

    @Test
    fun campaignBadgeShouldBump_onlyForBodylessClosedFreshCampaign() {
        // Есть тело сообщения → бейджем занимается onNewMessageEvent, здесь не поднимаем.
        assertFalse(ControllerLogic.campaignBadgeShouldBump(hasDto = true, chatOpen = false, alreadyAdopted = false))
        // Чат открыт → не поднимаем.
        assertFalse(ControllerLogic.campaignBadgeShouldBump(hasDto = false, chatOpen = true, alreadyAdopted = false))
        // Повторный кадр той же кампании → не поднимаем (дедуп).
        assertFalse(ControllerLogic.campaignBadgeShouldBump(hasDto = false, chatOpen = false, alreadyAdopted = true))
        // Свежая парковка при закрытом чате → поднимаем ровно один раз.
        assertTrue(ControllerLogic.campaignBadgeShouldBump(hasDto = false, chatOpen = false, alreadyAdopted = false))
    }

    // --- P2-3: интервал REST-поллинга ускоряется при эскалации ---

    @Test
    fun pollingInterval_speedsUpWhenEscalated() {
        val channel = RealtimeChannel(
            agentId = "agent-1",
            apiClient = ApiClient("https://example.test", FakeHttpEngine()),
            okHttpClient = OkHttpClient(),
            credentialsProvider = {
                RealtimeCredentials(
                    visitorId = "v_test",
                    channelId = null,
                    sessionToken = null,
                    userHash = null,
                    email = null,
                    userId = null,
                    lang = null,
                    conversationId = null,
                    lastBackendMsgId = null,
                )
            },
            onEvent = {},
        )
        channel.panelOpen = true
        channel.escalated = true
        assertEquals(RealtimeChannel.ESCALATED_POLL_MS, channel.pollingInterval())
        channel.escalated = false
        assertEquals(RealtimeChannel.OPEN_POLL_MS, channel.pollingInterval())
        channel.panelOpen = false
        assertEquals(RealtimeChannel.BACKGROUND_POLL_MS, channel.pollingInterval())
    }
}
