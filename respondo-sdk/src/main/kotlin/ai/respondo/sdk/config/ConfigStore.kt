package ai.respondo.sdk.config

import ai.respondo.sdk.identity.StorageKeys
import ai.respondo.sdk.internal.KeyValueStore
import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import ai.respondo.sdk.transport.dto.WidgetConfigDto
import kotlinx.serialization.Serializable

/** Кэшированный конфиг виджета со штампом времени (для TTL). */
@Serializable
private data class CachedConfig(
    val config: WidgetConfigDto,
    val timestamp: Long,
)

/**
 * Кэш конфига виджета с TTL 24ч. Позволяет мгновенно применить тему при повторном `init` до ответа сети,
 * а затем обновить её пришедшим свежим конфигом. office_hours/visitor_language из кэша считаются устаревшими,
 * поэтому UI дожидается свежего конфига для показа плашки офис-часов.
 */
class ConfigStore(
    private val store: KeyValueStore,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun load(agentId: String?, channelId: String?, lang: String?): WidgetConfigDto? {
        val raw = store.getString(StorageKeys.config(agentId, channelId, lang)) ?: return null
        return try {
            val cached = respondoJson.decodeFromString(CachedConfig.serializer(), raw)
            if (nowProvider() - cached.timestamp >= TTL_MS) null else cached.config
        } catch (t: Throwable) {
            RespondoLog.w("не удалось разобрать кэш конфига", t)
            null
        }
    }

    fun save(agentId: String?, channelId: String?, lang: String?, config: WidgetConfigDto) {
        store.putString(
            StorageKeys.config(agentId, channelId, lang),
            respondoJson.encodeToString(CachedConfig.serializer(), CachedConfig(config, nowProvider())),
        )
    }

    companion object {
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
