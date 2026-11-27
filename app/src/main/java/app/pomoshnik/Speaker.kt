package app.pomoshnik

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Reads answers aloud in Russian with the phone's text-to-speech engine. */
class Speaker(context: Context) {
    private data class Speech(val text: String, val after: (() -> Unit)?)

    private val handler = Handler(Looper.getMainLooper())
    private var ready: Boolean? = null
    private var queued: Speech? = null
    private var completion: Pair<String, () -> Unit>? = null

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            handler.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready == true) {
                    tts.language = Locale("ru", "RU")
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) = finish(utteranceId)
                        override fun onError(utteranceId: String) = finish(utteranceId)
                    })
                }
                val next = queued
                queued = null
                if (next != null) speak(next.text, next.after)
            }
        }
    }

    fun speak(text: String, after: (() -> Unit)? = null) {
        when (ready) {
            null -> { queued = Speech(text, after); return }
            false -> { after?.invoke(); return }
            true -> Unit
        }
        val spoken = text.replace("°", " градусов").replace("м/с", "метров в секунду")
            .replace("руб.", "рублей").replace("\n", ". ")
        val id = UUID.randomUUID().toString()
        completion = after?.let { id to it }
        if (tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finish(id)
    }

    private fun finish(id: String) {
        handler.post {
            val pending = completion
            if (pending?.first == id) {
                completion = null
                pending.second()
            }
        }
    }

    fun stop() {
        queued = null
        completion = null
        if (ready == true) tts.stop()
    }

    fun shutdown() {
        stop()
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }
}
