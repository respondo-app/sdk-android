package ai.respondo.sdk.ui

import ai.respondo.sdk.core.EmailValidator
import ai.respondo.sdk.i18n.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

/** Композер ввода: вложение, текстовое поле, кнопка отправки. */
@Composable
internal fun Composer(
    value: String,
    placeholder: String,
    sendLabel: String,
    attachLabel: String,
    errorText: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAttach) { Text("+", fontSize = 22.sp) }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder, fontSize = 14.sp) },
                maxLines = 5,
                isError = errorText != null,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            TextButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Text(sendLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
    }
}

/** Полноэкранный лайтбокс изображения (тап — закрыть). */
@Composable
internal fun ImageLightbox(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

/** Индикатор набора оператором. */
@Composable
internal fun TypingRow(authorName: String?, lang: String) {
    val label = if (authorName != null) "$authorName ${Strings.get(lang, "typingIndicator")}" else Strings.get(lang, "typingIndicator")
    Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF6B7280))
    }
}

/** Ряд чипов-вопросов (быстрые/предложенные). Клик отправляет текст вопроса. */
@Composable
internal fun QuestionChips(questions: List<String>, onClick: (String) -> Unit) {
    if (questions.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (q in questions) {
            Text(
                text = q,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF3F4F6))
                    .clickable { onClick(q) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Плашка доступности офис-часов: цветная точка + текст. */
@Composable
internal fun OfficeHoursBar(open: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF9FAFB)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape)
                .background(if (open) Color(0xFF22C55E) else Color(0xFF9CA3AF)),
        )
        Text(text, fontSize = 12.sp, color = Color(0xFF374151), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Баннер эскалации: ожидание/оператор в сети + кнопка «вернуть в AI». */
@Composable
internal fun EscalationBannerView(agentIsHere: Boolean, lang: String, onContinueWithAi: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFFEF3C7)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (agentIsHere) Strings.get(lang, "agentIsHere") else Strings.get(lang, "waitingForAgent"),
            fontSize = 13.sp,
            color = Color(0xFF92400E),
        )
        TextButton(onClick = onContinueWithAi) {
            Text(Strings.get(lang, "continueWithAI"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Inline-поле сбора email (email_collector): блокирует ответ до валидного адреса. */
@Composable
internal fun EmailCollectorView(lang: String, onSubmit: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(Strings.get(lang, "emailCollectorPrompt"), fontSize = 13.sp, color = Color(0xFF374151))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = false },
            placeholder = { Text(Strings.get(lang, "emailCollectorPlaceholder")) },
            isError = error,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (error) Text(Strings.get(lang, "emailInvalid"), fontSize = 11.sp, color = Color(0xFFDC2626))
        Button(
            onClick = { if (EmailValidator.isValid(email)) onSubmit(email) else error = true },
            modifier = Modifier.padding(top = 6.dp),
        ) { Text(Strings.get(lang, "emailSubmit")) }
    }
}

/** Компактный индикатор загрузки (ожидание ответа AI). */
@Composable
internal fun LoadingRow() {
    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
