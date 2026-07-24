package ai.respondo.sdk.ui

import ai.respondo.sdk.core.ChatMessage
import ai.respondo.sdk.core.MessageRole
import ai.respondo.sdk.core.SendStatus
import ai.respondo.sdk.core.isImage
import ai.respondo.sdk.i18n.Strings
import ai.respondo.sdk.theme.BrandColor
import ai.respondo.sdk.transport.dto.ChatAttachmentDto
import ai.respondo.sdk.transport.dto.DocLinkDto
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Пузырь сообщения: пользователь справа (primary), ассистент/оператор слева (светлый пузырь),
 * системные — по центру. Показывает вложения (клик по изображению → лайтбокс), источники,
 * статус отправки/доставки.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    lang: String,
    linkColor: Color,
    docLinks: List<DocLinkDto>,
    onImageClick: (ChatAttachmentDto) -> Unit,
    onRetry: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    when (message.role) {
        MessageRole.USER -> UserBubble(message, lang, onImageClick, onRetry)
        MessageRole.ASSISTANT -> AssistantBubble(message, lang, linkColor, docLinks, onImageClick, onLinkClick)
        MessageRole.SYSTEM -> SystemBubble(message, linkColor, onLinkClick)
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    lang: String,
    onImageClick: (ChatAttachmentDto) -> Unit,
    onRetry: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 280.dp)) {
            if (message.content.isNotEmpty()) {
                Text(
                    text = message.content,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            AttachmentList(message.attachments, onImageClick)
            when (message.sendStatus) {
                SendStatus.SENDING -> Text(Strings.get(lang, "typingIndicator"), fontSize = 11.sp, color = mutedColor())
                SendStatus.FAILED -> Text(
                    text = Strings.get(lang, "sendFailed"),
                    fontSize = 11.sp,
                    color = Color(0xFFDC2626),
                    modifier = Modifier.clickable { onRetry(message.id) }.padding(top = 2.dp),
                )
                SendStatus.SENT, SendStatus.NONE -> Unit
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    lang: String,
    linkColor: Color,
    docLinks: List<DocLinkDto>,
    onImageClick: (ChatAttachmentDto) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        if (message.authorAvatarUrl != null) {
            AsyncImage(
                model = message.authorAvatarUrl,
                contentDescription = message.authorName,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).padding(end = 0.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 8.dp).widthIn(max = 280.dp)) {
            if (message.authorName != null) {
                Text(message.authorName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = mutedColor())
            }
            if (message.content.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrandColor.ASSISTANT_BUBBLE.toComposeColor())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    RichMessageText(
                        content = message.content,
                        isHtml = message.isHtml,
                        baseColor = BrandColor.INK.toComposeColor(),
                        linkColor = linkColor,
                        fontSize = 15,
                        onLinkClick = onLinkClick,
                    )
                }
            }
            AttachmentList(message.attachments, onImageClick)
            SourcesRow(message, lang, linkColor, onLinkClick)
            DocLinksRow(docLinks, lang, onLinkClick)
            if (message.deliveryStatus == "delivered") {
                Text("✓✓ ${Strings.get(lang, "readReceipt")}", fontSize = 10.sp, color = mutedColor())
            }
        }
    }
}

@Composable
private fun SystemBubble(message: ChatMessage, linkColor: Color, onLinkClick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF9FAFB))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            RichMessageText(
                content = message.content,
                isHtml = message.isHtml,
                baseColor = mutedColor(),
                linkColor = linkColor,
                fontSize = 12,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun AttachmentList(attachments: List<ChatAttachmentDto>, onImageClick: (ChatAttachmentDto) -> Unit) {
    for (att in attachments) {
        if (att.isImage()) {
            AsyncImage(
                model = att.url,
                contentDescription = att.filename,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(att) },
            )
        } else {
            Text("📎 ${att.filename}", fontSize = 13.sp, color = mutedColor(), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SourcesRow(message: ChatMessage, lang: String, linkColor: Color, onLinkClick: (String) -> Unit) {
    if (message.sources.isEmpty()) return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(Strings.get(lang, "sourcesLabel") + ":", fontSize = 11.sp, color = mutedColor())
        for (src in message.sources) {
            val url = src.url ?: continue
            Text(
                text = src.title ?: url,
                fontSize = 12.sp,
                color = linkColor,
                modifier = Modifier.clickable { onLinkClick(url) }.padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun DocLinksRow(docLinks: List<DocLinkDto>, lang: String, onLinkClick: (String) -> Unit) {
    if (docLinks.isEmpty()) return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(Strings.get(lang, "docLinksLabel") + ":", fontSize = 11.sp, color = mutedColor())
        for (link in docLinks) {
            val url = link.url ?: continue
            Text(
                text = link.title ?: url,
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onLinkClick(url) }.padding(vertical = 1.dp),
            )
        }
    }
}

private fun mutedColor(): Color = Color(0xFF6B7280)
