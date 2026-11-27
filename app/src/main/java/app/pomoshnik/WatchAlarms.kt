package app.pomoshnik

import android.content.Context
import android.content.SharedPreferences
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandParser
import org.json.JSONObject
import java.time.LocalDateTime

internal object WatchAlarms {
    data class Pending(val id: String, val text: String, val command: Command.SetAlarm)

    fun queue(context: Context): SharedPreferences =
        context.getSharedPreferences("watch-alarms", Context.MODE_PRIVATE)

    fun next(queue: SharedPreferences): Pending? = queue.all.asSequence()
        .mapNotNull { (id, value) ->
            val payload = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
            val enteredAt = runCatching { LocalDateTime.parse(payload.getString("enteredAt")) }.getOrNull()
                ?: return@mapNotNull null
            val text = payload.optString("text")
            val command = CommandParser.parse(text, enteredAt) as? Command.SetAlarm ?: return@mapNotNull null
            enteredAt to Pending(id, text, command)
        }
        .minByOrNull { it.first }
        ?.second
}
