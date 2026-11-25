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
        val city: String? = null,
        val currencies: List<String>? = null,
        val amount: Double? = null,
        val answer: String? = null,
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
              "task" (задача или напоминание), "weather" (вопрос о погоде), "currency" (курс валют),
              "question" (любой другой вопрос или просьба), "unknown" (непонятно);
            - "title": короткое название задачи или события в именительном падеже, с заглавной буквы,
              без слов-команд ("напомни", "добавь", "создай"); для будильника и таймера — подпись или null;
            - "date": дата в формате YYYY-MM-DD или null;
            - "time": время в формате HH:MM (24 часа) или null;
            - "duration_minutes": длительность события в минутах или null;
            - "days": для повторяющегося будильника массив из "MON","TUE","WED","THU","FRI","SAT","SUN", иначе null;
            - "timer_seconds": длительность таймера в секундах или null;
            - "city": для погоды город в именительном падеже или null (null = там, где пользователь);
            - "currencies": для курса массив кодов ISO ("USD", "EUR", "CNY" ...) или null;
            - "amount": для курса сумма в валюте, которую надо перевести в рубли, или null;
            - "answer": для question — короткий ответ по-русски, 1–3 предложения, без markdown.

            Правила:
            - "напомни ..." — это task; встречи, приёмы, дни рождения, мероприятия — event;
            - относительные даты ("завтра", "в пятницу", "через неделю") считай от текущей даты;
            - если время сказано без "утра/вечера" и речь о встрече или делах, выбирай разумное
              дневное время (например, "в 3" — это 15:00);
            - событие без времени — это событие на весь день (time = null);
            - на вопросы о погоде и курсах НЕ отвечай сам: верни weather или currency, данные подставит приложение;
            - если не знаешь точного ответа на question, так и скажи.
        """.trimIndent()
    }

    /** Prompt for answering a free-form question directly. */
    fun answerPrompt(now: LocalDateTime): String {
        val date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekday = weekdayNames[now.dayOfWeek.value - 1]
        return """
            Ты — голосовой помощник «Секретутка» на телефоне. Сегодня $date, $weekday.
            Отвечай по-русски коротко и по делу: 1–3 предложения, без markdown и списков,
            так, чтобы ответ было удобно прослушать. Если не знаешь точного ответа или он зависит
            от свежих данных, честно скажи об этом.
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
            "weather" -> Command.Weather(a.city?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }, date)
            "currency" -> {
                val codes = a.currencies.orEmpty().map { it.trim().uppercase() }.filter { it.length == 3 }
                Command.Currency(codes.ifEmpty { listOf("USD", "EUR", "CNY") }, a.amount?.takeIf { it > 0 })
            }
            "question" -> a.answer?.trim()?.takeIf { it.isNotEmpty() }?.let { Command.Answer(it) }
                ?: Command.Question(original)
            else -> Command.Unknown(original, "Не понял, что нужно сделать")
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
