package app.pomoshnik.core

import app.pomoshnik.core.RuText.L
import app.pomoshnik.core.RuText.R
import app.pomoshnik.core.RuText.rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal enum class PartOfDay(val defaultTime: LocalTime) {
    MORNING(LocalTime.of(9, 0)),
    DAY(LocalTime.of(13, 0)),
    EVENING(LocalTime.of(19, 0)),
    NIGHT(LocalTime.of(23, 0)),
}

internal data class Extracted(
    /** Explicit or derived date, if the phrase named one. */
    val date: LocalDate?,
    /** Explicit clock time, if the phrase named one. */
    val time: LocalTime?,
    /** "через 15 минут" resolved against now. */
    val relative: LocalDateTime?,
    val partOfDay: PartOfDay?,
    /** Repeat days ("по будням"), used for alarms. */
    val days: Set<DayOfWeek>,
    /** Event length ("на 2 часа"), only extracted for events. */
    val durationMinutes: Int?,
    /** The phrase with all date/time words removed. */
    val rest: String,
)

/**
 * Finds date and time expressions in a Russian phrase, removes them, and returns
 * what they mean. Patterns run from most to least specific, and each consumed piece
 * of text is blanked so a later pattern cannot read it twice.
 */
internal class DateTimeExtractor(private val now: LocalDateTime) {

    private val n = RuNumbers.PATTERN
    private val mod = "(утра|дня|вечера|ночи)"

    /** Words after a number that mean it is a count, not an hour ("на 5 человек"). */
    private val countNouns =
        "человек[\\p{L}\\p{N}_]*|штук[\\p{L}\\p{N}_]*|рубл[\\p{L}\\p{N}_]*|процент[\\p{L}\\p{N}_]*|раз|мест[\\p{L}\\p{N}_]*|кг|км|грамм[\\p{L}\\p{N}_]*|литр[\\p{L}\\p{N}_]*|" +
            "доллар[\\p{L}\\p{N}_]*|евро|лет|год[\\p{L}\\p{N}_]*|минут[\\p{L}\\p{N}_]*|секунд[\\p{L}\\p{N}_]*|дн(?:я|ей)|недел[\\p{L}\\p{N}_]*|месяц[\\p{L}\\p{N}_]*|" +
            "этаж[\\p{L}\\p{N}_]*|класс[\\p{L}\\p{N}_]*|кв[\\p{L}\\p{N}_]*|страниц[\\p{L}\\p{N}_]*|част[\\p{L}\\p{N}_]*"

    private val months = listOf(
        "январ" to 1, "феврал" to 2, "март" to 3, "апрел" to 4, "ма" to 5, "июн" to 6,
        "июл" to 7, "август" to 8, "сентябр" to 9, "октябр" to 10, "ноябр" to 11, "декабр" to 12,
    )
    private val monthWords =
        "январ[яь]|феврал[яь]|марта?|апрел[яь]|ма[яй]|июн[яь]|июл[яь]|августа?|сентябр[яь]|октябр[яь]|ноябр[яь]|декабр[яь]"

    private val weekdays = listOf(
        "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "сред" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятниц" to DayOfWeek.FRIDAY, "суббот" to DayOfWeek.SATURDAY,
        "воскресен" to DayOfWeek.SUNDAY,
    )
    private val weekdayWords =
        "понедельник[\\p{L}\\p{N}_]*|вторник[\\p{L}\\p{N}_]*|сред[уаы]|четверг[\\p{L}\\p{N}_]*|пятниц[уаы]|суббот[уаы]|воскресень[еяю]"

    private var text = ""

