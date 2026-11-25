package ai.respondo.sdk.core

/** Инлайн-участок рич-разметки с набором стилей. [link] — только http(s), иначе null. */
internal data class RichSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** Блок рич-разметки: абзац, элемент списка или отступ абзаца. */
internal sealed interface RichBlock {
    val spans: List<RichSpan>

    data class Paragraph(override val spans: List<RichSpan>) : RichBlock
    data class Bullet(override val spans: List<RichSpan>) : RichBlock
    data class Ordered(val number: Int, override val spans: List<RichSpan>) : RichBlock

    /**
     * Пустая строка между абзацами markdown. Без неё абзац, отделённый пустой строкой, рисовался
     * вплотную, как простой перенос (тело макроса, 2026-10-30).
     */
    object Gap : RichBlock {
        override val spans: List<RichSpan> = emptyList()
    }
}

/** Результат разбора контента сообщения в блоки/спаны для нативного рендера. */
internal data class RichText(val blocks: List<RichBlock>) {
    /** Плоский текст без разметки (для тестов и деградации). */
    fun plainText(): String = blocks.joinToString("\n") { block -> block.spans.joinToString("") { it.text } }
}

/**
 * Разбор контента сообщения в рич-модель для рендера в Compose `AnnotatedString` — БЕЗ WebView и без
 * HTML-инъекций. Поддержка markdown-подмножества (жирный/курсив/инлайн-код/ссылки/списки) и безопасная
 * деградация html (`content_format="html"`) в текст с сохранением ссылок. Вход ограничен [MAX_INPUT]
 * символами (защита от гигантских пейлоадов; разбор линейный по длине входа).
 */
internal object MarkdownRenderer {

    /** Жёсткий кэп длины входа. Всё сверх — отбрасывается до разбора. */
    const val MAX_INPUT = 20_000

    fun render(content: String, isHtml: Boolean): RichText {
        val capped = if (content.length > MAX_INPUT) content.substring(0, MAX_INPUT) else content
        if (capped.isEmpty()) return RichText(emptyList())
        return if (isHtml) renderHtml(capped) else renderMarkdown(capped)
    }

    // ==================== markdown ====================

