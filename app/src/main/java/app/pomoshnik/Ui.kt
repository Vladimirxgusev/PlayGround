package app.pomoshnik

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val examples = listOf(
    "Поставь будильник на 7:30",
    "Напомни завтра в 10 позвонить маме",
    "Встреча с врачом в четверг в 14:00",
    "Какая погода завтра?",
    "Курс доллара",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onUndo: (Entry) -> Unit,
    onSettings: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val send = {
        if (input.isNotBlank()) {
            onSend(input)
            input = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Секретутка") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true,
            ) {
                items(activity.history, key = { it.id }) { entry -> EntryCard(entry, onUndo) }
                if (activity.history.isEmpty()) {
                    item { Hints(onExample = { onSend(it) }) }
                }
            }

            val status = when {
                activity.busy -> "Выполняю…"
                activity.listening && activity.partial.isNotEmpty() -> activity.partial
                activity.listening -> "Слушаю…"
                else -> ""
            }
            if (status.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activity.busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                LargeFloatingActionButton(onClick = onMic) {
                    Icon(
                        if (activity.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (activity.listening) "Остановить" else "Сказать команду",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Или напишите команду") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                IconButton(onClick = send, enabled = input.isNotBlank() && !activity.busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@Composable
private fun Hints(onExample: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Нажмите на микрофон и скажите, например:", style = MaterialTheme.typography.titleMedium)
        examples.forEach { ex ->
            OutlinedButton(onClick = { onExample(ex) }, modifier = Modifier.fillMaxWidth()) { Text(ex) }
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, onUndo: (Entry) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.ok) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (entry.said.isNotEmpty()) {
                Text("«${entry.said}»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (entry.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(entry.result, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (entry.viaAi || entry.undo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.viaAi) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("понял с помощью YandexGPT", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    if (entry.undo != null) TextButton(onClick = { onUndo(entry) }) { Text("Отменить") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    loadCalendars: () -> List<CalendarInfo>,
    requestCalendarPermission: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
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
                "Простые команды понимаются без интернета. Фразы, которые правила не поняли, " +
                    "отправляются в YandexGPT — он работает в России без VPN. " +
                    "Нужны API-ключ сервисного аккаунта с ролью ai.languageModels.user и ID каталога из Yandex Cloud.",
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
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                supportingText = { Text("yandexgpt-lite/latest — быстрее и дешевле, yandexgpt/latest — умнее") },
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