    fun extract(input: String, preferPm: Boolean, wantDuration: Boolean): Extracted {
        text = input
        var date: LocalDate? = null
        var time: LocalTime? = null
        var relative: LocalDateTime? = null
        val days = mutableSetOf<DayOfWeek>()
        var duration: Int? = null

        // Event length first, so "на 2 часа" is not read as "at 2 o'clock".
        if (wantDuration) {
            take("$L(?:на|длительностью|продолжительностью)\\s+(${Durations.pattern(clockOnly = true)})")
                ?.let { m -> Durations.parse(m.groupValues[1])?.let { duration = (it.seconds / 60).toInt() } }
        }

        // "через 15 минут", "через неделю"
        take("$L(?:через|спустя)\\s+(${Durations.pattern()})")?.let { m ->
            Durations.parse(m.groupValues[1])?.let { span ->
                if (span.hasClockPart) {
                    relative = now.plusMonths(span.months.toLong()).plusDays(span.days.toLong())
                        .plusSeconds(span.seconds)
                } else {
                    date = now.toLocalDate().plusMonths(span.months.toLong()).plusDays(span.days.toLong())
                }
            }
        }

        // Repeats, for alarms.
        if (take("$L(?:по\\s+будн(?:ям|им\\s+дням)|в\\s+будни|по\\s+рабочим\\s+дням)$R") != null) {
            days += DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        }
        if (take("$L(?:по\\s+выходным|в\\s+выходные)$R") != null) {
            days += setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        if (take("$L(?:каждый\\s+день|ежедневно)$R") != null) days += DayOfWeek.entries
        while (true) {
            val m = take(
                "$L(?:по|каждый|каждую|каждое)\\s+(понедельник(?:ам)?|вторник(?:ам)?|сред(?:ам|у)|" +
                    "четверг(?:ам)?|пятниц(?:ам|у)|суббот(?:ам|у)|воскресень(?:ям|е))$R"
            ) ?: break
            weekdayOf(m.groupValues[1])?.let { days += it }
        }

        // "5 мая", "5 мая 2027 года"
        take("$L(?:на\\s+|к\\s+)?(\\d{1,2})(?:-?(?:го|е|ое))?\\s+($monthWords)(?:\\s+(\\d{4})(?:\\s*(?:года|год|г)$R)?)?$R")
            ?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = monthOf(m.groupValues[2])
                val year = m.groupValues[3].toIntOrNull()
                date = safeDate(year, month, day, rollForward = year == null) ?: date
            }

        // "12.12", "12.12.2026", "12/12"
        if (date == null) {
            val rxDate = rx("$L(?:на\\s+|к\\s+)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?$R")
            for (m in rxDate.findAll(text)) {
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                val d = if (month in 1..12) safeDate(year, month, day, rollForward = year == null) else null
                if (d != null) {
                    date = d
                    blank(m.range)
                    break
                }
            }
        }

        // "сегодня", "завтра", "послезавтра"
        take("$L(?:на\\s+)?(сегодня|завтра|послезавтра)$R")?.let { m ->
            val today = now.toLocalDate()
            date = when (m.groupValues[1].lowercase()) {
                "сегодня" -> today
                "завтра" -> today.plusDays(1)
                else -> today.plusDays(2)
            }
        }

        // "в пятницу", "в следующий вторник"
        if (date == null) {
            take("$L(?:(?:в|во|на|к)\\s+)?(?:(следующ[\\p{L}\\p{N}_]+|эт[уио][\\p{L}\\p{N}_]*)\\s+)?($weekdayWords)$R")?.let { m ->
                val target = weekdayOf(m.groupValues[2])!!
                val today = now.toLocalDate()
                var ahead = (target.value - today.dayOfWeek.value + 7) % 7
                if (ahead == 0 && !m.groupValues[1].lowercase().startsWith("эт")) ahead = 7
                date = today.plusDays(ahead.toLong())
            }
        }

        // "утром", "вечером"
        var part: PartOfDay? = null
        take("$L(утром|с\\s+утра|днем|в\\s+обед|вечером|ночью)$R")?.let { m ->
            val w = m.groupValues[1].lowercase()
            part = when {
                w.contains("утр") -> PartOfDay.MORNING
                w == "днем" || w.contains("обед") -> PartOfDay.DAY
                w == "вечером" -> PartOfDay.EVENING
                else -> PartOfDay.NIGHT
            }
        }

        // Clock time.
        if (take("$L(?:в\\s+)?полдень$R") != null) time = LocalTime.NOON
        if (time == null && take("$L(?:в\\s+)?полночь$R") != null) time = LocalTime.MIDNIGHT
        if (time == null) {
            val rxHm = rx("$L(?:(?:в|на|к|до|около|с)\\s+)?(\\d{1,2})[:.](\\d{2})$R(?:\\s+$mod$R)?")
            for (m in rxHm.findAll(text)) {
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h > 23 || min > 59) continue
                time = LocalTime.of(adjustHour(h, m.groupValues[3], part, preferPm), min)
                blank(m.range)
                break
            }
        }
        if (time == null) {
            val rxHour = rx(
                "$L(?:в|на|к|до|около)\\s+($n|час)$R(?:\\s*(?:час(?:а|ов)?|ч)$R)?" +
                    "(?:\\s+($n)\\s*минут[\\p{L}\\p{N}_]*)?(?:\\s+$mod$R)?(?!\\s*(?:$countNouns)$R)"
            )
            time = firstTime(rxHour, part, preferPm)
        }
        if (time == null) {
            // "7 утра" without a preposition.
            time = firstTime(rx("$L($n)()\\s+$mod$R"), part, preferPm)
        }

