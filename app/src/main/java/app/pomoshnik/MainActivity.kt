package app.pomoshnik

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import app.pomoshnik.core.AiFallback
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandParser
import app.pomoshnik.core.CommandState
import java.util.UUID
import app.pomoshnik.core.InfoFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

open class MainActivity : ComponentActivity() {

    protected open val launchesAsPanel = false
    private var compactPanel by mutableStateOf(false)

    private lateinit var settings: Settings
    private lateinit var actions: Actions
    private lateinit var speech: SpeechInput
    private lateinit var googleTasks: GoogleTasks
    private lateinit var info: InfoService
    private lateinit var speaker: Speaker
    private val watchAlarms by lazy { WatchAlarms.queue(this) }
    private val watchAlarmListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            handleWatchAlarm()
        }
    }
    private var pendingWeather: Pair<String, Command.Weather>? = null

    // UI state
    val history = mutableStateListOf<Entry>()
    var listening by mutableStateOf(false)
    var partial by mutableStateOf("")
    var busy by mutableStateOf(false)
    var showSettings by mutableStateOf(false)

    private val commandHistory by lazy { CommandHistory(this) }
    val favorites = mutableStateListOf<String>()
    var clarification by mutableStateOf<String?>(null)
        private set
    private var alarmPhrase: String? = null
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshHistory() }
    private var pendingEvent: Pair<String, Command.CreateEvent>? = null
    private var pendingViaAi = false
    private var listenAfterMicGrant = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && listenAfterMicGrant) startListening()
        else if (!granted) addInfo("Без доступа к микрофону можно вводить команды текстом")
        listenAfterMicGrant = false
    }

    private val wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredWakePermissions().isEmpty()) startWakeWhenVisible()
        else addInfo("Для голосового вызова нужны доступ к микрофону и уведомлениям.")
    }

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val (said, event) = pendingEvent ?: return@registerForActivityResult
            pendingEvent = null
            val outcome = if (result.values.all { it }) actions.createEvent(event)
            else actions.createEventViaCalendarApp(event)
            addEntry(said, outcome, pendingViaAi)
        }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val (said, weather) = pendingWeather ?: return@registerForActivityResult
        pendingWeather = null
        answer(said, pendingViaAi) { info.weather(weather) }
    }

    private val googleConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            googleTasks.onConsentResult(res.data)
        }

    /** Used only when in-app recognition is unavailable: the system speech dialog. */
    private val speechDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        listening = false
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) handle(text, fromVoice = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!launchesAsPanel && isVoiceInvocation(intent?.action)) {
            openAssistPanel(intent.action)
            finish()
            return
        }
        compactPanel = savedInstanceState?.getBoolean("compactPanel") ?: launchesAsPanel
        installCrashReporter()
        enableEdgeToEdge()
        settings = Settings(this)
        commandHistory.preferences.registerOnSharedPreferenceChangeListener(historyListener)
        refreshHistory()
        actions = Actions(this, settings)
        googleTasks = GoogleTasks(this).also { it.attach(googleConsent) }
        info = InfoService(this, settings)
        speaker = Speaker(this)
        speech = SpeechInput(
            context = this,
            onPartial = { partial = it },
            onResult = { partial = ""; handle(it, fromVoice = true) },
            onError = { partial = ""; addInfo(it) },
            onListening = { listening = it },
        )

        WakeListeningService.restoreCommand(this)
        setContent {
            var input by rememberSaveable { mutableStateOf("") }
            val wakeCommand by WakeListeningService.commands.collectAsState()
            LaunchedEffect(wakeCommand) {
                wakeCommand?.let { input = it }
            }
            val onInputChange: (String) -> Unit = {
                input = it
                if (it.isBlank() && wakeCommand != null) WakeListeningService.clearCommand(this)
            }
            val onSend: (String) -> Unit = {
                if (it.isNotBlank() && !busy) {
                    WakeListeningService.clearCommand(this)
                    handle(it)
                    input = ""
                }
            }
            val onMic = { if (listening) speech.stop() else startListening() }
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(
                    primary = Color(0xFFAAC8FF),
                    onPrimary = Color(0xFF0C2350),
                    tertiary = Color(0xFFB6AAFF),
                    primaryContainer = Color(0xFF202E50),
                    background = Color(0xFF101522),
                    surface = Color(0xFF1B2336),
                    surfaceVariant = Color(0xFF303A50),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF315AC2),
                    onPrimary = Color.White,
                    tertiary = Color(0xFF6545BF),
                    primaryContainer = Color(0xFFE0E8FF),
                    background = Color(0xFFF5F7FF),
                    surface = Color(0xFFFCFCFF),
                    surfaceVariant = Color(0xFFE3E8F5),
                )
            }
            MaterialTheme(colorScheme = colors) {
                if (compactPanel) {
                    AssistPanel(
                        activity = this,
                        input = input,
                        onInputChange = onInputChange,
                        onMic = onMic,
                        onSend = onSend,
                        onUndo = { undo(it) },
                        onExpand = { compactPanel = false },
                        onClose = { finish() },
                    )
                } else if (showSettings) {
                    SettingsScreen(
                        settings = settings,
                        loadCalendars = { actions.listCalendars() },
                        requestCalendarPermission = {
                            calendarPermission.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                            )
                        },
                        onBack = { showSettings = false },
                        onToggleWake = { toggleWakeListening() },
                    )
                } else {
                    MainScreen(
                        activity = this,
                        input = input,
                        onInputChange = onInputChange,
                        onMic = onMic,
                        onSend = onSend,
                        onUndo = { entry -> undo(entry) },
                        onSettings = { speech.stop(); showSettings = true },
                    )
                }
            }
        }

        val crash = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).getString("last", null)
        if (crash != null) {
            getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit().remove("last").apply()
            addInfo("В прошлый раз приложение закрылось из-за ошибки. Пришлите этот текст разработчику:\n$crash")
        } else if (savedInstanceState == null) {
            handleLaunchIntent(intent, firstLaunch = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isVoiceInvocation(intent.action)) {
            if (!launchesAsPanel) {
                openAssistPanel(intent.action)
                return
            }
            compactPanel = true
        }
        handleLaunchIntent(intent, firstLaunch = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("compactPanel", compactPanel)
    }

    private fun openAssistPanel(action: String?) {
        startActivity(Intent(this, AssistPanelActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isVoiceInvocation(action: String?) =
        action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND || action == ACTION_LISTEN

    override fun onPostResume() {
        super.onPostResume()
        if (::actions.isInitialized) {
            watchAlarms.registerOnSharedPreferenceChangeListener(watchAlarmListener)
            handleWatchAlarm()
        }
    }

    override fun onPause() {
        if (::actions.isInitialized) watchAlarms.unregisterOnSharedPreferenceChangeListener(watchAlarmListener)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::speech.isInitialized) speech.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speaker.isInitialized) {
            commandHistory.preferences.unregisterOnSharedPreferenceChangeListener(historyListener)
            speaker.shutdown()
        }
    }

    private fun handleWatchAlarm() {
        val alarm = WatchAlarms.next(watchAlarms) ?: return
        // Dispatch only while this activity is visible. Prevent duplicate dispatch if Clock takes focus.
        watchAlarms.unregisterOnSharedPreferenceChangeListener(watchAlarmListener)
        val outcome = try {
            if (!watchAlarms.edit().remove(alarm.id).commit()) return
            actions.setAlarm(alarm.command)
        } finally {
            watchAlarms.registerOnSharedPreferenceChangeListener(watchAlarmListener)
        }
        WatchAlarms.complete(this, alarm, outcome)
    }

    private fun handleLaunchIntent(intent: Intent?, firstLaunch: Boolean) {
        val action = intent?.action
        if (action == WakeListeningService.OPEN) {
            showSettings = false
            return
        }
        if (WakeListeningService.commands.value != null) return
        val voiceLaunch = isVoiceInvocation(action)
        val autoListen = firstLaunch && settings.listenOnOpen && !WakeListeningService.enabled.value
        if (voiceLaunch || autoListen) {
            showSettings = false
            startListening()
        }
    }

    private fun requiredWakePermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun toggleWakeListening() {
        if (WakeListeningService.enabled.value) {
            stopService(Intent(this, WakeListeningService::class.java))
            return
        }
        val permissions = requiredWakePermissions()
        if (permissions.isEmpty()) startWakeWhenVisible()
        else wakePermissions.launch(permissions.toTypedArray())
    }

    private fun startWakeWhenVisible() {
        lifecycleScope.launch {
            lifecycle.withResumed {
                speech.stop()
                speaker.stop()
                try {
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeListeningService::class.java))
                    showSettings = false
                } catch (_: RuntimeException) {
                    addInfo("Не удалось включить голосовой вызов.")
                }
            }
        }
    }

    fun startListening() {
        if (WakeListeningService.enabled.value) {
            stopService(Intent(this, WakeListeningService::class.java))
            lifecycleScope.launch {
                WakeListeningService.enabled.first { !it }
                lifecycle.withResumed { startListening() }
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listenAfterMicGrant = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        partial = ""
        speaker.stop()
        if (speech.isAvailable) {
            speech.start()
        } else {
            try {
                listening = true
                speechDialog.launch(SpeechInput.recognizerIntent())
            } catch (_: Exception) {
                listening = false
                addInfo("На телефоне нет сервиса распознавания речи. Установите приложение Google или вводите текстом.")
            }
        }
    }

    /** Rules first; if they fail and YandexGPT is set up, ask it. Then run the command. */
    fun handle(said: String, fromVoice: Boolean = false) {
        val reply = said.trim()
        if (reply.isEmpty() || busy) return
        val previous = alarmPhrase
        val parsed = CommandParser.parse(reply)
        if (previous != null && reply.equals("отмена", ignoreCase = true)) {
            cancelClarification()
            return
        }
        val continued = previous?.let { CommandParser.completeAlarm(it, reply) }
        if (previous != null && parsed is Command.Unknown && !parsed.needsAlarmTime && continued == null) {
            askAlarmTime(previous, fromVoice)
            return
        }
        cancelClarification()
        val text = if (continued != null) "$previous $reply" else reply
        val initial = continued ?: parsed
        if (initial is Command.Unknown && initial.needsAlarmTime) {
            askAlarmTime(text, fromVoice)
            return
        }
        lifecycleScope.launch {
            var command = initial
            var viaAi = false
            if (command is Command.Unknown) {
                if (settings.aiConfigured) {
                    busy = true
                    viaAi = true
                    command = try {
                        val reply = YandexGpt.complete(settings, AiFallback.systemPrompt(LocalDateTime.now()), text)
                        AiFallback.toCommand(YandexGpt.parseAnswer(reply), text, LocalDateTime.now())
                    } catch (e: Exception) {
                        Command.Unknown(text, "ИИ недоступен: ${e.message}")
                    } finally {
                        busy = false
                    }
                } else {
                    command = command.copy(
                        reason = command.reason + ". Подключите YandexGPT в настройках, чтобы понимать любые фразы.",
                    )
                }
            }
            run(text, command, viaAi)
        }
    }

    private fun run(said: String, command: Command, viaAi: Boolean) {
        when (command) {
            is Command.SetAlarm -> addEntry(said, actions.setAlarm(command), viaAi)
            is Command.SetTimer -> addEntry(said, actions.setTimer(command), viaAi)
            is Command.CreateTask -> lifecycleScope.launch {
                busy = true
                val outcome = try {
                    googleTasks.create(command)
                } catch (e: Exception) {
                    actions.createTaskViaClipboard(command, e.message ?: "неизвестная ошибка")
                } finally {
                    busy = false
                }
                addEntry(said, outcome, viaAi)
            }
            is Command.Unknown -> addEntry(said, Outcome(false, command.reason), viaAi)
            is Command.Now -> answer(said, viaAi) { InfoFormat.now(command.kind, LocalDateTime.now()) }
            is Command.Currency -> answer(said, viaAi) { info.rates(command) }
            is Command.Answer -> answer(said, viaAi = true) { command.text }
            is Command.Question -> if (settings.aiConfigured) {
                answer(said, viaAi = true) {
                    YandexGpt.complete(settings, AiFallback.answerPrompt(LocalDateTime.now()), command.text).trim()
                }
            } else {
                addEntry(said, Outcome(false, "Чтобы отвечать на любые вопросы, подключите YandexGPT в настройках"), viaAi)
            }
            is Command.Weather -> {
                val needLocation = command.city == null && settings.homeCity.isBlank() &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                if (needLocation) {
                    pendingWeather = said to command
                    pendingViaAi = viaAi
                    locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                } else {
                    answer(said, viaAi) { info.weather(command) }
                }
            }
            is Command.CreateEvent -> {
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    addEntry(said, actions.createEvent(command), viaAi)
                } else {
                    pendingEvent = said to command
                    pendingViaAi = viaAi
                    calendarPermission.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                }
            }
        }
    }

    /** Fetches an answer, shows it and (if enabled) reads it aloud. */
    private fun answer(said: String, viaAi: Boolean, fetch: suspend () -> String) {
        lifecycleScope.launch {
            busy = true
            val outcome = try {
                Outcome(true, fetch())
            } catch (e: Exception) {
                Outcome(false, "Не получилось ответить: ${e.message}")
            } finally {
                busy = false
            }
            addEntry(said, outcome, viaAi)
            if (settings.speakAnswers) speaker.speak(outcome.message)
        }
    }

    private fun askAlarmTime(text: String, fromVoice: Boolean) {
        alarmPhrase = text
        clarification = "На какое время поставить будильник?"
        if (fromVoice) {
            speaker.speak(requireNotNull(clarification)) {
                if (alarmPhrase != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    startListening()
                }
            }
        }
    }

    fun cancelClarification() {
        alarmPhrase = null
        clarification = null
    }

    fun toggleFavorite(text: String) {
        if (text.isNotBlank()) commandHistory.toggleFavorite(text)
    }

    private fun refreshHistory() {
        history.clear()
        history.addAll(commandHistory.entries())
        favorites.clear()
        favorites.addAll(commandHistory.favorites())
    }

    private fun undo(entry: Entry) {
        val action = entry.undo ?: return
        if (busy) return
        lifecycleScope.launch {
            busy = true
            try {
                val message = when (action.kind) {
                    UndoAction.Kind.EVENT -> { actions.deleteEvent(action.id); "Событие отменено" }
                    UndoAction.Kind.TASK -> { googleTasks.delete(action.id); "Задача отменена" }
                }
                commandHistory.put(entry.copy(result = message, state = CommandState.UNDONE, undo = null))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                commandHistory.put(entry.copy(result = "Не удалось отменить: ${e.message}", state = CommandState.FAILED))
            } finally {
                busy = false
            }
        }
    }

    private fun addEntry(said: String, outcome: Outcome, viaAi: Boolean) {
        commandHistory.put(Entry(UUID.randomUUID().toString(), said, outcome.message, outcome.state, viaAi, outcome.undo))
    }

    private fun addInfo(message: String) {
        addEntry("", Outcome(false, message), viaAi = false)
    }

    /** Saves the stack trace of a crash so the next launch can show it. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashSaver) return
        Thread.setDefaultUncaughtExceptionHandler(CrashSaver(applicationContext, previous))
    }

    private class CrashSaver(
        private val context: android.content.Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val trace = e.stackTraceToString().lines().take(25).joinToString("\n")
                context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString("last", "версия ${BuildConfig.VERSION_NAME}\n$trace").commit()
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val CRASH_PREFS = "crash"
        const val ACTION_LISTEN = "app.pomoshnik.LISTEN"
    }
}

/** The system assistant entry point shares the command session with its expanded UI. */
class AssistPanelActivity : MainActivity() {
    override val launchesAsPanel = true
}
