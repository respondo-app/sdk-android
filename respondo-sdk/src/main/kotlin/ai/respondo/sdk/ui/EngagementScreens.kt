package ai.respondo.sdk.ui

import ai.respondo.sdk.core.OverlayDecision
import ai.respondo.sdk.core.RespondoBanner
import ai.respondo.sdk.core.RespondoBannerAction
import ai.respondo.sdk.core.RespondoChecklist
import ai.respondo.sdk.core.RespondoChecklistAction
import ai.respondo.sdk.core.RespondoChecklistTask
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.core.RespondoNewsItem
import ai.respondo.sdk.core.RespondoQuestion
import ai.respondo.sdk.core.RespondoQuestionType
import ai.respondo.sdk.core.RespondoSurvey
import ai.respondo.sdk.core.RespondoSurveyAnswer
import ai.respondo.sdk.i18n.Strings
import ai.respondo.sdk.theme.BrandColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private const val LABEL_COLOR = 0xFF6B7280
private const val EMPTY_COLOR = 0xFF9CA3AF
private const val HAIRLINE_COLOR = 0xFFE5E7EB

private fun hexColor(hex: String?, fallbackArgb: Long): Color = Color(BrandColor.parseHex(hex, fallbackArgb))

/** Лента новостей («What's new»). Открытие карточки помечает её seen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewsScreen(controller: RespondoController) {
    val lang by controller.lang.collectAsState()
    val theme by controller.theme.collectAsState()
    val items by controller.engagement.news.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(Strings.get(lang, "newsTitle"), fontSize = 16.sp) },
            actions = { TextButton(onClick = { ai.respondo.sdk.Respondo.close() }) { Text(Strings.get(lang, "close")) } },
        )
        if (items.isEmpty()) {
            EmptyState(Strings.get(lang, "newsEmpty"))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    LaunchedEffect(item.id) { controller.engagement.markNewsSeen(item.id) }
                    NewsCard(item, theme.primaryColorArgb)
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: RespondoNewsItem, primaryArgb: Long) {
    val primary = Color(primaryArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(HAIRLINE_COLOR), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        item.imageUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (item.labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (label in item.labels) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(item.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        if (item.body.isNotEmpty()) {
            Text(item.body, fontSize = 14.sp, color = Color(LABEL_COLOR), modifier = Modifier.padding(top = 4.dp))
        }
        if (!item.seen) {
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(primary))
        }
    }
}

/** Онбординг-чеклисты: задачи с чекбоксами/CTA, прогресс, скрытие. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChecklistsScreen(controller: RespondoController) {
    val lang by controller.lang.collectAsState()
    val theme by controller.theme.collectAsState()
    val lists by controller.engagement.checklists.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(Strings.get(lang, "checklistsTitle"), fontSize = 16.sp) },
            actions = { TextButton(onClick = { ai.respondo.sdk.Respondo.close() }) { Text(Strings.get(lang, "close")) } },
        )
        if (lists.isEmpty()) {
            EmptyState(Strings.get(lang, "checklistsEmpty"))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(lists, key = { it.id }) { checklist ->
                    ChecklistCard(checklist, theme.primaryColorArgb, lang, controller)
                }
            }
        }
    }
}

@Composable
private fun ChecklistCard(checklist: RespondoChecklist, primaryArgb: Long, lang: String, controller: RespondoController) {
    val primary = Color(primaryArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(HAIRLINE_COLOR), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(checklist.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (checklist.dismissible) {
                TextButton(onClick = { controller.engagement.dismissChecklist(checklist.id) }) {
                    Text("×", fontSize = 18.sp, color = Color(LABEL_COLOR))
                }
            }
        }
        checklist.body?.let { Text(it, fontSize = 13.sp, color = Color(LABEL_COLOR)) }
        Spacer(Modifier.height(8.dp))
        ProgressBar(checklist.doneCount, checklist.totalCount, primary)
        Spacer(Modifier.height(8.dp))
        for (task in checklist.visibleTasks) {
            ChecklistTaskRow(task, checklist.doneTaskIds.contains(task.id), primary) {
                controller.engagement.performTask(checklist.id, task.id)
            }
        }
    }
}

@Composable
private fun ProgressBar(done: Int, total: Int, primary: Color) {
    val fraction = if (total == 0) 0f else done.toFloat() / total.toFloat()
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color(HAIRLINE_COLOR)),
        ) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(primary))
        }
        Text("$done/$total", fontSize = 11.sp, color = Color(LABEL_COLOR), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChecklistTaskRow(task: RespondoChecklistTask, done: Boolean, primary: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "☑" else "☐", fontSize = 20.sp, color = if (done) primary else Color(LABEL_COLOR))
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                task.title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            task.body?.let { Text(it, fontSize = 12.sp, color = Color(LABEL_COLOR)) }
        }
        if (task.action is RespondoChecklistAction.Url) Text("›", fontSize = 16.sp, color = Color(LABEL_COLOR))
    }
}

/**
 * Оверлей-опрос (NPS/CSAT и др.) поверх треда. Рисуется только когда арбитр выбрал опрос.
 */