        return Extracted(date, time, relative, part, days, duration, RuText.collapse(text))
    }

    private fun firstTime(rxTime: Regex, part: PartOfDay?, preferPm: Boolean): LocalTime? {
        for (m in rxTime.findAll(text)) {
            val h = RuNumbers.parse(m.groupValues[1]) ?: continue
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { RuNumbers.parse(it) } ?: 0
            if (h > 23 || min > 59) continue
            blank(m.range)
            return LocalTime.of(adjustHour(h, m.groupValues[3], part, preferPm), min)
        }
        return null
    }

    /** Turns a spoken 12-hour time into 24-hour using "утра/вечера" or the part of day. */
    private fun adjustHour(h: Int, modifier: String, part: PartOfDay?, preferPm: Boolean): Int =
        when (modifier.lowercase()) {
            "утра" -> if (h == 12) 0 else h
            "дня" -> if (h in 1..6) h + 12 else h
            "вечера" -> if (h in 1..11) h + 12 else h
            "ночи" -> if (h == 12) 0 else if (h in 9..11) h + 12 else h
            else -> when (part) {
                PartOfDay.EVENING -> if (h in 1..11) h + 12 else h
                PartOfDay.DAY -> if (h in 1..6) h + 12 else h
                PartOfDay.NIGHT -> if (h == 12) 0 else if (h in 8..11) h + 12 else h
                PartOfDay.MORNING -> if (h == 12) 0 else h
                null -> if (preferPm && h in 1..6) h + 12 else h
            }
        }

    private fun take(pattern: String): MatchResult? {
        val m = rx(pattern).find(text) ?: return null
        blank(m.range)
        return m
    }

    private fun blank(range: IntRange) {
        text = text.replaceRange(range, " ")
    }

    private fun monthOf(word: String): Int {
        val w = word.lowercase()
        // Check longer stems first so "ма" (май) does not swallow "март".
        return months.sortedByDescending { it.first.length }.first { w.startsWith(it.first) }.second
    }

    private fun weekdayOf(word: String): DayOfWeek? {
        val w = word.lowercase()
        return weekdays.firstOrNull { w.startsWith(it.first) }?.second
    }

    private fun safeDate(year: Int?, month: Int, day: Int, rollForward: Boolean): LocalDate? {
        val y = year ?: now.year
        val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return null
        return if (rollForward && d.isBefore(now.toLocalDate())) {
            runCatching { LocalDate.of(y + 1, month, day) }.getOrNull()
        } else {
            d
        }
    }

    private operator fun DayOfWeek.rangeTo(other: DayOfWeek): Set<DayOfWeek> =
        DayOfWeek.entries.filter { it.value in value..other.value }.toSet()
}
