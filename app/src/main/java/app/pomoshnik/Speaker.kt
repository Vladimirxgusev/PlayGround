package app.pomoshnik

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads answers aloud in Russian with the phone's text-to-speech engine. */
class Speaker(context: Context) {
    private var ready = false
    private var queued: String? = null

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts.language = Locale("ru", "RU")
                queued?.let { speak(it) }
                queued = null
            }
        }
    }

    fun speak(text: String) {
        if (!ready) {
            queued = text
            return
        }
        // Symbols the engine would otherwise skip or spell out.
        val spoken = text
            .replace("°", " градусов")
            .replace("м/с", "метров в секунду")
            .replace("руб.", "рублей")
            .replace("\n", ". ")
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
