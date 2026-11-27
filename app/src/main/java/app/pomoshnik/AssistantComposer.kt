package app.pomoshnik

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AssistantComposer(
    activity: MainActivity,
    input: String,
    onInputChange: (String) -> Unit,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val wakeStatus by WakeListeningService.status.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = input.isNotBlank() && !activity.busy
    val send = {
        if (canSend) {
            focus.clearFocus()
            keyboard?.hide()
            onSend(input)
        }
    }
    val status = when {
        activity.busy -> "Готовлю ответ…"
        activity.listening -> activity.partial.ifBlank { "Слушаю…" }
        else -> activity.clarification ?: activity.notice ?: wakeStatus.orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.isNotBlank()) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activity.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(status, Modifier.weight(1f), color = colors.primary, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (activity.clarification != null) {
                    TextButton(onClick = activity::cancelClarification) { Text("Отмена") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    onMic()
                },
                enabled = !activity.busy || activity.listening,
                modifier = Modifier.size(52.dp).background(colors.primary, CircleShape),
            ) {
                Icon(
                    if (activity.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (activity.listening) "Остановить" else "Сказать команду",
                    tint = colors.onPrimary,
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Спроси или дай команду", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                shape = CircleShape,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    focusedContainerColor = colors.surfaceVariant,
                    unfocusedContainerColor = colors.surfaceVariant,
                    focusedPlaceholderColor = colors.onSurfaceVariant,
                    unfocusedPlaceholderColor = colors.onSurfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            if (input.isNotEmpty()) IconButton(onClick = send, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = colors.primary)
            }
        }
    }
}
