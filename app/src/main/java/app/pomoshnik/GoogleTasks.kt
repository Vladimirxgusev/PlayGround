package app.pomoshnik

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.pomoshnik.core.Command
import app.pomoshnik.core.Describe
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes tasks to Google Tasks through its REST API. Sign-in uses Google Play services
 * authorization: the first time, the user picks an account and allows access to Tasks.
 */
class GoogleTasks(private val activity: Activity) {

    private var consentLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingConsent: CompletableDeferred<String?>? = null

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    fun attach(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        consentLauncher = launcher
    }

    /** Called by the activity with the consent screen's result. */
    fun onConsentResult(data: Intent?) {
        val token = try {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
        } catch (_: ApiException) {
            null
        }
        pendingConsent?.complete(token)
        pendingConsent = null
    }

    /** Returns an access token, showing the account/consent screen if needed. */
    private suspend fun accessToken(): String {
        val result = try {
            Identity.getAuthorizationClient(activity).authorize(request).await()
        } catch (e: ApiException) {
            throw IOException(explain(e))
        }
        if (!result.hasResolution()) {
            return result.accessToken ?: throw IOException("Google не выдал доступ к Задачам")
        }
        val launcher = consentLauncher ?: throw IOException("Окно входа недоступно")
        val deferred = CompletableDeferred<String?>()
        pendingConsent = deferred
        launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
        return deferred.await() ?: throw IOException("Доступ к Google Задачам не выдан")
    }

    suspend fun create(c: Command.CreateTask): Outcome {
        val body = JSONObject().put("title", c.title)
        c.time?.let { body.put("notes", "Время: ${Describe.time(it)}") }
        c.date?.let { body.put("due", "${it}T00:00:00.000Z") }

        val reply = call("POST", "$BASE/lists/@default/tasks", body.toString())
        val id = JSONObject(reply).getString("id")
        val timeNote = if (c.time != null) "\nGoogle Задачи хранят только дату, время записано в заметку." else ""
        return Outcome(true, Describe.command(c) + "\nДобавлено в Google Задачи." + timeNote) {
            // Undo runs on the main thread from a button; the network call goes to a worker thread.
            Thread { runCatching { kotlinx.coroutines.runBlocking { call("DELETE", "$BASE/lists/@default/tasks/$id", null) } } }
                .start()
            "Задача «${c.title}» удалена из Google Задач"
        }
    }

    private suspend fun call(method: String, url: String, body: String?, retried: Boolean = false): String {
        val token = accessToken()
        val (code, text) = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                conn.disconnect()
            }
        }
        if (code == 401 && !retried) {
            // Expired token: drop it from the cache and try once more.
            withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(activity, token) } }
            return call(method, url, body, retried = true)
        }
        if (code !in 200..299) {
            val hint = when (code) {
                403 -> "в Google Cloud не включён Google Tasks API или нет доступа"
                else -> text.take(200)
            }
            throw IOException("Google Задачи ответили $code: $hint")
        }
        return text
    }

    private fun explain(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "приложение не зарегистрировано в Google Cloud (нужен OAuth-клиент Android с SHA-1 из сборки)"
        CommonStatusCodes.NETWORK_ERROR -> "нет связи с Google"
        CommonStatusCodes.SIGN_IN_REQUIRED -> "нужно войти в Google-аккаунт"
        else -> "ошибка входа Google (${e.statusCode})"
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/tasks"
        private const val BASE = "https://tasks.googleapis.com/tasks/v1"
    }
}
