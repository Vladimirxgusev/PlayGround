package app.pomoshnik

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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

        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
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

        if (savedInstanceState == null) handleLaunchIntent(intent, firstLaunch = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent, firstLaunch = false)
    }

    override fun onStop() {
        super.onStop()
        speech.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker.shutdown()
    }

    private fun handleLaunchIntent(intent: Intent?, firstLaunch: Boolean) {
        val action = intent?.action
        val voiceLaunch = action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND ||
            action == ACTION_LISTEN
        if (voiceLaunch || (firstLaunch && settings.listenOnOpen)) {
            showSettings = false
            startListening()
        }
    }

    fun startListening() {
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

    companion object {
        const val ACTION_LISTEN = "app.pomoshnik.LISTEN"
    }
}
