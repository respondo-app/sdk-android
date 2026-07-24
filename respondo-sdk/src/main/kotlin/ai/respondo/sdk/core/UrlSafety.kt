package ai.respondo.sdk.core

/**
 * Проверка безопасности схемы URL для внешнего открытия. Разрешаем только `http`/`https`: остальные
 * схемы (`javascript:`, `data:`, `file:`, кастомные) во внешний Intent/браузер не пускаем — это отсекает
 * XSS-подобные и локальные ссылки из контента бэкенда, источников, doc-links, ticket-URL и разметки.
 */
internal object UrlSafety {
    fun isHttp(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
    }
}
