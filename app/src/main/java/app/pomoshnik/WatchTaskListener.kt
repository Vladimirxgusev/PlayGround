package app.pomoshnik

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandParser
import app.pomoshnik.core.CommandState
import app.pomoshnik.core.WatchTaskSync
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

class WatchTaskListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith(WatchTaskSync.REQUEST)) continue
            val id = path.removePrefix(WatchTaskSync.REQUEST)
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) continue
            val payload = runCatching { DataMapItem.fromDataItem(event.dataItem).dataMap }.getOrNull() ?: continue
            val text = payload.getString(WatchTaskSync.TEXT)?.trim().orEmpty()
            if (text.isBlank() || text.length > WatchTaskSync.MAX_TEXT_LENGTH) continue
            val enteredAt = payload.getString(WatchTaskSync.ENTERED_AT) ?: continue
            val timestamp = runCatching { LocalDateTime.parse(enteredAt) }.getOrNull() ?: continue
            val command = CommandParser.parse(text, timestamp)

            val work = OneTimeWorkRequestBuilder<WatchTaskWorker>()
                .apply {
                    if (command is Command.CreateTask) {
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    }
                }
                .setInputData(workDataOf("id" to id, WatchTaskSync.TEXT to text, WatchTaskSync.ENTERED_AT to enteredAt,
                    WatchTaskSync.TRACK_FINAL to payload.getBoolean(WatchTaskSync.TRACK_FINAL)))
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork("watch-task-$id", ExistingWorkPolicy.KEEP, work)
        }
    }
}

/** Persist the outcome before acknowledging it so an acknowledgement retry cannot repeat the POST. */
class WatchTaskWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val text = inputData.getString(WatchTaskSync.TEXT) ?: return Result.failure()
        val enteredAt = inputData.getString(WatchTaskSync.ENTERED_AT) ?: return Result.failure()
        val journal = applicationContext.getSharedPreferences("watch-task-results", Context.MODE_PRIVATE)
        val saved = journal.getString(id, null)
        val outcome = when (saved) {
            null -> {
                if (!journal.edit().putString(id, IN_PROGRESS).commit()) return Result.retry()
                executeCommand(id, text, LocalDateTime.parse(enteredAt)).also {
                    CommandHistory(applicationContext).put(Entry(id, text, it.message, it.state, undo = it.undo),
                        unlessFinished = it.state == CommandState.QUEUED)
                }
            }
            IN_PROGRESS -> Outcome(false, "Проверьте результат на телефоне перед повторной отправкой.")
            else -> JSONObject(saved).let {
                val fallback = if (it.getBoolean("ok")) CommandState.DONE else CommandState.FAILED
                Outcome(it.getBoolean("ok"), it.getString("message"),
                    state = CommandState.valueOf(it.optString("state", fallback.name)))
            }
        }

        if (saved == null || saved == IN_PROGRESS) {
            val record = JSONObject().put("ok", outcome.ok).put("message", outcome.message).put("state", outcome.state.name)
            if (!journal.edit().putString(id, record.toString()).commit()) return Result.retry()
        }
        val result = PutDataMapRequest.create(WatchTaskSync.RESULT + id).apply {
            dataMap.putBoolean(WatchTaskSync.OK, outcome.ok)
            dataMap.putString(WatchTaskSync.STATE, outcome.state.name)
            dataMap.putString(WatchTaskSync.MESSAGE, outcome.message)
        }.asPutDataRequest().setUrgent()
        return try {
            Wearable.getDataClient(applicationContext).putDataItem(result).await()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun executeCommand(id: String, text: String, enteredAt: LocalDateTime): Outcome {
        return when (val command = CommandParser.parse(text, enteredAt)) {
            is Command.CreateTask -> createTask(command)
            is Command.SetAlarm -> {
                val queued = WatchAlarms.queue(applicationContext).edit()
                    .putString(id, JSONObject().put("text", text).put("enteredAt", enteredAt.toString())
                        .put("trackFinal", inputData.getBoolean(WatchTaskSync.TRACK_FINAL, false)).toString())
                    .commit()
                if (queued) {
                    Outcome(true, "Телефон получил будильник. Открой «Секретутку» на телефоне.", state = CommandState.QUEUED)
                } else {
                    Outcome(false, "Телефон не смог сохранить команду будильника.")
                }
            }
            else -> Outcome(false, "С часов можно передать задачу или будильник.")
        }
    }

    private suspend fun createTask(command: Command.CreateTask): Outcome {
        return try {
            val outcome = GoogleTasks(applicationContext).create(command)
            outcome.copy(message = "Добавлено в Google Задачи: ${command.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleTasksConsentRequired) {
            Outcome(false, e.message.orEmpty())
        } catch (_: Exception) {
            Outcome(false, "Нет подтверждения от Google. Проверьте Задачи на телефоне перед повторной отправкой.")
        }
    }

    private companion object {
        const val IN_PROGRESS = "in-progress"
    }
}
