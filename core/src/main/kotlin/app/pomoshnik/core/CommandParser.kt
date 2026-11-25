package app.pomoshnik.core

import app.pomoshnik.core.RuText.L
import app.pomoshnik.core.RuText.R
import app.pomoshnik.core.RuText.rx
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Rule-based parser for Russian voice commands. It handles the common phrasings
 * offline; whatever it returns as [Command.Unknown] can be sent to the AI fallback.
 */
object CommandParser {

    private enum class Kind { TIMER, ALARM, EVENT, TASK }

    private val timerRx = rx("$L(?:таймер[\\p{L}\\p{N}_]*|засеки)$R")
    private val alarmRx = rx("$L(?:будильник[\\p{L}\\p{N}_]*|разбуди[\\p{L}\\p{N}_]*|подними\\s+меня|подъем)$R")
    private val eventStrongRx = rx("$L(?:в\\s+календар[\\p{L}\\p{N}_]*|календар[\\p{L}\\p{N}_]*|событи[\\p{L}\\p{N}_]*|мероприяти[\\p{L}\\p{N}_]*|запланируй[\\p{L}\\p{N}_]*)$R")
    private val taskRx = rx("$L(?:задач[\\p{L}\\p{N}_]*|напомни[\\p{L}\\p{N}_]*|напоминани[\\p{L}\\p{N}_]*|список\\s+дел|в\\s+дела)$R")
    private val eventWeakRx = rx(
        "$L(?:встреч[\\p{L}\\p{N}_]*|созвон[\\p{L}\\p{N}_]*|звонок|прием[\\p{L}\\p{N}_]*|собрани[\\p{L}\\p{N}_]*|день\\s+рождения|свидани[\\p{L}\\p{N}_]*|" +
            "конференци[\\p{L}\\p{N}_]*|вебинар[\\p{L}\\p{N}_]*|совещани[\\p{L}\\p{N}_]*|планерк[\\p{L}\\p{N}_]*|занятие|тренировк[\\p{L}\\p{N}_]*|концерт[\\p{L}\\p{N}_]*|визит[\\p{L}\\p{N}_]*)$R"
    )

    /** Command words removed from titles. Longer phrases come first. */
    private val fillerRx = rx(
        "$L(?:" + listOf(
            "добав(?:ь|ьте)\\s+в\\s+(?:календарь|список\\s+дел|список\\s+задач|задачи|дела)",
            "(?:в|во)\\s+(?:календарь|календаре|список\\s+дел|список\\s+задач|задачи|дела)",
            "(?:поставь|добавь|создай|заведи|сделай)(?:те)?\\s+(?:новую\\s+)?(?:задачу|событие|напоминание)",
            "(?:новая|новую)\\s+(?:задача|задачу)",
            "напомни(?:те)?(?:\\s+(?:мне|нам))?", "напоминание",
            "задачу", "задача", "событие",
            "запланируй(?:те)?", "запиши(?:те)?", "добавь(?:те)?", "создай(?:те)?", "поставь(?:те)?",
            "пожалуйста",
        ).joinToString("|") + ")$R"
    )
    private val alarmFillerRx = rx(
        "$L(?:поставь(?:те)?|заведи(?:те)?|установи(?:те)?|включи(?:те)?|создай(?:те)?|" +
            "будильник[\\p{L}\\p{N}_]*|разбуди(?:те)?|подними|подъем|меня|мне|пожалуйста)$R"
    )
    private val timerFillerRx = rx(
        "$L(?:поставь(?:те)?|заведи(?:те)?|установи(?:те)?|включи(?:те)?|засеки|таймер[\\p{L}\\p{N}_]*|пожалуйста)$R"
    )

    private val leadingJunk = rx("^(?:о\\s+том\\s+что|что|чтобы|чтоб|про|о|об|насчет|на|в|во|к|и|до)\\s+")
    private val trailingJunk = rx("\\s+(?:на|в|во|к|и|с|до|по|около|часов|часа|час|утра|дня|вечера|ночи)$")

