package ai.respondo.sdk

import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.realtime.RealtimeEvent
import ai.respondo.sdk.realtime.WsParser
import ai.respondo.sdk.support.Fixtures
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.ChatResponseDto
import ai.respondo.sdk.transport.dto.HistoryResponseDto
import ai.respondo.sdk.transport.dto.ResumeResponseDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор golden-снимков legacy-контракта чата (каталог `fixtures/contract`) в модели SDK.
 * Копии держатся идентичными `sdks/spec/fixtures/contract` тестом бэкенда
 * `TestChatContractFixtureCopiesInSync`.
 */
class ContractGoldenParsingTest {

    private fun golden(name: String): JsonObject =
        respondoJson.parseToJsonElement(Fixtures.read("contract/$name.json")).jsonObject

    private fun chatResponse(element: JsonElement): ChatResponseDto =
        respondoJson.decodeFromJsonElement(ChatResponseDto.serializer(), element)

    private fun assertChat(dto: ChatResponseDto) {
        assertFalse(dto.conversationId.isNullOrEmpty())
        dto.message?.let { assertTrue(it.role.isNotEmpty()) }
    }

    private fun frames(name: String): List<JsonObject> =
        golden(name)["frames"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun syncAiFirst_parsesAsChatResponse() {
        val dto = chatResponse(golden("sync-ai-first")["body"]!!)
        assertChat(dto)
        assertNotNull(dto.message)
    }

    @Test
    fun syncAiProducts_parsesAsChatResponse() {
        val dto = chatResponse(golden("sync-ai-products")["body"]!!)
        assertChat(dto)
        assertNotNull(dto.message)
    }

    @Test
    fun syncAiHandover_parsesAsChatResponse() {
        val dto = chatResponse(golden("sync-ai-handover")["body"]!!)
        assertChat(dto)
        assertTrue(dto.humanHandover)
    }

    @Test
    fun syncHumansOnly_parsesAsChatResponse() {
        val dto = chatResponse(golden("sync-humans-only")["body"]!!)
        assertChat(dto)
        assertTrue(dto.humanHandover)
    }

    @Test
    fun uploadAttachment_chatPartParsesAsChatResponse() {
        assertChat(chatResponse(golden("upload-attachment")["chat"]!!.jsonObject["body"]!!))
    }

    @Test
    fun uploadAttachment_uploadBodyParsesAsAttachment() {
        val body = golden("upload-attachment")["upload"]!!.jsonObject["body"]!!
        val dto = respondoJson.decodeFromJsonElement(ChatAttachmentDto.serializer(), body)
        assertTrue(dto.id.isNotEmpty())
        assertTrue(dto.filename.isNotEmpty())
        assertTrue(dto.url.isNotEmpty())
    }

    @Test
    fun testChat_parsesAsChatResponse() {
        val dto = chatResponse(golden("test-chat")["body"]!!)
        assertChat(dto)
        assertNotNull(dto.message)
    }

    @Test
    fun resume_parsesAsResumeResponse() {
        val dto = respondoJson.decodeFromJsonElement(ResumeResponseDto.serializer(), golden("resume")["body"]!!)
        assertFalse(dto.conversationId.isNullOrEmpty())
        assertTrue(dto.messages.isNotEmpty())
    }

    @Test
    fun history_parsesAsHistoryResponse() {
        val dto = respondoJson.decodeFromJsonElement(HistoryResponseDto.serializer(), golden("history")["body"]!!)
        assertTrue(dto.messages.isNotEmpty())
        assertTrue(dto.messages.all { it.role.isNotEmpty() })
    }

    @Test
    fun wsNewMessage_frameParsesAsNewMessage() {
        val events = frames("ws-new-message")
            .filter { it["type"]!!.jsonPrimitive.content == "new_message" }
            .map { WsParser.parse(it.toString()) }
        assertTrue(events.isNotEmpty())
        events.forEach { assertTrue(it is RealtimeEvent.NewMessage) }
    }

    @Test
    fun wsAck_messageUpdatedFrameParses() {
        val events = frames("ws-ack")
            .filter { it["type"]!!.jsonPrimitive.content == "message_updated" }
            .map { WsParser.parse(it.toString()) }
        assertTrue(events.isNotEmpty())
        events.forEach { assertTrue(it is RealtimeEvent.MessageUpdated) }
    }
}
