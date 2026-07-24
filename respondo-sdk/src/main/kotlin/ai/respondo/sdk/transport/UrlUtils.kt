package ai.respondo.sdk.transport

import java.net.URI
import java.net.URLEncoder

/** Утилиты построения URL и вывода WS-хоста из базового API-URL (аналог `detectWsOrigin` веба). */
internal object UrlUtils {

    /** Убирает хвостовой слэш базового URL. */
    fun normalizeBaseUrl(url: String): String = url.trimEnd('/')

    /**
     * Выводит WS-базу из базового API-URL:
     * - `https://api.respondo.ai` → `wss://wss.respondo.ai` (прод);
     * - произвольный host → тот же host со схемой `wss` (для https) / `ws` (для http), порт сохраняется.
     */
    fun detectWsBaseUrl(baseApiUrl: String): String {
        val uri = URI(normalizeBaseUrl(baseApiUrl))
        val host = uri.host ?: return "wss://wss.respondo.ai"
        if (host.equals("api.respondo.ai", ignoreCase = true)) {
            return "wss://wss.respondo.ai"
        }
        val wsScheme = if (uri.scheme.equals("http", ignoreCase = true)) "ws" else "wss"
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        return "$wsScheme://$host$portPart"
    }

    /** Собирает query-строку из пар, пропуская null/пустые значения и URL-кодируя ключи и значения. */
    fun query(vararg params: Pair<String, String?>): String {
        val parts = params.mapNotNull { (k, v) ->
            if (v.isNullOrEmpty()) null else "${enc(k)}=${enc(v)}"
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
