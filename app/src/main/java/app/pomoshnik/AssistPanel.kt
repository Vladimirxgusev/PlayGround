package app.pomoshnik

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f))
                .clickable(onClickLabel = "Закрыть панель", onClick = onClose),
        )
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val answerLimit = (maxHeight - 172.dp).coerceIn(52.dp, 300.dp)
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Секретутка", Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onExpand) {
                            Icon(Icons.Filled.OpenInFull, contentDescription = "Открыть на весь экран")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                        }
                    }
                    activity.panelEntry?.let { entry ->
                        key(entry.id) {
                            Column(Modifier.heightIn(max = answerLimit).verticalScroll(rememberScrollState())) {
                                EntryCard(entry, onUndo, entry.said in activity.favorites, activity::toggleFavorite)
                            }
                        }
                    }
                    FavoriteCommands(activity.favorites, onInputChange)
                    AssistantComposer(activity, input, onInputChange, onMic, onSend)
                }
            }
        }
    }
}
