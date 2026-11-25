package ai.respondo.sdk.core

import java.net.URI

/**
 * Проверка безопасности схемы URL для внешнего открытия. Разрешаем только `http`/`https`: остальные
 * схемы (`javascript:`, `data:`, `file:`, кастомные) во внешний Intent/браузер не пускаем — это отсекает
 * XSS-подобные и локальные ссылки из контента бэкенда, источников, doc-links и разметки.
 *
 * Проверяется схема, абсолютность и наличие host. Это не решение о том, можно ли показывать ссылку
 * клиенту: `https://example.com` проходит здесь и у staff-интерфейса чужого хелпдеска. Раньше на этой проверке держалась карточка
 * эскалации, куда подставлялся URL Zendesk-тикета арендатора, — такие ссылки клиенту не показываются
 * вовсе, и отсеиваются они на уровне DTO (поля просто нет), а не здесь.
 */
internal object UrlSafety {
    fun isHttp(url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (u.isEmpty() || u.contains('\\') || u.any { it <= '\u0020' || it == '\u007f' }) return false
        val parsed = runCatching { URI(u) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !parsed.host.isNullOrEmpty()
    }
}
