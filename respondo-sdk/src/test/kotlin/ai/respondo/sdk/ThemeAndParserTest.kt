package ai.respondo.sdk

import ai.respondo.sdk.core.ChatMessage
import ai.respondo.sdk.core.MessageRole
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.core.UnreadTracker
import ai.respondo.sdk.core.isEchoedUserRole
import ai.respondo.sdk.i18n.LocaleDetect
import ai.respondo.sdk.i18n.Strings
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.realtime.WsParser
import ai.respondo.sdk.theme.BrandColor
import ai.respondo.sdk.theme.ResolvedTheme
import ai.respondo.sdk.theme.ThemeMapper
import ai.respondo.sdk.transport.dto.MessageDto
import ai.respondo.sdk.transport.dto.MessageMetadataDto
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тема/контраст, парсер ролей, unread и детект локали. */
class ThemeAndParserTest {

    // --- BrandColor ---

    @Test
    fun parseHex_producesArgb() {
        assertEquals(0xFF2563EBL, BrandColor.parseHex("#2563EB", 0))
        assertEquals(0xFF000000L, BrandColor.parseHex("#000000", 0))
        assertEquals(42L, BrandColor.parseHex("bad", 42))
    }

    @Test
    fun onPrimary_picksContrast() {
        assertEquals(BrandColor.WHITE, BrandColor.onPrimary(0xFF2563EB)) // тёмно-синий → белый текст
        assertEquals(BrandColor.INK, BrandColor.onPrimary(0xFFFFFFFF)) // белый фон → тёмный текст
    }

    @Test
    fun linkInk_usesBrandOnLight() {
        assertEquals(0xFF2563EBL, BrandColor.linkInk(0xFF2563EB))
        assertEquals(BrandColor.INK, BrandColor.linkInk(0xFFFFFFFF))
    }

    // --- ThemeMapper ---

    @Test
    fun themeMapper_defaults() {
        val theme = ThemeMapper.resolve(null, null)
        assertEquals(ResolvedTheme.DEFAULT_PRIMARY, theme.primaryColorArgb)
        assertEquals("Support", theme.title)
        assertEquals(12, theme.cornerRadiusDp)
        assertEquals(0.75f, theme.initialDetent)
    }

    @Test
    fun themeMapper_overrideWinsOverConfig() {
        val config = WidgetConfigDto(primaryColor = "#111111", title = "FromBackend")
        val theme = ThemeMapper.resolve(config, RespondoTheme(primaryColor = 0xFFAABBCC, title = "Override"))
        assertEquals(0xFFAABBCCL, theme.primaryColorArgb)
        assertEquals("Override", theme.title)
    }

    @Test
    fun themeMapper_clampsRadius() {
        val theme = ThemeMapper.resolve(WidgetConfigDto(borderRadius = 999.0), null)
        assertEquals(ResolvedTheme.MAX_RADIUS, theme.cornerRadiusDp)
    }

    // --- WsParser / роли ---

    @Test
    fun normalizeRole_mapsAgentToAssistant() {
        assertEquals(MessageRole.ASSISTANT, ChatMessage.normalizeRole("agent"))
        assertEquals(MessageRole.ASSISTANT, ChatMessage.normalizeRole("assistant"))
        assertEquals(MessageRole.SYSTEM, ChatMessage.normalizeRole("system"))
        assertEquals(MessageRole.USER, ChatMessage.normalizeRole("visitor"))
    }

    @Test
    fun echoedUserRole_filteredExceptSurvey() {
        assertTrue(MessageDto(id = "1", role = "user", content = "x").isEchoedUserRole())
        assertTrue(MessageDto(id = "2", role = "visitor", content = "x").isEchoedUserRole())
        assertFalse(MessageDto(id = "3", role = "assistant", content = "x").isEchoedUserRole())
        val survey = MessageDto(id = "4", role = "user", content = "x", metadata = MessageMetadataDto(surveyStep = kotlinx.serialization.json.JsonObject(emptyMap())))
        assertFalse(survey.isEchoedUserRole())
    }

    @Test
    fun wsParser_parsesErrorAndCampaign() {
        val err = WsParser.parse("""{"type":"error","data":{"message":"forbidden"}}""")
        assertTrue(err is RealtimeEvent.Error)
        assertEquals("forbidden", (err as RealtimeEvent.Error).message)

        val campaign = WsParser.parse("""{"type":"campaign_conversation","conversation_id":"c1"}""")
        assertTrue(campaign is RealtimeEvent.CampaignConversation)
    }

    @Test
    fun wsParser_ignoresTooltipCatalog() {
        val ev = WsParser.parse("""{"type":"tooltip.catalog","data":{"tooltips":[]}}""")
        assertTrue(ev is RealtimeEvent.Ignored)
    }

    // --- UnreadTracker ---

    @Test
    fun unread_countsDistinctIds() {
        val t = UnreadTracker()
        assertEquals(1, t.onIncomingWhileClosed("m1"))
        assertEquals(1, t.onIncomingWhileClosed("m1")) // дубль по id
        assertEquals(2, t.onIncomingWhileClosed("m2"))
        assertEquals(0, t.reset())
    }

    // --- LocaleDetect ---

    @Test
    fun localeDetect_priority() {
        assertEquals("ru", LocaleDetect.detect(storedLang = "ru-RU", firstUserText = "hello", systemLocale = "en"))
        assertEquals("uk", LocaleDetect.detect(storedLang = null, firstUserText = "Привіт, як справи?", systemLocale = "en"))
        assertEquals("ru", LocaleDetect.detect(storedLang = null, firstUserText = "Здравствуйте", systemLocale = "en"))
        assertEquals("en", LocaleDetect.detect(storedLang = null, firstUserText = null, systemLocale = "xx"))
        assertEquals("de", LocaleDetect.detect(storedLang = null, firstUserText = null, systemLocale = "de-DE"))
    }

    // --- лимит сообщения (750) ---

    @Test
    fun messageLimit_constAndLocalizedString() {
        assertEquals(750, RespondoController.MAX_MESSAGE_LENGTH)
        // Английское значение ключа — источник (en в ui-strings-new.json).
        val en = Strings.get("en", "messageTooLong")
        assertEquals("Messages are limited to 750 characters.", en)
        // Русское значение переведено (Opus): непустое, отличается от английского и упоминает лимит 750.
        val ru = Strings.get("ru", "messageTooLong")
        assertTrue(ru.isNotBlank())
        assertFalse(ru == en)
        assertTrue(ru.contains("750"))
    }
}