    private fun renderMarkdown(text: String): RichText {
        val blocks = ArrayList<RichBlock>()
        // Пустая строка после блока — отступ абзаца перед СЛЕДУЮЩИМ блоком: один на серию пустых
        // строк, и ни одного в начале или в конце сообщения.
        var pendingGap = false
        // Номер пункта: первый пункт серии — номер источника, следующие — +1 (паритет с iOS и
        // Flutter). Пустая строка серию НЕ рвёт — рыхлый список импорта «1. a\n\n2. b» держит
        // номера; рвёт любой другой блок.
        var lastOrdinal: Int? = null
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                if (blocks.isNotEmpty()) pendingGap = true
                continue
            }
            if (pendingGap) {
                blocks.add(RichBlock.Gap)
                pendingGap = false
            }
            // «\#» в начале строки — литерал «#», не заголовок: канонический вид тела хранит этот
            // экран, чтобы «\# of orders» не стал заголовком (паритет с iOS и Flutter).
            val escapedHeading = escapedHeadingText(line)
            if (escapedHeading != null) {
                lastOrdinal = null
                blocks.add(RichBlock.Paragraph(parseInline(escapedHeading)))
                continue
            }
            val trimmed = line.trimStart()
            val heading = headingContent(line)
            val quote = quoteContent(line)
            val bullet = bulletContent(trimmed)
            val ordered = orderedContent(trimmed)
            if (heading != null || quote != null || bullet != null || ordered == null) lastOrdinal = null
            when {
                // Заголовок — жирной строкой, без решёток (как Telegram, WhatsApp и почта).
                heading != null -> blocks.add(RichBlock.Paragraph(styled(parseInline(heading.replace("**", "")), bold = true)))
                // Цитата — строкой без маркера «> ».
                quote != null -> blocks.add(RichBlock.Paragraph(parseInline(quote)))
                bullet != null -> blocks.add(RichBlock.Bullet(parseInline(bullet)))
                ordered != null -> {
                    val number = lastOrdinal?.plus(1) ?: ordered.first
                    lastOrdinal = number
                    blocks.add(RichBlock.Ordered(number, parseInline(ordered.second)))
                }
                else -> blocks.add(RichBlock.Paragraph(parseInline(line)))
            }
        }
        return RichText(blocks)
    }

    /** Строка `\#…` (до трёх ведущих пробелов) — её текст без бэкслэша, иначе null. */
    private fun escapedHeadingText(line: String): String? {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        if (i + 1 >= line.length || line[i] != '\\' || line[i + 1] != '#') return null
        return line.substring(i + 1)
    }

    /** Текст ATX-заголовка (`#`…`######` + пробел, до трёх ведущих пробелов) без решёток, иначе null. */
    private fun headingContent(line: String): String? {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        var hashes = 0
        while (i < line.length && line[i] == '#' && hashes < 7) {
            i++; hashes++
        }
        if (hashes == 0 || hashes > 6 || i >= line.length || (line[i] != ' ' && line[i] != '\t')) return null
        // Закрывающие решётки («## Steps ##») — только после пробела: «## C#» остаётся «C#».
        val text = line.substring(i).trim().replace(HEADING_CLOSING, "").trim()
        return text.ifEmpty { null }
    }

    private val HEADING_CLOSING = Regex("[ \\t]+#+$")

    /** Текст строки цитаты (`>` + необязательный пробел, до трёх ведущих пробелов), иначе null. */
    private fun quoteContent(line: String): String? {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        if (i >= line.length || line[i] != '>') return null
        i++
        if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(i)
    }

    /** Возвращает текст элемента маркированного списка (`- `, `* `, `+ `) или null. */
    private fun bulletContent(line: String): String? {
        if (line.length < 2) return null
        val c = line[0]
        return if ((c == '-' || c == '*' || c == '+') && line[1] == ' ') line.substring(2).trim() else null
    }

    /** Возвращает (номер, текст) элемента нумерованного списка (`1. ` / `1) `) или null. */
    private fun orderedContent(line: String): Pair<Int, String>? {
        var i = 0
        while (i < line.length && i < 9 && line[i].isDigit()) i++
        if (i == 0 || i + 1 >= line.length) return null
        val sep = line[i]
        if ((sep != '.' && sep != ')') || line[i + 1] != ' ') return null
        val number = line.substring(0, i).toIntOrNull() ?: return null
        return number to line.substring(i + 2).trim()
    }

    /** Инлайн-разбор строки: `code`, **bold**, *italic*, [text](url), ![alt](url) — ссылкой, автоссылки http(s). */
    private fun parseInline(s: String): List<RichSpan> {
        val spans = ArrayList<RichSpan>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                spans.add(RichSpan(buf.toString()))
                buf.clear()
            }
        }

        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '`' -> {
                    val close = s.indexOf('`', i + 1)
                    if (close > i) {
                        flush()
                        spans.add(RichSpan(s.substring(i + 1, close), code = true))
                        i = close + 1
                    } else {
                        buf.append(c); i++
                    }
                }

                c == '*' && i + 1 < n && s[i + 1] == '*' -> {
                    val close = s.indexOf("**", i + 2)
                    if (close > i + 1) {
                        flush()
                        spans.addAll(styled(parseInline(s.substring(i + 2, close)), bold = true))
                        i = close + 2
                    } else {
                        buf.append(c); i++
                    }
                }

                c == '*' -> {
                    val close = s.indexOf('*', i + 1)
                    if (close > i + 1) {
                        flush()
                        spans.addAll(styled(parseInline(s.substring(i + 1, close)), italic = true))
                        i = close + 1
                    } else {
                        buf.append(c); i++
                    }
                }

                // Картинка ![alt](url) — ссылкой с подписью alt (пустой alt — адресом), без «!»:
                // картинки в пузыре SDK не рисует, а «!» перед ссылкой клиент читал буквально
                // (тела макросов несут картинки).
                c == '!' && i + 1 < n && s[i + 1] == '[' -> {
                    val link = tryParseLink(s, i + 1)
                    if (link != null) {
                        flush()
                        spans.addAll(link.spans)
                        i = link.next
                    } else {
                        buf.append(c); i++
                    }
                }

                c == '[' -> {
                    val link = tryParseLink(s, i)
                    if (link != null) {
                        flush()
                        spans.addAll(link.spans)
                        i = link.next
                    } else {
                        buf.append(c); i++
                    }
                }

                (c == 'h' || c == 'H') && startsWithUrl(s, i) && isUrlBoundary(s, i) -> {
                    val end = urlEnd(s, i)
                    val url = s.substring(i, end)
                    if (UrlSafety.isHttp(url)) {
                        flush()
                        spans.add(RichSpan(url, link = url))
                        i = end
                    } else {
                        buf.append(c); i++
                    }
                }

                else -> {
                    buf.append(c); i++
                }
            }
        }
        flush()
        return spans
    }

    private fun styled(inner: List<RichSpan>, bold: Boolean = false, italic: Boolean = false): List<RichSpan> =
        inner.map { it.copy(bold = it.bold || bold, italic = it.italic || italic) }

    private class ParsedLink(val spans: List<RichSpan>, val next: Int)

    private fun tryParseLink(s: String, start: Int): ParsedLink? {
        val closeBracket = s.indexOf(']', start + 1)
        if (closeBracket <= start || closeBracket + 1 >= s.length || s[closeBracket + 1] != '(') return null
        var closeParen = closeBracket + 2
        var depth = 0
        while (closeParen < s.length) {
            when (s[closeParen]) {
                '(' -> depth++
                ')' -> if (depth == 0) break else depth--
            }
            closeParen++
        }
        if (closeParen <= closeBracket + 1 || closeParen >= s.length) return null
        val label = s.substring(start + 1, closeBracket)
        val url = s.substring(closeBracket + 2, closeParen).trim()
        val labelSpans = parseInline(label).ifEmpty { listOf(RichSpan(url)) }
        // Небезопасная схема — рендерим только текст ярлыка, без активной ссылки.
        val withLink = if (UrlSafety.isHttp(url)) labelSpans.map { it.copy(link = it.link ?: url) } else labelSpans
        return ParsedLink(withLink, closeParen + 1)
    }

    private fun startsWithUrl(s: String, i: Int): Boolean =
        s.startsWith("http://", i, ignoreCase = true) || s.startsWith("https://", i, ignoreCase = true)

    private fun isUrlBoundary(s: String, i: Int): Boolean {
        if (i == 0) return true
        val previous = s[i - 1]
        return !previous.isLetterOrDigit() && previous !in "_/@.+-:$%\\\"'"
    }

    private fun urlEnd(s: String, start: Int): Int {
        var j = start
        val open = ArrayDeque<Char>()
        while (j < s.length) {
            val c = s[j]
            if (c.isWhitespace() || c in "<>\"'`*") break
            when (c) {
                '(', '[', '{' -> open.addLast(c)
                ')', ']', '}' -> {
                    val expected = when (c) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (open.lastOrNull() != expected) break
                    open.removeLast()
                }
            }
            j++
        }
        // Хвостовую пунктуацию не втягиваем в ссылку.
        while (j > start && s[j - 1] in ".,;:!?…") j--
        return j
    }

    // ==================== html → безопасный текст ====================

    private fun renderHtml(html: String): RichText {
        val blocks = ArrayList<RichBlock>()
        var spans = ArrayList<RichSpan>()
        val text = StringBuilder()
        var bold = false
        var italic = false
        var code = false
        var link: String? = null

        fun flushText() {
            if (text.isNotEmpty()) {
                val raw = text.toString()
                if (link == null && !code) {
                    spans.addAll(
                        parseBareUrls(raw).map { span ->
                            span.copy(bold = bold, italic = italic)
                        },
                    )
                } else {
                    spans.add(RichSpan(raw, bold = bold, italic = italic, code = code, link = link))
                }
                text.clear()
            }
        }

        fun flushBlock() {
            flushText()
            if (spans.isNotEmpty()) {
                blocks.add(RichBlock.Paragraph(spans))
                spans = ArrayList()
            }
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val close = html.indexOf('>', i + 1)
                if (close == -1) break // незакрытый тег — безопасно отбрасываем остаток
                val rawTag = html.substring(i + 1, close).trim()
                val lower = rawTag.lowercase()
                // Содержимое script/style вырезаем целиком.
                if (lower.startsWith("script")) {
                    val end = html.indexOf("</script", close, ignoreCase = true)
                    i = if (end == -1) n else (html.indexOf('>', end).takeIf { it != -1 }?.plus(1) ?: n)
                    continue
                }
                if (lower.startsWith("style")) {
                    val end = html.indexOf("</style", close, ignoreCase = true)
                    i = if (end == -1) n else (html.indexOf('>', end).takeIf { it != -1 }?.plus(1) ?: n)
                    continue
                }
                val closing = lower.startsWith("/")
                val name = lower.removePrefix("/").substringBefore(' ').substringBefore('/')
                when (name) {
                    "b", "strong" -> {
                        flushText(); bold = !closing
                    }
                    "i", "em" -> {
                        flushText(); italic = !closing
                    }
                    "code" -> {
                        flushText(); code = !closing
                    }
                    "a" -> {
                        flushText()
                        link = if (closing) null else hrefOf(rawTag)?.takeIf { UrlSafety.isHttp(it) }
                    }
                    "br" -> flushBlock()
                    "p", "div", "li", "ul", "ol", "blockquote", "pre", "tr", "h1", "h2", "h3", "h4", "h5", "h6" -> flushBlock()
                    // прочие теги — отбрасываем разметку, текст сохраняем
                }
                i = close + 1
            } else if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                if (semi != -1 && semi - i <= 10) {
                    text.append(decodeEntity(html.substring(i + 1, semi)))
                    i = semi + 1
                } else {
                    text.append('&'); i++
                }
            } else {
                text.append(c); i++
            }
        }
        flushBlock()
        return RichText(blocks)
    }

    private fun hrefOf(tag: String): String? {
        val idx = tag.indexOf("href", ignoreCase = true).takeIf { it >= 0 } ?: return null
        val eq = tag.indexOf('=', idx).takeIf { it >= 0 } ?: return null
        var j = eq + 1
        while (j < tag.length && tag[j].isWhitespace()) j++
        if (j >= tag.length) return null
        val quote = tag[j]
        return if (quote == '"' || quote == '\'') {
            val end = tag.indexOf(quote, j + 1).takeIf { it >= 0 } ?: return null
            tag.substring(j + 1, end).trim()
        } else {
            var k = j
            while (k < tag.length && !tag[k].isWhitespace() && tag[k] != '>') k++
            tag.substring(j, k).trim()
        }
    }

    /** Автоссылки в plain-тексте HTML-ветки (backend оставляет content_format=html
     * после downgrade, а сам content отдаёт без тегов). Markdown-разметку здесь
     * намеренно не разбираем — HTML-стили уже применены выше. */
    private fun parseBareUrls(text: String): List<RichSpan> {
        val spans = ArrayList<RichSpan>()
        var i = 0
        var plainStart = 0
        while (i < text.length) {
            if ((text[i] == 'h' || text[i] == 'H') && startsWithUrl(text, i) && isUrlBoundary(text, i)) {
                val end = urlEnd(text, i)
                val url = text.substring(i, end)
                if (UrlSafety.isHttp(url)) {
                    if (plainStart < i) spans.add(RichSpan(text.substring(plainStart, i)))
                    spans.add(RichSpan(url, link = url))
                    i = end
                    plainStart = end
                    continue
                }
            }
            i++
        }
        if (plainStart < text.length) spans.add(RichSpan(text.substring(plainStart)))
        return if (spans.isEmpty()) listOf(RichSpan(text)) else spans
    }

    private fun decodeEntity(raw: String): String = when (raw.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos", "#39" -> "'"
        "nbsp" -> " "
        else -> {
            if (raw.startsWith("#")) {
                val code = if (raw.startsWith("#x", ignoreCase = true)) {
                    raw.substring(2).toIntOrNull(16)
                } else {
                    raw.substring(1).toIntOrNull()
                }
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else "&$raw;"
            } else {
                "&$raw;"
            }
        }
    }
}
