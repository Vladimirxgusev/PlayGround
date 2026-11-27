package app.pomoshnik

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pomoshnik.core.CommandState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val examples = listOf(
    "Будильник" to "Поставь будильник на 7:30",
    "Напоминание" to "Напомни завтра в 10 позвонить маме",
    "Погода" to "Какая погода завтра?",
    "Курс доллара" to "Курс доллара",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    input: String,
    onInputChange: (String) -> Unit,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onUndo: (Entry) -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var showHistory by rememberSaveable { mutableStateOf(false) }
    BackHandler(showHistory) { showHistory = false }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = colors.onBackground,
        modifier = Modifier.background(
            Brush.verticalGradient(listOf(colors.primaryContainer, colors.background, colors.background)),
        ),
        topBar = {
            TopAppBar(
                title = { Text(if (showHistory) "История" else "Секретутка", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onBackground,
                    actionIconContentColor = colors.onBackground,
                ),
                navigationIcon = {
                    if (showHistory) IconButton(onClick = { showHistory = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К помощнику")
                    }
                },
                actions = {
                    if (!showHistory) IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "История команд")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 580.dp).fillMaxSize()) {
                val visible = if (showHistory) activity.history else activity.history.take(4)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = if (visible.isEmpty()) Arrangement.Center else Arrangement.spacedBy(14.dp),
                    reverseLayout = visible.isNotEmpty(),
                ) {
                    if (visible.isEmpty()) {
                        item {
                            if (showHistory) {
                                Text("Здесь появятся выполненные команды и ответы.", color = colors.onSurfaceVariant)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    IconButton(
                                        onClick = onMic,
                                        modifier = Modifier.size(76.dp).background(
                                            Brush.linearGradient(listOf(colors.primary, colors.tertiary)), CircleShape,
                                        ),
                                    ) {
                                        Icon(Icons.Filled.Mic, contentDescription = "Продиктовать", tint = colors.onPrimary,
                                            modifier = Modifier.size(30.dp))
                                    }
                                    Text("Чем помочь?", style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        examples.forEach { (label, example) ->
                                            FilterChip(selected = input == example, onClick = { onInputChange(example) },
                                                label = { Text(label) }, shape = CircleShape)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { entry ->
                            EntryCard(entry, onUndo, entry.said in activity.favorites, activity::toggleFavorite)
                        }
                    }
                }

                if (!showHistory) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(30.dp),
                        color = colors.surface,
                        contentColor = colors.onSurface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            FavoriteCommands(activity.favorites, onInputChange)
                            AssistantComposer(activity, input, onInputChange, onMic, onSend)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FavoriteCommands(commands: List<String>, onSelect: (String) -> Unit) {
    if (commands.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (command in commands) {
            FilterChip(
                selected = false,
                onClick = { onSelect(command) },
                label = { Text(command, Modifier.widthIn(max = 190.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp)) },
                shape = CircleShape,
            )
        }
    }
}

@Composable
internal fun EntryCard(
    entry: Entry,
    onUndo: (Entry) -> Unit,
    favorite: Boolean,
    onFavorite: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val failed = entry.state == CommandState.FAILED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.onSurface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 14.dp)) {
            if (entry.said.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (!entry.conversation) {
                            Text(entry.state.title, style = MaterialTheme.typography.labelMedium,
                                color = if (failed) colors.error else colors.primary)
                        }
                        Text(entry.said, style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onFavorite(entry.said) }) {
                        Icon(
                            if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
                            tint = if (favorite) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
            SelectionContainer {
                Text(
                    entry.result, style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
            }
            if ((entry.conversation && entry.viaAi) || entry.undo != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.conversation && entry.viaAi) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                            tint = colors.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("YandexGPT", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (entry.undo != null) TextButton(onClick = { onUndo(entry) }) { Text("Отменить") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    loadCalendars: () -> List<CalendarInfo>,
    requestCalendarPermission: () -> Unit,
    onBack: () -> Unit,
    onToggleWake: () -> Unit,
    appearance: AssistantAppearance,
    onAppearanceChange: (AssistantAppearance) -> Unit,
) {
    val context = LocalContext.current
    val wakeEnabled by WakeListeningService.enabled.collectAsState()
    var apiKey by remember { mutableStateOf(settings.yandexApiKey) }
    var folderId by remember { mutableStateOf(settings.yandexFolderId) }
    var model by remember { mutableStateOf(settings.yandexModel) }
    var listenOnOpen by remember { mutableStateOf(settings.listenOnOpen) }
    var speakAnswers by remember { mutableStateOf(settings.speakAnswers) }
    var homeCity by remember { mutableStateOf(settings.homeCity) }
    var calendarId by remember { mutableStateOf(settings.calendarId) }
    var calendars by remember { mutableStateOf(loadCalendars()) }

    val save = {
        settings.yandexApiKey = apiKey
        settings.yandexFolderId = folderId
        settings.yandexModel = model
        settings.homeCity = homeCity
        onBack()
    }

    BackHandler(onBack = save)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = save) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Внешний вид", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in AssistantThemeMode.entries) {
                    FilterChip(selected = mode == appearance.mode,
                        onClick = { onAppearanceChange(appearance.copy(mode = mode)) },
                        label = { Text(mode.label) }, shape = CircleShape)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (accent in AssistantAccent.entries) {
                    FilterChip(
                        selected = accent == appearance.accent,
                        onClick = { onAppearanceChange(appearance.copy(accent = accent)) },
                        label = { Text(accent.label) },
                        leadingIcon = { Box(Modifier.size(14.dp).background(accent.light, CircleShape)) },
                        shape = CircleShape,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Крупный текст ответов", Modifier.weight(1f))
                Switch(checked = appearance.largeAnswers,
                    onCheckedChange = { onAppearanceChange(appearance.copy(largeAnswers = it)) })
            }
            HorizontalDivider()

            Text("Помощник по умолчанию", style = MaterialTheme.typography.titleMedium)
            Text(
                "Чтобы вызывать «Секретутку» удержанием кнопки питания, выберите его в " +
                    "Настройки → Приложения → Приложения по умолчанию → Цифровой помощник.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                val intents = listOf(
                    Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
                    Intent(AndroidSettings.ACTION_SETTINGS),
                )
                for (i in intents) {
                    try {
                        context.startActivity(i); break
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }) { Text("Открыть приложения по умолчанию") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Голосовой вызов «Слушай меня»", modifier = Modifier.weight(1f))
                Switch(checked = wakeEnabled, onCheckedChange = { onToggleWake() })
            }
            Text(
                "Первый запуск загрузит около 44 МБ. После этого фраза распознаётся на телефоне. " +
                    "Режим выключается после одной команды или через уведомление.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Слушать сразу при открытии", modifier = Modifier.weight(1f))
                Switch(checked = listenOnOpen, onCheckedChange = {
                    listenOnOpen = it
                    settings.listenOnOpen = it
                })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Озвучивать ответы (погода, курсы, вопросы)", modifier = Modifier.weight(1f))
                Switch(checked = speakAnswers, onCheckedChange = {
                    speakAnswers = it
                    settings.speakAnswers = it
                })
            }
            OutlinedTextField(
                value = homeCity,
                onValueChange = { homeCity = it },
                label = { Text("Город для погоды") },
                supportingText = { Text("Если пусто — по местоположению телефона") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Календарь для событий", style = MaterialTheme.typography.titleMedium)
            if (calendars.isEmpty()) {
                Text("Дайте доступ к календарю, чтобы выбрать, куда записывать события.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = requestCalendarPermission) { Text("Дать доступ") }
                    OutlinedButton(onClick = { calendars = loadCalendars() }) { Text("Обновить список") }
                }
            } else {
                CalendarOption("Автоматически (основной Google-календарь)", calendarId == -1L) {
                    calendarId = -1L; settings.calendarId = -1L
                }
                calendars.forEach { cal ->
                    CalendarOption("${cal.name}\n${cal.account}", calendarId == cal.id) {
                        calendarId = cal.id; settings.calendarId = cal.id
                    }
                }
            }

            HorizontalDivider()
            Text("ИИ для сложных фраз и вопросов (YandexGPT)", style = MaterialTheme.typography.titleMedium)
            Text(
                "YandexGPT отвечает на вопросы и ведёт диалог. Для контекста он получает до четырёх " +
                    "последних реплик текущего сеанса. Нужны API-ключ и ID каталога Yandex Cloud.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = folderId,
                onValueChange = { folderId = it },
                label = { Text("ID каталога (folder id)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model == "yandexgpt-lite/latest",
                    onClick = { model = "yandexgpt-lite/latest" }, label = { Text("Быстрая") })
                FilterChip(selected = model == "yandexgpt/latest",
                    onClick = { model = "yandexgpt/latest" }, label = { Text("Подробная") })
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                supportingText = { Text("Для подробных ответов выбери yandexgpt/latest, если модель доступна твоему тарифу.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = save, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }
}

@Composable
private fun CalendarOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
