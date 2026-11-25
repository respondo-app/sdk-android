package ai.respondo.sdk

import ai.respondo.sdk.core.MarkdownRenderer
import ai.respondo.sdk.core.RichBlock
import ai.respondo.sdk.core.RichSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты рич-парсера контента сообщений (markdown + безопасная деградация html), включая злые входы. */
class MarkdownRendererTest {

    private fun spans(content: String, isHtml: Boolean = false): List<RichSpan> =
        MarkdownRenderer.render(content, isHtml).blocks.flatMap { it.spans }

    // --- markdown: инлайн-стили ---

    @Test
    fun markdown_boldItalicCode() {
        val s = spans("**b** *i* `c`")
        assertEquals("b", s.first { it.bold }.text)
        assertEquals("i", s.first { it.italic }.text)
        assertEquals("c", s.first { it.code }.text)
    }

    @Test
    fun markdown_linkHttpIsClickable() {
        val s = spans("go [site](https://a.b/x) now")
        val link = s.first { it.link != null }
        assertEquals("site", link.text)
        assertEquals("https://a.b/x", link.link)
    }

    @Test
    fun markdown_autolinkStripsTrailingPunctuation() {
        val s = spans("visit https://foo.bar!")
        val link = s.first { it.link != null }
        assertEquals("https://foo.bar", link.text)
        assertEquals("https://foo.bar", link.link)
    }

    @Test
    fun markdown_autolinkAcceptsUppercaseSchemeAndPreservesPath() {
        val s = spans("HTTP://EXAMPLE.COM/a_b")
        assertEquals("HTTP://EXAMPLE.COM/a_b", s.single { it.link != null }.link)
    }

    @Test
    fun markdown_autolinkRequiresValidHttpHostAndTokenBoundary() {
        val s = spans("prefixhttps://example.com javascript:https://example.com http://")
        assertTrue(s.none { it.link != null })
        assertEquals(
            "prefixhttps://example.com javascript:https://example.com http://",
            s.joinToString("") { it.text },
        )
    }

    @Test
    fun markdown_autolinkKeepsBalancedParentheses() {
        val s = spans("see https://example.com/a_(b))")
        val link = s.first { it.link != null }
        assertEquals("https://example.com/a_(b)", link.text)
        assertEquals(")", s.last().text)
    }

    @Test
    fun markdownLinkKeepsBalancedParenthesesInDestination() {
        val s = spans("[docs](https://example.com/a_(b))")
        assertEquals("docs", s.single().text)
        assertEquals("https://example.com/a_(b)", s.single().link)
    }

    @Test
    fun markdown_autolinkRejectsControlCharactersAndBackslashes() {
        assertTrue(spans("https://example.com/a\\b").none { it.link != null })
        assertTrue(spans("https://example.com/a\u0007b").none { it.link != null })
    }

    @Test
    fun markdown_javascriptLinkDropped() {
        // Небезопасная схема: ярлык остаётся текстом, активной ссылки нет.
        val s = spans("[click](javascript:alert(1))")
        assertTrue(s.none { it.link != null })
        assertTrue(s.any { it.text.contains("click") })
    }

    @Test
    fun markdown_lists() {
        val blocks = MarkdownRenderer.render("- one\n- two\n1. first\n2) second", false).blocks
        assertTrue(blocks[0] is RichBlock.Bullet)
        assertEquals("one", blocks[0].spans.joinToString("") { it.text })
        assertTrue(blocks[2] is RichBlock.Ordered)
        assertEquals(1, (blocks[2] as RichBlock.Ordered).number)
        assertEquals("first", blocks[2].spans.joinToString("") { it.text })
        assertEquals(2, (blocks[3] as RichBlock.Ordered).number)
    }

    // --- markdown: абзацы, заголовки, цитаты (реальное тело макроса, 2026-10-30) ---

    private val macroBody = "Good day,\n\n## Steps\n\n- open the bot\n- press **Stop**\n\n" +
        "> It takes a minute\n\nOlga from Acme Support here.\nThank you for contacting us!"

    @Test
    fun markdown_blankLineIsParagraphGapOncePerRun() {
        val kinds = MarkdownRenderer.render(macroBody, false).blocks.map { it::class.simpleName }
        assertEquals(
            listOf(
                "Paragraph", "Gap", "Paragraph", "Gap", "Bullet", "Bullet", "Gap",
                "Paragraph", "Gap", "Paragraph", "Paragraph",
            ),
            kinds,
        )
        val edges = MarkdownRenderer.render("\n\na\n\n\n\nb\n\n", false).blocks
        assertEquals(listOf("Paragraph", "Gap", "Paragraph"), edges.map { it::class.simpleName })
    }

    @Test
    fun markdown_headingIsBoldLineAndQuoteLosesMarker() {
        val blocks = MarkdownRenderer.render(macroBody, false).blocks
        assertEquals(listOf(RichSpan("Steps", bold = true)), blocks[2].spans)
        assertEquals("It takes a minute", blocks[7].spans.joinToString("") { it.text })
        val text = MarkdownRenderer.render(macroBody, false).plainText()
        assertFalse(text.contains("#"))
        assertFalse(text.contains(">"))
        assertFalse(text.contains("\\"))
        // «## C#» — закрывающих решёток нет, «#» в тексте остаётся.
        assertEquals("C#", MarkdownRenderer.render("## C#", false).plainText())
    }

