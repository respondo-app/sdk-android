package ai.respondo.sdk.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Конвертирует произвольные значения свойств `track` в [JsonElement] (строки, числа, булевы, списки, карты). */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in this@toJsonElement) put(k.toString(), v.toJsonElement())
    }
    is Iterable<*> -> buildJsonArray {
        for (v in this@toJsonElement) add(v.toJsonElement())
    }
    else -> JsonPrimitive(toString())
}
