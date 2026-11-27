package app.pomoshnik.wear

import android.content.Context
import app.pomoshnik.core.CommandState
import org.json.JSONArray
import org.json.JSONObject

internal class WatchCommandHistory(context: Context) {
    data class Record(val id: String, val text: String, val message: String, val state: CommandState)

    private val store = context.getSharedPreferences("watch-command-history", Context.MODE_PRIVATE)

    fun records(): List<Record> {
        val json = JSONArray(store.getString("entries", "[]"))
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            Record(item.getString("id"), item.getString("text"), item.getString("message"),
                CommandState.valueOf(item.getString("state")))
        }
    }

    fun put(record: Record): Boolean {
        val updated = (listOf(record) + records().filterNot { it.id == record.id }).take(30)
        val json = JSONArray()
        for (item in updated) {
            json.put(JSONObject().put("id", item.id).put("text", item.text)
                .put("message", item.message).put("state", item.state.name))
        }
        return store.edit().putString("entries", json.toString()).commit()
    }
}