@Composable
internal fun SurveyOverlay(controller: RespondoController, survey: RespondoSurvey) {
    val lang by controller.lang.collectAsState()
    val theme by controller.theme.collectAsState()
    val stepIndex by controller.engagement.surveyStepIndex.collectAsState()
    val finished by controller.engagement.surveyFinished.collectAsState()
    val answers by controller.engagement.surveyAnswers.collectAsState()
    val primary = Color(theme.primaryColorArgb)
    val onPrimary = Color(theme.onPrimaryArgb)

    Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    survey.intro?.let { Text(it, fontSize = 14.sp, color = Color(LABEL_COLOR)) }
                    if (survey.showProgress) {
                        Text(
                            "${minOf(stepIndex + 1, survey.steps.size)}/${survey.steps.size}",
                            fontSize = 11.sp,
                            color = Color(LABEL_COLOR),
                        )
                    }
                }
                if (survey.showDismiss) {
                    TextButton(onClick = { controller.engagement.dismissSurvey(survey.deliveryId) }) {
                        Text("×", fontSize = 18.sp, color = Color(LABEL_COLOR))
                    }
                }
            }

            if (finished) {
                Text(
                    survey.thanks ?: Strings.get(lang, "surveyThanks"),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(Strings.get(lang, "close"), primary, onPrimary, enabled = true) {
                    controller.engagement.dismissSurvey(survey.deliveryId)
                }
            } else {
                for (question in controller.engagement.currentStepQuestions(survey)) {
                    SurveyQuestion(question, answers[question.id], primary, onPrimary) { answer ->
                        controller.engagement.answerQuestion(survey, question.id, answer)
                    }
                }
                val isLast = stepIndex + 1 >= survey.steps.size
                val canAdvance = controller.engagement.canAdvance(survey)
                PrimaryButton(
                    label = Strings.get(lang, if (isLast) "surveySubmit" else "surveyNext"),
                    background = if (canAdvance) primary else Color(LABEL_COLOR).copy(alpha = 0.4f),
                    foreground = onPrimary,
                    enabled = canAdvance,
                ) { controller.engagement.advanceSurvey(survey) }
            }
        }
    }
}

@Composable
private fun SurveyQuestion(
    question: RespondoQuestion,
    answer: RespondoSurveyAnswer?,
    primary: Color,
    onPrimary: Color,
    onAnswer: (RespondoSurveyAnswer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        when (question.type) {
            RespondoQuestionType.NPS -> ScaleRow(0..10, (answer as? RespondoSurveyAnswer.Number)?.value, primary, onPrimary) {
                onAnswer(RespondoSurveyAnswer.Number(it.toDouble()))
            }
            RespondoQuestionType.CSAT, RespondoQuestionType.SCALE -> ScaleRow(1..5, (answer as? RespondoSurveyAnswer.Number)?.value, primary, onPrimary) {
                onAnswer(RespondoSurveyAnswer.Number(it.toDouble()))
            }
            RespondoQuestionType.CHOICE -> ChoiceList(question.options, (answer as? RespondoSurveyAnswer.Choice)?.value, primary) {
                onAnswer(RespondoSurveyAnswer.Choice(it))
            }
            RespondoQuestionType.TEXT -> {
                var text by remember { mutableStateOf((answer as? RespondoSurveyAnswer.Text)?.value.orEmpty()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (it.isNotEmpty()) onAnswer(RespondoSurveyAnswer.Text(it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun ScaleRow(range: IntRange, selected: Double?, primary: Color, onPrimary: Color, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (value in range) {
            val isSelected = selected == value.toDouble()
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) primary else primary.copy(alpha = 0.1f))
                    .clickable { onPick(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text("$value", fontSize = 14.sp, color = if (isSelected) onPrimary else primary)
            }
        }
    }
}

@Composable
private fun ChoiceList(options: List<String>, selected: String?, primary: Color, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) {
            val isSelected = selected == option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(BrandColor.ASSISTANT_BUBBLE))
                    .border(1.5.dp, if (isSelected) primary else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onPick(option) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (isSelected) Text("✓", fontSize = 14.sp, color = primary)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, background: Color, foreground: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = foreground)
    }
}

/** Плашка-баннер (top/bottom) внутри Respondo-экранов. */
@Composable
internal fun BannerBar(controller: RespondoController, banner: RespondoBanner) {
    val lang by controller.lang.collectAsState()
    val theme by controller.theme.collectAsState()
    val background = hexColor(banner.backgroundHex, BrandColor.ASSISTANT_BUBBLE)
    val foreground = hexColor(banner.foregroundHex, BrandColor.INK)
    val btnBg = hexColor(banner.buttonBackgroundHex, theme.primaryColorArgb)
    val btnFg = hexColor(banner.buttonForegroundHex, theme.onPrimaryArgb)
    var email by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(banner.body, fontSize = 13.sp, color = foreground)
            when (banner.action) {
                RespondoBannerAction.URL -> banner.linkLabel?.let { label ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(btnBg)
                            .clickable { controller.engagement.bannerOpenUrl(banner) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(label, fontSize = 13.sp, color = btnFg)
                    }
                }
                RespondoBannerAction.REACTIONS -> if (banner.reactions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (emoji in banner.reactions) {
                            Text(
                                emoji,
                                fontSize = 20.sp,
                                modifier = Modifier.clickable { controller.engagement.bannerReaction(banner, emoji) },
                            )
                        }
                    }
                }
                RespondoBannerAction.EMAIL -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(Strings.get(lang, "emailCollectorPlaceholder"), fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(btnBg)
                            .clickable { controller.engagement.bannerEmail(banner, email) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(Strings.get(lang, "emailSubmit"), fontSize = 13.sp, color = btnFg)
                    }
                }
                else -> Unit
            }
        }
        if (banner.showDismiss) {
            TextButton(onClick = { controller.engagement.dismissBanner(banner.deliveryId) }) {
                Text("×", fontSize = 18.sp, color = foreground.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, fontSize = 14.sp, color = Color(EMPTY_COLOR))
    }
}
