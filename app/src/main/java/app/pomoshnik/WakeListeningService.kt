package app.pomoshnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.pomoshnik.core.WakePhrase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class WakeListeningService : Service(), RecognitionListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var tone: ToneGenerator? = null
    private var awaitingCommand = false
    private var active = false
    private val expireCommand = Runnable {
        awaitingCommand = false
        if (active) showWaiting()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        active = true
        running.value = true
        notifications.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL, "Ожидание голосовой фразы", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RESULT_CHANNEL, "Распознанные команды", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
        startForeground(LISTENING_NOTIFICATION, listeningNotification("Подготовка распознавания…"))
        state.value = "Подготовка распознавания…"
        scope.launch {
            try {
                val directory = runInterruptible(Dispatchers.IO) { WakeSpeechModel.directory(filesDir) }
                model = Model(directory.absolutePath)
                val engine = Recognizer(requireNotNull(model), SAMPLE_RATE)
                recognizer = engine
                val microphone = SpeechService(engine, SAMPLE_RATE)
                speech = microphone
                if (!microphone.startListening(this@WakeListeningService)) error("Microphone is busy")
                showWaiting()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail("Не удалось включить голосовой вызов. Проверьте интернет и доступ к микрофону.")
            }
        }
        return START_NOT_STICKY
    }

    private fun showWaiting() {
        val message = "Жду «Слушай меня»"
        state.value = message
        notifications.notify(LISTENING_NOTIFICATION, listeningNotification(message))
    }

    override fun onResult(hypothesis: String?) {
        if (!active) return
        val text = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
        if (text.isBlank()) return
        val command = WakePhrase.commandAfter(text)
        when {
            command != null && command.isNotBlank() -> finishCommand(command)
            command != null -> {
                awaitingCommand = true
                state.value = "Скажи команду"
                notifications.notify(LISTENING_NOTIFICATION, listeningNotification("Скажи команду"))
                runCatching {
                    val player = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 45).also { tone = it }
                    player.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                }
                handler.removeCallbacks(expireCommand)
                handler.postDelayed(expireCommand, 8_000)
            }
            awaitingCommand -> finishCommand(text)
        }
    }

    private fun finishCommand(command: String) {
        active = false
        val store = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!store.edit().putString(DRAFT, command).commit()) {
            fail("Не удалось сохранить голосовую команду.")
            return
        }
        draft.value = command
        notifications.notify(
            READY_NOTIFICATION,
            notification("Команда распознана. Нажми, чтобы проверить и отправить.", RESULT_CHANNEL)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    override fun onPartialResult(hypothesis: String?) = Unit
    override fun onFinalResult(hypothesis: String?) = Unit
    override fun onTimeout() = stopSelf()
    override fun onError(exception: Exception?) {
        if (active) fail("Микрофон недоступен. Включи голосовой вызов ещё раз.")
    }

    private fun fail(message: String) {
        active = false
        state.value = message
        notifications.notify(READY_NOTIFICATION, notification(message).setContentIntent(openApp()).build())
        stopSelf()
    }

    private fun notification(text: String, channel: String = CHANNEL) = Notification.Builder(this, channel)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Секретутка")
        .setContentText(text)
        .setVisibility(Notification.VISIBILITY_PRIVATE)

    private fun listeningNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeListeningService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return notification(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        val wasActive = active
        active = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        speech?.stop()
        speech?.shutdown()
        recognizer?.close()
        model?.close()
        tone?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        running.value = false
        if (wasActive || draft.value != null) state.value = null
        super.onDestroy()
    }

    companion object {
        const val OPEN = "app.pomoshnik.OPEN_VOICE"
        private const val STOP = "app.pomoshnik.STOP_VOICE"
        private const val CHANNEL = "wake-listening"
        private const val RESULT_CHANNEL = "wake-command"
        private const val PREFS = "wake-listening"
        private const val DRAFT = "draft"
        private const val LISTENING_NOTIFICATION = 5601
        private const val READY_NOTIFICATION = 5602
        private const val SAMPLE_RATE = 16_000f

        private val running = MutableStateFlow(false)
        val enabled = running.asStateFlow()
        private val state = MutableStateFlow<String?>(null)
        val status = state.asStateFlow()
        private val draft = MutableStateFlow<String?>(null)
        val commands = draft.asStateFlow()

        fun restoreCommand(context: Context) {
            draft.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DRAFT, null)
        }

        fun clearCommand(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DRAFT).apply()
            context.getSystemService(NotificationManager::class.java).cancel(READY_NOTIFICATION)
            draft.value = null
        }
    }
}
