package ai.respondo.sdk.ui

import ai.respondo.sdk.core.ControllerLogic
import ai.respondo.sdk.core.MessageRole
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.i18n.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Главный экран чата: шапка, тред, композер и все спец-поверхности. Биндится к потокам [RespondoController]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(controller: RespondoController) {
    val theme by controller.theme.collectAsState()
    val lang by controller.lang.collectAsState()
    val messages by controller.messages.collectAsState()
    val typing by controller.typing.collectAsState()
    val isLoading by controller.isLoading.collectAsState()
    val escalated by controller.escalated.collectAsState()
    val agentHasReplied by controller.agentHasReplied.collectAsState()
    val suggested by controller.suggestedQuestions.collectAsState()
    val docLinks by controller.docLinks.collectAsState()
    val officeHours by controller.officeHours.collectAsState()
    val emailRequired by controller.emailCollectorRequired.collectAsState()
    val pendingAttachments by controller.pendingAttachments.collectAsState()
    val hasMoreHistory by controller.hasMoreHistory.collectAsState()
    val messageError by controller.messageError.collectAsState()

    // rememberSaveable — переживает пересоздание (поворот экрана): черновик и открытый лайтбокс не теряются.
    var composerText by rememberSaveable { mutableStateOf("") }
    var lightboxUrl by rememberSaveable { mutableStateOf<String?>(null) }

    // Подавление оверлеев (survey/banner) при активном лайтбоксе/непустом композере.
    LaunchedEffect(composerText.isNotBlank()) { controller.engagement.setComposerHasText(composerText.isNotBlank()) }
    LaunchedEffect(lightboxUrl != null) { controller.engagement.setLightboxOpen(lightboxUrl != null) }

    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uiScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = context.contentResolver
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                        val type = resolver.getType(uri) ?: "application/octet-stream"
                        val name = queryDisplayName(resolver, uri) ?: "attachment"
                        Triple(bytes, name, type)
                    }.getOrNull()
                }
                loaded?.let { controller.attachFile(it.first, it.second, it.third) }
            }
        }
    }

    val listState = rememberLazyListState()
    // Автопрокрутка вниз только при НОВОМ последнем сообщении (ключ — id последнего, не размер списка):
    // подгрузка истории делает prepend вверх, id последнего не меняется → вниз не дёргаем.
    val lastMessage = messages.lastOrNull()
    val lastMessageId = lastMessage?.id
    var lastScrolledId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastMessageId) {
        if (lastMessage == null || lastMessageId == null) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisibleIndex >= info.totalItemsCount - 2
        // Собственная только что отправленная реплика — скроллим всегда, даже если читали историю выше.
        val ownSend = lastMessage.role == MessageRole.USER && lastMessage.isLocal
        if (ControllerLogic.shouldAutoScroll(firstScroll = lastScrolledId == null, nearBottom = nearBottom, lastIsOwnSend = ownSend)) {
            listState.animateScrollToItem(messages.size - 1)
        }
        lastScrolledId = lastMessageId
    }
    // Пагинация истории при скролле к верху.
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && hasMoreHistory) controller.loadMoreHistory()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(theme.title, fontSize = 16.sp) },
            navigationIcon = {
                theme.logoUrl?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp).size(28.dp).clip(RoundedCornerShape(14.dp)),
                    )
                }
            },
            actions = {
                TextButton(onClick = { ai.respondo.sdk.Respondo.close() }) {
                    Text(Strings.get(lang, "close"))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(),
        )

        officeHours?.let { OfficeHoursBar(it.open, it.text) }
        if (escalated) EscalationBannerView(agentHasReplied, lang) { controller.continueWithAi() }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        lang = lang,
                        linkColor = theme.linkColorArgb.toComposeColor(),
                        docLinks = docLinks[message.id].orEmpty(),
                        onImageClick = { lightboxUrl = it.url },
                        onRetry = { controller.retry(it) },
                        onLinkClick = { url -> if (!controller.onUrlRequested(url)) openExternally(context, url) },
                    )
                }
                if (typing != null) item { TypingRow(typing?.authorName, lang) }
                if (isLoading) item { LoadingRow() }
            }
        }

        // Быстрые вопросы (тред без пользовательских сообщений) или предложенные вопросы.
        val hasUserMessage = messages.any { it.role == MessageRole.USER }
        if (!hasUserMessage && theme.quickQuestions.isNotEmpty()) {
            QuestionChips(theme.quickQuestions) { controller.sendMessage(it) }
        } else if (suggested.isNotEmpty()) {
            QuestionChips(suggested) { controller.sendMessage(it) }
        }

        PendingAttachmentsRow(pendingAttachments.map { it.filename }, onRemove = { name ->
            pendingAttachments.firstOrNull { it.filename == name }?.let { controller.removePendingAttachment(it.id) }
        })

        HorizontalDivider()

        if (emailRequired) {
            EmailCollectorView(lang) { controller.submitCollectedEmail(it) }
        } else {
            Composer(
                value = composerText,
                placeholder = Strings.get(lang, if (escalated) "inputPlaceholderEscalated" else "inputPlaceholder"),
                sendLabel = Strings.get(lang, "send"),
                attachLabel = Strings.get(lang, "attach"),
                errorText = messageError,
                enabled = true,
                onValueChange = {
                    // Клиентский кэп ввода по лимиту сообщения.
                    composerText = it.take(RespondoController.MAX_MESSAGE_LENGTH)
                    if (messageError != null) controller.clearMessageError()
                },
                onSend = {
                    controller.sendMessage(composerText)
                    composerText = ""
                },
                onAttach = { filePicker.launch("*/*") },
            )
        }
    }

    lightboxUrl?.let { url -> ImageLightbox(url) { lightboxUrl = null } }
}

@Composable
private fun PendingAttachmentsRow(names: List<String>, onRemove: (String) -> Unit) {
    if (names.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        for (name in names) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎 $name", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(name) }) { Text("×", fontSize = 18.sp) }
            }
        }
    }
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? {
    return runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

private fun openExternally(context: android.content.Context, url: String) =
    ai.respondo.sdk.core.ExternalOpener.open(context, url)
