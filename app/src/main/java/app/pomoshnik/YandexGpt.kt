package app.pomoshnik

import app.pomoshnik.core.AiFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/** YandexGPT (Yandex Cloud Foundation Models). Reachable from Russia without a VPN. */
object YandexGpt {
    private const val ENDPOINT = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

    data class Turn(val user: String, val answer: String)

    suspend fun complete(settings: Settings, system: String, user: String): String =
        request(settings, JSONArray().put(message("system", system)).put(message("user", user)), 0.1, 300)

    suspend fun reply(settings: Settings, user: String, history: List<Turn>): String {
        val messages = JSONArray().put(message("system", AiFallback.answerPrompt(LocalDateTime.now())))
        history.takeLast(4).forEach { turn ->
            messages.put(message("user", turn.user)).put(message("assistant", turn.answer))
        }
        messages.put(message("user", user))
        return request(settings, messages, 0.45, 750).trim().ifBlank { throw IOException("ИИ вернул пустой ответ") }
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("text", text)

    private suspend fun request(
        settings: Settings, messages: JSONArray, temperature: Double, maxTokens: Int,
    ): String = withContext(Dispatchers.IO) {
        val folder = settings.yandexFolderId
        val body = JSONObject()
            .put("modelUri", "gpt://$folder/${settings.yandexModel}")
            .put("completionOptions", JSONObject().put("stream", false)
                .put("temperature", temperature).put("maxTokens", maxTokens.toString()))
            .put("messages", messages)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Api-Key ${settings.yandexApiKey}")
            setRequestProperty("x-folder-id", folder)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> "проверьте API-ключ, ID каталога и роль ai.languageModels.user"
                    429 -> "слишком много запросов, попробуйте позже"
                    else -> text.take(200)
                }
                throw IOException("YandexGPT ответил $code: $hint")
            }
            JSONObject(text).getJSONObject("result").getJSONArray("alternatives")
                .getJSONObject(0).getJSONObject("message").getString("text")
        } finally {
            conn.disconnect()
        }
    }

    /** Pulls the JSON object out of the model's reply (it sometimes wraps it in ``` fences). */
    fun parseAnswer(reply: String): AiFallback.Answer {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("ИИ вернул ответ не в том формате")
        val o = JSONObject(reply.substring(start, end + 1))
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
        fun int(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }?.toInt()
        val days = o.optJSONArray("days")?.let { a -> List(a.length()) { a.optString(it) } }
        return AiFallback.Answer(
            type = str("type"),
            title = str("title"),
            date = str("date"),
            time = str("time"),
            durationMinutes = int("duration_minutes"),
            days = days,
            timerSeconds = int("timer_seconds"),
            city = str("city"),
            currencies = o.optJSONArray("currencies")?.let { a -> List(a.length()) { a.optString(it) } },
            amount = o.optDouble("amount").takeIf { it.isFinite() },
            answer = str("answer"),
        )
    }
}
