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
import androidx.compose.runtime.getValue
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
import app.pomoshnik.core.InfoFormat
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** One line in the history: what was said and what happened. */
data class Entry(
    val id: Long,
    val said: String,
    val result: String,
    val ok: Boolean,
    val viaAi: Boolean = false,
    val undo: (() -> String)? = null,
)

class MainActivity : ComponentActivity() {

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

    private var nextId = 0L
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
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) handle(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        enableEdgeToEdge()
        settings = Settings(this)
        actions = Actions(this, settings)
        googleTasks = GoogleTasks(this).also { it.attach(googleConsent) }
        info = InfoService(this, settings)
        speaker = Speaker(this)
        speech = SpeechInput(
            context = this,
            onPartial = { partial = it },
            onResult = { partial = ""; handle(it) },
            onError = { partial = ""; addInfo(it) },
            onListening = { listening = it },
        )

        WakeListeningService.restoreCommand(this)
        setContent {
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
                if (showSettings) {
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
                        onMic = { if (listening) speech.stop() else startListening() },
                        onSend = { handle(it) },
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
        handleLaunchIntent(intent, firstLaunch = false)
    }

    override fun onPostResume() {
        super.onPostResume()
        watchAlarms.registerOnSharedPreferenceChangeListener(watchAlarmListener)
        handleWatchAlarm()
    }

    override fun onPause() {
        watchAlarms.unregisterOnSharedPreferenceChangeListener(watchAlarmListener)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        speech.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker.shutdown()
    }

    private fun handleWatchAlarm() {
        val alarm = WatchAlarms.next(watchAlarms) ?: return
        // Dispatch only while this activity is visible. Prevent duplicate dispatch if Clock takes focus.
        watchAlarms.unregisterOnSharedPreferenceChangeListener(watchAlarmListener)
        val outcome = try {
            if (watchAlarms.edit().remove(alarm.id).commit()) {
                actions.setAlarm(alarm.command)
            } else {
                Outcome(false, "Не удалось обработать будильник с часов. Откройте приложение ещё раз.")
            }
        } finally {
            watchAlarms.registerOnSharedPreferenceChangeListener(watchAlarmListener)
        }
        addEntry(alarm.text, outcome, viaAi = false)
    }

    private fun handleLaunchIntent(intent: Intent?, firstLaunch: Boolean) {
        val action = intent?.action
        if (action == WakeListeningService.OPEN) {
            showSettings = false
            return
        }
        if (WakeListeningService.enabled.value || WakeListeningService.commands.value != null) return
        val voiceLaunch = action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND ||
            action == ACTION_LISTEN
        if (voiceLaunch || (firstLaunch && settings.listenOnOpen)) {
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
            addInfo("Ожидание фразы выключено. Нажми микрофон ещё раз для диктовки.")
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
    fun handle(said: String) {
        val text = said.trim()
        if (text.isEmpty() || busy) return
        lifecycleScope.launch {
            var command = CommandParser.parse(text)
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

    private fun undo(entry: Entry) {
        val undo = entry.undo ?: return
        val message = try { undo() } catch (e: Exception) { "Не удалось отменить: ${e.message}" }
        val i = history.indexOfFirst { it.id == entry.id }
        if (i >= 0) history[i] = entry.copy(undo = null, result = message, ok = true)
    }

    private fun addEntry(said: String, outcome: Outcome, viaAi: Boolean) {
        history.add(0, Entry(nextId++, said, outcome.message, outcome.ok, viaAi, outcome.undo))
    }

    private fun addInfo(message: String) {
        history.add(0, Entry(nextId++, "", message, ok = false))
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
