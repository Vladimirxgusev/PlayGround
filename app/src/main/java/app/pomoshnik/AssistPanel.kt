package app.pomoshnik

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AssistPanel(
    activity: MainActivity,
    input: String,
    onInputChange: (String) -> Unit,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onUndo: (Entry) -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val wakeStatus by WakeListeningService.status.collectAsState()
    val status = when {
        activity.busy -> "Выполняю…"
        activity.listening -> activity.partial.ifBlank { "Слушаю…" }
        else -> wakeStatus.orEmpty()
    }
    val canSend = input.isNotBlank() && !activity.busy
    val send = { if (canSend) onSend(input) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))
                .clickable(onClickLabel = "Закрыть панель", onClick = onClose),
        )
        Box(Modifier.safeDrawingPadding().imePadding().padding(12.dp)) {
            Surface(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = colors.surface,
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Секретутка",
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = onExpand) {
                            Icon(Icons.Filled.OpenInFull, contentDescription = "Открыть на весь экран")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                        }
                    }

                    activity.history.firstOrNull()?.let { entry ->
                        Column(Modifier.heightIn(max = 132.dp).verticalScroll(rememberScrollState())) {
                            EntryCard(entry, onUndo)
                        }
                    }

                    if (status.isNotBlank()) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (activity.busy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                status,
                                color = colors.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = onMic,
                            enabled = !activity.busy || activity.listening,
                            modifier = Modifier.size(48.dp)
                                .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary)), CircleShape),
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
                            placeholder = { Text("Напиши команду") },
                            shape = CircleShape,
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.35f),
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        if (input.isNotEmpty()) {
                            IconButton(onClick = send, enabled = canSend) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                            }
                        }
                    }
                }
            }
        }
    }
}
