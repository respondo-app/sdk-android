package ai.respondo.sdk.i18n

import ai.respondo.sdk.internal.RespondoLog
import ai.respondo.sdk.internal.respondoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Локализованные строки чат-UI. Загружаются из встроенных ресурсов:
 * `/respondo/i18n/strings.json` (ключи веб-словаря × 8 локалей) и
 * `/respondo/i18n/ui-strings-new.json` (новые нативные ключи SDK, переведены на все 8 локалей).
 * Фолбэк на `en` для любого отсутствующего ключа/локали.
 */
object Strings {
    /** Поддерживаемые локали UI (как UI_STRINGS веба). */
    val SUPPORTED = setOf("ru", "uk", "de", "fr", "es", "pt", "pl", "en")

    private const val FALLBACK = "en"

    // locale -> (key -> value)
    private val table: Map<String, Map<String, String>> by lazy { loadAll() }

    /** Значение ключа для локали с фолбэком на `en`, затем на сам ключ (диагностический дефолт). */
    fun get(lang: String?, key: String): String {
        val normalized = normalize(lang)
        table[normalized]?.get(key)?.let { return it }
        table[FALLBACK]?.get(key)?.let { return it }
        return key
    }

    /** Приводит BCP-47 к поддерживаемому базовому коду; неизвестное → `en`. */
    fun normalize(lang: String?): String {
        val base = lang?.substringBefore('-')?.lowercase() ?: return FALLBACK
        return if (base in SUPPORTED) base else FALLBACK
    }

    private fun loadAll(): Map<String, Map<String, String>> {
        val merged = HashMap<String, HashMap<String, String>>()
        mergeInto(merged, "/respondo/i18n/strings.json")
        mergeInto(merged, "/respondo/i18n/ui-strings-new.json")
        return merged
    }

    private fun mergeInto(target: HashMap<String, HashMap<String, String>>, resourcePath: String) {
        val stream = Strings::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            RespondoLog.w("ресурс строк не найден: $resourcePath")
            return
        }
        val text = stream.bufferedReader().use { it.readText() }
        val root = respondoJson.parseToJsonElement(text).jsonObject
        for ((locale, value) in root) {
            if (locale == "_meta") continue
            val obj = value as? JsonObject ?: continue
            val map = target.getOrPut(locale) { HashMap() }
            for ((key, v) in obj) {
                val str = (v as? JsonPrimitive)?.contentOrNull ?: continue
                // strings.json грузится первым; новые ключи не перетирают существующие переводы.
                map.putIfAbsent(key, str)
            }
        }
    }
}
