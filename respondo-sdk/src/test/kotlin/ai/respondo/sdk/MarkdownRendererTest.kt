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
}