    /** Accusative → nominative for the words that most often start an event title. */
    private val titleFixes = mapOf(
        "встречу" to "встреча", "тренировку" to "тренировка", "планерку" to "планерка",
        "презентацию" to "презентация", "консультацию" to "консультация", "поездку" to "поездка",
    )

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command {
        val text = RuText.normalize(input)
        if (text.isEmpty()) return Command.Unknown(input, "Пустая команда")

        val kind = when {
            timerRx.containsMatchIn(text) -> Kind.TIMER
            alarmRx.containsMatchIn(text) -> Kind.ALARM
            eventStrongRx.containsMatchIn(text) -> Kind.EVENT
            taskRx.containsMatchIn(text) -> Kind.TASK
            eventWeakRx.containsMatchIn(text) -> Kind.EVENT
            else -> return InfoParser.parse(text, input, now) ?: Command.Unknown(input)
        }

        return when (kind) {
            Kind.TIMER -> parseTimer(text, input)
            Kind.ALARM -> parseAlarm(text, input, now)
            Kind.EVENT -> parseEvent(text, input, now)
            Kind.TASK -> parseTask(text, input, now)
        }
    }

    private fun parseTimer(text: String, input: String): Command {
        val durRx = rx("$L(?:на\\s+)?(${Durations.pattern(clockOnly = true)})")
        val m = durRx.find(text)
        val seconds: Long
        var rest: String
        if (m != null) {
            seconds = Durations.parse(m.groupValues[1])?.seconds ?: 0
            rest = text.replaceRange(m.range, " ")
        } else {
            // "таймер на 10" → minutes
            val bare = rx("$L(?:на\\s+)?(${RuNumbers.PATTERN})$R").find(text)
                ?: return Command.Unknown(input, "Не понял длительность таймера")
            seconds = (RuNumbers.parse(bare.groupValues[1]) ?: 0) * 60L
            rest = text.replaceRange(bare.range, " ")
        }
        if (seconds <= 0 || seconds > 24 * 3600) return Command.Unknown(input, "Не понял длительность таймера")
        rest = cleanTitle(timerFillerRx.replace(rest, " "))
        return Command.SetTimer(seconds.toInt(), RuText.capitalize(rest).ifEmpty { null })
    }

    private fun parseAlarm(text: String, input: String, now: LocalDateTime): Command {
        val ex = DateTimeExtractor(now).extract(text, preferPm = false, wantDuration = false)
        val time = ex.relative?.toLocalTime() ?: ex.time
            ?: return Command.Unknown(input, "Не услышал время будильника")
        val label = RuText.capitalize(cleanTitle(alarmFillerRx.replace(ex.rest, " "))).ifEmpty { null }
        return Command.SetAlarm(time.hour, time.minute, label, ex.days)
    }

    private fun parseEvent(text: String, input: String, now: LocalDateTime): Command {
        val ex = DateTimeExtractor(now).extract(text, preferPm = true, wantDuration = true)
        val relative = ex.relative
        var date = relative?.toLocalDate() ?: ex.date
        val time = relative?.toLocalTime()?.withSecond(0)?.withNano(0) ?: ex.time ?: ex.partOfDay?.defaultTime
        if (date == null && time == null) return Command.Unknown(input, "Не понял, на какой день событие")
        if (date == null) date = nextDateFor(time!!, now)
        val title = makeTitle(ex.rest) ?: return Command.Unknown(input, "Не понял название события")
        return Command.CreateEvent(title, date, time, ex.durationMinutes?.takeIf { it > 0 } ?: 60)
    }

    private fun parseTask(text: String, input: String, now: LocalDateTime): Command {
        val ex = DateTimeExtractor(now).extract(text, preferPm = true, wantDuration = false)
        val relative = ex.relative
        var date = relative?.toLocalDate() ?: ex.date
        val time = relative?.toLocalTime()?.withSecond(0)?.withNano(0) ?: ex.time ?: ex.partOfDay?.defaultTime
        if (date == null && time != null) date = nextDateFor(time, now)
        val title = makeTitle(ex.rest) ?: return Command.Unknown(input, "Не понял текст задачи")
        return Command.CreateTask(title, date, time)
    }

    /** Today if the time is still ahead, otherwise tomorrow. */
    private fun nextDateFor(time: LocalTime, now: LocalDateTime): LocalDate =
        if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)

    private fun makeTitle(rest: String): String? {
        var t = cleanTitle(fillerRx.replace(rest, " "))
        if (t.isEmpty()) return null
        val first = t.substringBefore(' ')
        titleFixes[first.lowercase()]?.let { t = it + t.substring(first.length) }
        return RuText.capitalize(t)
    }

    private fun cleanTitle(s: String): String {
        var t = RuText.collapse(s)
        while (true) {
            val next = RuText.collapse(trailingJunk.replace(leadingJunk.replace(t, ""), ""))
            if (next == t) return t
            t = next
        }
    }
}
