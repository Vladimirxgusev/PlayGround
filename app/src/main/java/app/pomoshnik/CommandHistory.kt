package app.pomoshnik

import android.content.Context
import android.content.SharedPreferences
import app.pomoshnik.core.CommandState
import org.json.JSONArray
import org.json.JSONObject

data class UndoAction(val kind: Kind, val id: String) {
    enum class Kind { EVENT, TASK }
}

data class Entry(
    val id: String,
    val said: String,
    val result: String,
    val state: CommandState,
    val viaAi: Boolean = false,
    val undo: UndoAction? = null,
)

internal class CommandHistory(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("command-history", Context.MODE_PRIVATE)

    fun entries(): List<Entry> {
        val rows = JSONArray(preferences.getString("entries", "[]"))
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val undo = row.optJSONObject("undo")?.let {
                UndoAction(UndoAction.Kind.valueOf(it.getString("kind")), it.getString("id"))
            }
            Entry(row.getString("id"), row.getString("text"), row.getString("result"),
                CommandState.valueOf(row.getString("state")), row.optBoolean("ai"), undo)
        }
    }

    fun put(entry: Entry, unlessFinished: Boolean = false) = synchronized(lock) {
        val rows = entries()
        val previous = rows.firstOrNull { it.id == entry.id }
        if (unlessFinished && previous != null && previous.state != CommandState.QUEUED) return@synchronized
        val updated = (listOf(entry) + rows.filterNot { it.id == entry.id }).take(60)
        val json = JSONArray()
        for (item in updated) {
            json.put(JSONObject().put("id", item.id).put("text", item.said).put("result", item.result)
                .put("state", item.state.name).put("ai", item.viaAi).put("undo", item.undo?.let {
                    JSONObject().put("kind", it.kind.name).put("id", it.id)
                }))
        }
        preferences.edit().putString("entries", json.toString()).apply()
    }

    fun favorites(): List<String> {
        val list = JSONArray(preferences.getString("favorites", "[]"))
        return (0 until list.length()).map(list::getString)
    }

    fun toggleFavorite(text: String) = synchronized(lock) {
        val pinned = favorites()
        val updated = if (text in pinned) pinned - text else listOf(text) + pinned
        preferences.edit().putString("favorites", JSONArray(updated.take(8)).toString()).apply()
    }

    private companion object {
        val lock = Any()
    }
}
