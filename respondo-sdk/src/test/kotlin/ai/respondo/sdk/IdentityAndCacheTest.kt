package ai.respondo.sdk

import ai.respondo.sdk.config.ConfigStore
import ai.respondo.sdk.core.CachedConversation
import ai.respondo.sdk.core.ChatMessage
import ai.respondo.sdk.core.ConversationCache
import ai.respondo.sdk.core.MessageRole
import ai.respondo.sdk.identity.IdentityStore
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IdentityStore, ConversationCache (TTL, revoke-скан) и ConfigStore. */
class IdentityAndCacheTest {

    @Test
    fun visitorId_isStableAndPrefixed() {
        val store = InMemoryKeyValueStore()
        val identity = IdentityStore(store)
        val first = identity.visitorId()
        assertTrue(first.startsWith("v_"))
        assertEquals(first, identity.visitorId())
    }

    @Test
    fun regenerateVisitor_changesId() {
        val identity = IdentityStore(InMemoryKeyValueStore())
        val first = identity.visitorId()
        val second = identity.regenerateVisitor()
        assertNotEquals(first, second)
        assertEquals(second, identity.visitorId())
    }

    @Test
    fun langAndEmail_persist() {
        val identity = IdentityStore(InMemoryKeyValueStore())
        identity.lang = "ru"
        assertEquals("ru", identity.lang)
        identity.collectedEmail = "a@b.co"
        assertEquals("a@b.co", identity.collectedEmail)
    }

    @Test
    fun wipeAll_clearsRespondoKeys() {
        val store = InMemoryKeyValueStore()
        val identity = IdentityStore(store)
        identity.visitorId()
        identity.lang = "de"
        store.putString("respondoai_custom", "x")
        identity.wipeAll()
        assertTrue(store.keys().none { it.startsWith("respondoai_") })
    }

    @Test
    fun conversationCache_ttlExpires() {
        val store = InMemoryKeyValueStore()
        var now = 1_000L
        val cache = ConversationCache(store) { now }
        cache.save("agent", "channel", CachedConversation(conversationId = "cid", sessionToken = "tok"))
        assertTrue(cache.load("agent", "channel") != null)
        now += ConversationCache.TTL_MS
        assertNull(cache.load("agent", "channel"))
    }

    @Test
    fun conversationCache_trimsToMax() {
        val cache = ConversationCache(InMemoryKeyValueStore())
        val many = (1..80).map { ChatMessage(id = "id-$it", role = MessageRole.USER, content = "m$it") }
        cache.save("a", "c", CachedConversation(messages = many))
        val loaded = cache.load("a", "c")
        assertEquals(ConversationCache.MAX_MESSAGES, loaded?.messages?.size)
    }

    @Test
    fun allConversationsWithToken_findsRevocableSessions() {
        val store = InMemoryKeyValueStore()
        val cache = ConversationCache(store)
        cache.save("agent", "channel", CachedConversation(conversationId = "cid-1", sessionToken = "tok-1"))
        val pairs = cache.allConversationsWithToken()
        assertEquals(1, pairs.size)
        assertEquals("cid-1" to "tok-1", pairs.first())
    }

    @Test
    fun configStore_roundtripAndTtl() {
        val store = InMemoryKeyValueStore()
        var now = 0L
        val cfg = ConfigStore(store) { now }
        cfg.save("agent", "channel", "ru", WidgetConfigDto(title = "Поддержка"))
        assertEquals("Поддержка", cfg.load("agent", "channel", "ru")?.title)
        now += ConfigStore.TTL_MS
        assertNull(cfg.load("agent", "channel", "ru"))
    }

    @Test
    fun conversationCache_absentReturnsNull() {
        val cache = ConversationCache(InMemoryKeyValueStore())
        assertNull(cache.load("x", "y"))
    }

    @Test
    fun seenFlags_persist() {
        val identity = IdentityStore(InMemoryKeyValueStore())
        assertFalse(identity.isChatSeen("m1"))
        identity.markChatSeen("m1")
        assertTrue(identity.isChatSeen("m1"))
    }
}
