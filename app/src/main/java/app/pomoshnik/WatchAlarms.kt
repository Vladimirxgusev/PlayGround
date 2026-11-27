package app.pomoshnik

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pomoshnik.core.CommandState
import app.pomoshnik.core.WatchTaskSync
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandParser
import org.json.JSONObject
import java.time.LocalDateTime

internal object WatchAlarms {
    data class Pending(val id: String, val text: String, val command: Command.SetAlarm, val trackFinal: Boolean)

    fun queue(context: Context): SharedPreferences =
        context.getSharedPreferences("watch-alarms", Context.MODE_PRIVATE)

    fun next(queue: SharedPreferences): Pending? = queue.all.asSequence()
        .mapNotNull { (id, value) ->
            val payload = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
            val enteredAt = runCatching { LocalDateTime.parse(payload.getString("enteredAt")) }.getOrNull()
                ?: return@mapNotNull null
            val text = payload.optString("text")
            val command = CommandParser.parse(text, enteredAt) as? Command.SetAlarm ?: return@mapNotNull null
            enteredAt to Pending(id, text, command, payload.optBoolean("trackFinal"))
        }
        .minByOrNull { it.first }
        ?.second

    fun complete(context: Context, alarm: Pending, result: Outcome) {
        CommandHistory(context).put(Entry(alarm.id, alarm.text, result.message, result.state))
        if (!alarm.trackFinal) return
        val work = OneTimeWorkRequestBuilder<WatchAlarmStatusWorker>().setInputData(
            workDataOf("id" to alarm.id, WatchTaskSync.OK to result.ok,
                WatchTaskSync.STATE to result.state.name, WatchTaskSync.MESSAGE to result.message),
        ).build()
        WorkManager.getInstance(context).enqueueUniqueWork("watch-alarm-result-${alarm.id}", ExistingWorkPolicy.KEEP, work)
    }
}

class WatchAlarmStatusWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val request = PutDataMapRequest.create(WatchTaskSync.FINAL + id).apply {
            dataMap.putBoolean(WatchTaskSync.OK, inputData.getBoolean(WatchTaskSync.OK, false))
            dataMap.putString(WatchTaskSync.STATE, inputData.getString(WatchTaskSync.STATE) ?: CommandState.FAILED.name)
            dataMap.putString(WatchTaskSync.MESSAGE, inputData.getString(WatchTaskSync.MESSAGE).orEmpty())
        }.asPutDataRequest().setUrgent()
        return try {
            Wearable.getDataClient(applicationContext).putDataItem(request).await()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
