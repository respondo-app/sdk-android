package ai.respondo.sdk.internal

import kotlinx.serialization.json.Json

/**
 * Единый JSON-парсер SDK.
 *
 * - [Json.ignoreUnknownKeys] — бэкенд может добавлять поля (additionalProperties=true в схемах);
 * - [Json.explicitNulls] `= false` — null-поля не сериализуются в исходящих телах (напр. пустой session_token опускается);
 * - [Json.isLenient] и [Json.coerceInputValues] — терпимость к нестрогому JSON и значениям по умолчанию.
 */
internal val respondoJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
    encodeDefaults = false
}
