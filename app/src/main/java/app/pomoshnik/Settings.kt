package app.pomoshnik

import android.content.Context

/** User settings, stored on the device only. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var appearance: AssistantAppearance
        get() = AssistantAppearance(
            AssistantThemeMode.entries.firstOrNull { it.name == prefs.getString("theme_mode", null) }
                ?: AssistantThemeMode.SYSTEM,
            AssistantAccent.entries.firstOrNull { it.name == prefs.getString("theme_accent", null) }
                ?: AssistantAccent.ICE,
            prefs.getBoolean("large_answers", false),
        )
        set(value) = prefs.edit().putString("theme_mode", value.mode.name)
            .putString("theme_accent", value.accent.name).putBoolean("large_answers", value.largeAnswers).apply()

    var yandexApiKey: String
        get() = prefs.getString("yandex_api_key", "").orEmpty()
        set(v) = prefs.edit().putString("yandex_api_key", v.trim()).apply()

    var yandexFolderId: String
        get() = prefs.getString("yandex_folder_id", "").orEmpty()
        set(v) = prefs.edit().putString("yandex_folder_id", v.trim()).apply()

    /** Model path after the folder id, e.g. "yandexgpt-lite/latest". */
    var yandexModel: String
        get() = prefs.getString("yandex_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("yandex_model", v.trim()).apply()

    /** Calendar to write events to; -1 picks the main Google calendar automatically. */
    var calendarId: Long
        get() = prefs.getLong("calendar_id", -1)
        set(v) = prefs.edit().putLong("calendar_id", v).apply()

    var listenOnOpen: Boolean
        get() = prefs.getBoolean("listen_on_open", true)
        set(v) = prefs.edit().putBoolean("listen_on_open", v).apply()

    /** Used for weather when the phone's location is unavailable. */
    var homeCity: String
        get() = prefs.getString("home_city", "").orEmpty()
        set(v) = prefs.edit().putString("home_city", v.trim()).apply()

    var speakAnswers: Boolean
        get() = prefs.getBoolean("speak_answers", true)
        set(v) = prefs.edit().putBoolean("speak_answers", v).apply()

    val aiConfigured: Boolean get() = yandexApiKey.isNotBlank() && yandexFolderId.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "yandexgpt-lite/latest"
    }
}
