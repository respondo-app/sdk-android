package ai.respondo.sdk.ui

import ai.respondo.sdk.core.MarkdownRenderer
import ai.respondo.sdk.core.RichBlock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val CODE_BACKGROUND = Color(0x14000000)

/**
 * Рендер контента сообщения (markdown или безопасная деградация html) в нативные `AnnotatedString`:
 * жирный/курсив/инлайн-код/ссылки (только http(s), кликабельные)/маркированные и нумерованные списки.
 * Без WebView. Ссылки открываются через [onLinkClick] (host-листенер → внешний браузер).
 */
@Composable
internal fun RichMessageText(
    content: String,
    isHtml: Boolean,
    baseColor: Color,
    linkColor: Color,
    fontSize: Int,
    onLinkClick: (String) -> Unit,
) {
    val rich = remember(content, isHtml) { MarkdownRenderer.render(content, isHtml) }
    Column {
        for (block in rich.blocks) {
            val prefix = when (block) {
                is RichBlock.Bullet -> "•  "
                is RichBlock.Ordered -> "${block.number}.  "
                is RichBlock.Paragraph -> ""
            }
            val annotated = buildAnnotatedString {
                if (prefix.isNotEmpty()) append(prefix)
                for (span in block.spans) {
                    val url = span.link
                    val style = SpanStyle(
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        background = if (span.code) CODE_BACKGROUND else Color.Unspecified,
                        color = if (url != null) linkColor else baseColor,
                        textDecoration = if (url != null) TextDecoration.Underline else null,
                    )
                    if (url != null) {
                        val listener = LinkInteractionListener { onLinkClick(url) }
                        withLink(LinkAnnotation.Clickable(tag = url, linkInteractionListener = listener)) {
                            withStyle(style) { append(span.text) }
                        }
                    } else {
                        withStyle(style) { append(span.text) }
                    }
                }
            }
            Text(text = annotated, color = baseColor, fontSize = fontSize.sp)
        }
    }
}