    // Картинка тела макроса — ссылка с подписью alt (пустой alt — адресом), без «!».
    @Test
    fun markdown_imageIsLinkWithoutBang() {
        val spans = MarkdownRenderer
            .render("See ![](https://cdn.example.com/x.png) and ![shot](https://cdn.example.com/a.png)", false)
            .blocks.single().spans
        assertFalse(spans.joinToString("") { it.text }.contains("!"))
        assertEquals(
            listOf(
                RichSpan("https://cdn.example.com/x.png", link = "https://cdn.example.com/x.png"),
                RichSpan("shot", link = "https://cdn.example.com/a.png"),
            ),
            spans.filter { it.link != null },
        )
    }

    // Рыхлый список импорта держит номера источника; ленивая «1. 1.» — по порядку.
    @Test
    fun markdown_looseOrderedListKeepsSourceNumbers() {
        fun numbers(md: String) = MarkdownRenderer.render(md, false).blocks
            .filterIsInstance<RichBlock.Ordered>().map { it.number }
        assertEquals(listOf(1, 2, 3), numbers("1. Open the bot\n\n2. Press Stop\n\n3. Wait"))
        assertEquals(listOf(1, 2, 4), numbers("1. a\n1. b\n\ntext\n\n4. c"))
    }

    @Test
    fun markdown_unclosedMarkersAreLiteral() {
        // Незакрытые ** / ` не должны терять текст.
        assertEquals("a **b c", MarkdownRenderer.render("a **b c", false).plainText())
        assertEquals("x `y", MarkdownRenderer.render("x `y", false).plainText())
    }

    // --- html: безопасная деградация ---

    @Test
    fun html_inlineTagsAndLink() {
        val s = spans("<b>Bold</b> <a href=\"https://x.y\">link</a>", isHtml = true)
        assertTrue(s.any { it.bold && it.text == "Bold" })
        val link = s.first { it.link != null }
        assertEquals("link", link.text)
        assertEquals("https://x.y", link.link)
    }

    @Test
    fun html_downgradedPlainContentAutolinksUrl() {
        val s = spans("operator reply https://example.com/help", isHtml = true)
        val link = s.first { it.link != null }
        assertEquals("https://example.com/help", link.text)
        assertEquals("https://example.com/help", link.link)
    }

    @Test
    fun html_scriptContentStripped() {
        assertEquals("safeend", MarkdownRenderer.render("safe<script>alert(1)</script>end", true).plainText())
    }

    @Test
    fun html_styleContentStripped() {
        assertEquals("ab", MarkdownRenderer.render("a<style>.x{color:red}</style>b", true).plainText())
    }

    @Test
    fun html_javascriptHrefDropped() {
        val s = spans("<a href=\"javascript:steal()\">t</a>", isHtml = true)
        assertNull(s.firstOrNull { it.text == "t" }?.link)
    }

    @Test
    fun html_unclosedTagIsSafe() {
        // '<' без '>' — безопасно отбрасываем хвост, без исключения.
        assertEquals("oops ", MarkdownRenderer.render("oops <incomplete", true).plainText())
        // Незакрытый инлайн-тег не роняет разбор.
        assertTrue(spans("text <b>bold", isHtml = true).any { it.bold })
    }

    @Test
    fun html_entitiesDecoded() {
        assertEquals("a & b <c>", MarkdownRenderer.render("a &amp; b &lt;c&gt;", true).plainText())
    }

    // --- защита от больших входов ---

    @Test
    fun render_capsInputLength() {
        val big = "a".repeat(MarkdownRenderer.MAX_INPUT + 5_000)
        val out = MarkdownRenderer.render(big, false).plainText()
        assertTrue(out.length <= MarkdownRenderer.MAX_INPUT)
    }

    @Test
    fun render_largeWellFormedInputCompletesQuickly() {
        val block = "Line **bold** with `code` and [x](https://a.b/p) plus https://c.d/e\n- item\n"
        val big = block.repeat(2_000) // ~140k → усечётся до 20k
        val started = System.nanoTime()
        val out = MarkdownRenderer.render(big, false)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("разбор должен быть быстрым (был ${elapsedMs}ms)", elapsedMs < 1_000)
        assertFalse(out.blocks.isEmpty())
    }

    @Test
    fun render_emptyContent() {
        assertTrue(MarkdownRenderer.render("", false).blocks.isEmpty())
    }

    // «\#» в начале строки — литерал «#», не заголовок: канонический вид тела хранит этот экран
    // (паритет с iOS и Flutter).
    @Test
    fun markdown_escapedHeadingIsPlainText() {
        val blocks = MarkdownRenderer.render("\\# of orders: 5\n## Real", false).blocks
        assertEquals(2, blocks.size)
        assertEquals("# of orders: 5", blocks[0].spans.joinToString("") { it.text })
        assertFalse(blocks[0].spans.any { it.bold })
        assertEquals(listOf(RichSpan("Real", bold = true)), blocks[1].spans)
    }
}
