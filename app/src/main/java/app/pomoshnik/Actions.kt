package app.pomoshnik

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import app.pomoshnik.core.Command
import app.pomoshnik.core.CommandState
import app.pomoshnik.core.Describe
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset

/** Outcome of running a command, shown in the history list. */
data class Outcome(
    val ok: Boolean,
    val message: String,
    val undo: UndoAction? = null,
    val state: CommandState = if (ok) CommandState.DONE else CommandState.FAILED,
)

data class CalendarInfo(val id: Long, val name: String, val account: String, val isGoogle: Boolean, val isPrimary: Boolean)

/** Runs commands against the stock Clock app and the phone's calendar. */
class Actions(private val activity: Activity, private val settings: Settings) {

    fun setAlarm(c: Command.SetAlarm): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, c.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (c.days.isNotEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(c.days.sorted().map { it.toCalendarDay() }))
        }
        val result = launch(intent, "Передано в «Часы» · " + Describe.command(c), "Не нашёл приложение «Часы» для будильника")
        return if (result.ok) result.copy(state = CommandState.SENT) else result
    }

    fun setTimer(c: Command.SetTimer): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, c.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val result = launch(intent, "Передано в «Часы» · " + Describe.command(c), "Не нашёл приложение «Часы» для таймера")
        return if (result.ok) result.copy(state = CommandState.SENT) else result
    }

    /** Needs READ/WRITE_CALENDAR; the caller requests them first. */
    fun createEvent(c: Command.CreateEvent): Outcome {
        val calendar = pickCalendar() ?: return Outcome(false, "На телефоне нет календаря, в который можно записывать")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar.id)
            put(Events.TITLE, c.title)
            if (c.time == null) {
                val start = c.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(Events.DTSTART, start)
                put(Events.DTEND, start + 24 * 3600 * 1000L)
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                val zone = ZoneId.systemDefault()
                val start = c.date.atTime(c.time).atZone(zone)
                put(Events.DTSTART, start.toInstant().toEpochMilli())
                put(Events.DTEND, start.plusMinutes(c.durationMinutes.toLong()).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
            }
        }
        val resolver = activity.contentResolver
        val uri = resolver.insert(Events.CONTENT_URI, values)
            ?: return Outcome(false, "Календарь не принял событие")
        if (c.time != null) {
            resolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, ContentUris.parseId(uri))
                    put(Reminders.MINUTES, 15)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
        return Outcome(
            true, Describe.command(c) + "\nКалендарь: ${calendar.name}",
            UndoAction(UndoAction.Kind.EVENT, ContentUris.parseId(uri).toString()),
        )
    }

    fun deleteEvent(id: String) {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id.toLong())
        check(activity.contentResolver.delete(uri, null, null) > 0) { "Событие не найдено в календаре" }
    }

    /** Without calendar permission: open the calendar's own "new event" screen, pre-filled. */
    fun createEventViaCalendarApp(c: Command.CreateEvent): Outcome {
        val intent = Intent(Intent.ACTION_INSERT, Events.CONTENT_URI).putExtra(Events.TITLE, c.title)
        if (c.time == null) {
            val start = c.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 24 * 3600 * 1000L)
        } else {
            val start = c.date.atTime(c.time).atZone(ZoneId.systemDefault())
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toInstant().toEpochMilli())
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    start.plusMinutes(c.durationMinutes.toLong()).toInstant().toEpochMilli(),
                )
        }
        val result = launch(intent, "Открыл календарь, проверьте и сохраните событие", "Не нашёл приложение календаря")
        return if (result.ok) result.copy(state = CommandState.SENT) else result
    }

    /** Fallback when Google Tasks cannot be reached: copy the text and open the Tasks app. */
    fun createTaskViaClipboard(c: Command.CreateTask, problem: String): Outcome {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Задача", c.title))
        val launchTasks = activity.packageManager.getLaunchIntentForPackage(TASKS_PACKAGE)
        if (launchTasks != null) activity.startActivity(launchTasks)
        val tail = if (launchTasks != null) "Текст скопирован, открыл Google Задачи — вставьте его." else "Текст скопирован."
        return Outcome(false, "Не удалось записать в Google Задачи: $problem.\n$tail")
    }

    fun listCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY,
        )
        val selection = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val result = mutableListOf<CalendarInfo>()
        try {
            activity.contentResolver.query(Calendars.CONTENT_URI, projection, selection, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    result += CalendarInfo(
                        id = cur.getLong(0),
                        name = cur.getString(1).orEmpty(),
                        account = cur.getString(2).orEmpty(),
                        isGoogle = cur.getString(3) == "com.google",
                        isPrimary = !cur.isNull(4) && cur.getInt(4) == 1,
                    )
                }
            }
        } catch (_: SecurityException) {
            // No calendar permission yet.
        }
        return result
    }

    private fun pickCalendar(): CalendarInfo? {
        val all = listCalendars()
        return all.firstOrNull { it.id == settings.calendarId }
            ?: all.firstOrNull { it.isGoogle && it.isPrimary }
            ?: all.firstOrNull { it.isGoogle }
            ?: all.firstOrNull()
    }

    private fun launch(intent: Intent, success: String, notFound: String): Outcome = try {
        activity.startActivity(intent)
        Outcome(true, success)
    } catch (_: ActivityNotFoundException) {
        Outcome(false, notFound)
    } catch (e: SecurityException) {
        Outcome(false, "Нет разрешения: ${e.message}")
    }

    private fun DayOfWeek.toCalendarDay(): Int = value % 7 + 1 // java.util.Calendar: SUNDAY = 1

    companion object {
        const val TASKS_PACKAGE = "com.google.android.apps.tasks"
    }
}
