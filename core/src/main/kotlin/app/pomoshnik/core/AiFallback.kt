package app.pomoshnik.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The AI fallback: the prompt that asks a language model to turn a phrase into JSON,
 * and the mapping from that JSON's fields to a [Command]. JSON decoding itself lives
 * in the app (Android ships org.json), so this stays dependency-free and testable.
 */
object AiFallback {

    /** Fields of the JSON the model is asked to return. All optional. */
    data class Answer(
        val type: String? = null,
        val title: String? = null,
        val date: String? = null,
        val time: String? = null,
        val durationMinutes: Int? = null,
        val days: List<String>? = null,
        val timerSeconds: Int? = null,
    )

    private val weekdayNames = listOf(
        "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
    )

    fun systemPrompt(now: LocalDateTime): String {
        val date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = "%02d:%02d".format(now.hour, now.minute)
        val weekday = weekdayNames[now.dayOfWeek.value - 1]
        return """
            Ты разбираешь голосовые команды для ассистента на Android-телефоне.
            Сейчас $date, $weekday, $time.
            Верни ТОЛЬКО один JSON-объект без пояснений и без markdown.

            Поля:
            - "type": одно из "alarm" (будильник), "timer" (таймер), "event" (событие в календаре),
              "task" (задача или напоминание), "unknown" (команда не про это);
            - "title": короткое название задачи или события в именительном падеже, с заглавной буквы,
              без слов-команд ("напомни", "добавь", "создай"); для будильника и таймера — подпись или null;
            - "date": дата в формате YYYY-MM-DD или null;
            - "time": время в формате HH:MM (24 часа) или null;
            - "duration_minutes": длительность события в минутах или null;
            - "days": для повторяющегося будильника массив из "MON","TUE","WED","THU","FRI","SAT","SUN", иначе null;
            - "timer_seconds": длительность таймера в секундах или null.

            Правила:
            - "напомни ..." — это task; встречи, приёмы, дни рождения, мероприятия — event;
            - относительные даты ("завтра", "в пятницу", "через неделю") считай от текущей даты;
            - если время сказано без "утра/вечера" и речь о встрече или делах, выбирай разумное
              дневное время (например, "в 3" — это 15:00);
            - событие без времени — это событие на весь день (time = null).
        """.trimIndent()
    }

    fun toCommand(a: Answer, original: String, now: LocalDateTime): Command {
        val time = a.time?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }
        val date = a.date?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        val title = a.title?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
        return when (a.type?.trim()?.lowercase()) {
            "alarm" -> {
                val t = time ?: return Command.Unknown(original, "ИИ не смог определить время будильника")
                val days = a.days.orEmpty().mapNotNull { dayOf(it) }.toSet()
                Command.SetAlarm(t.hour, t.minute, title, days)
            }
            "timer" -> {
                val s = a.timerSeconds?.takeIf { it in 1..86_400 }
                    ?: return Command.Unknown(original, "ИИ не смог определить длительность таймера")
                Command.SetTimer(s, title)
            }
            "event" -> {
                val t = title ?: return Command.Unknown(original, "ИИ не смог определить название события")
                val d = date ?: time?.let {
                    if (it.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
                } ?: return Command.Unknown(original, "ИИ не смог определить дату события")
                Command.CreateEvent(t, d, time, a.durationMinutes?.takeIf { it in 1..(24 * 60) } ?: 60)
            }
            "task" -> {
                val t = title ?: return Command.Unknown(original, "ИИ не смог определить текст задачи")
                val d = date ?: time?.let {
                    if (it.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
                }
                Command.CreateTask(t, d, time)
            }
            else -> Command.Unknown(original, "Это не похоже на команду для будильника, календаря или задач")
        }
    }

    private fun dayOf(s: String): DayOfWeek? = when (s.trim().uppercase().take(3)) {
        "MON" -> DayOfWeek.MONDAY
        "TUE" -> DayOfWeek.TUESDAY
        "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY
        "FRI" -> DayOfWeek.FRIDAY
        "SAT" -> DayOfWeek.SATURDAY
        "SUN" -> DayOfWeek.SUNDAY
        else -> null
    }
}
