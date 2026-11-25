package app.pomoshnik.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Short Russian descriptions of commands for the UI. */
object Describe {
    private val monthsGen = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val weekdayShort = mapOf(
        DayOfWeek.MONDAY to "пн", DayOfWeek.TUESDAY to "вт", DayOfWeek.WEDNESDAY to "ср",
        DayOfWeek.THURSDAY to "чт", DayOfWeek.FRIDAY to "пт", DayOfWeek.SATURDAY to "сб",
        DayOfWeek.SUNDAY to "вс",
    )

    fun command(c: Command, today: LocalDate = LocalDate.now()): String = when (c) {
        is Command.SetAlarm -> buildString {
            append("Будильник на ").append(time(LocalTime.of(c.hour, c.minute)))
            if (c.days.isNotEmpty()) append(", ").append(days(c.days))
            c.label?.let { append(" — «").append(it).append("»") }
        }
        is Command.SetTimer -> buildString {
            append("Таймер на ").append(duration(c.seconds))
            c.label?.let { append(" — «").append(it).append("»") }
        }
        is Command.CreateEvent -> buildString {
            append("Событие «").append(c.title).append("» — ").append(date(c.date, today))
            if (c.time == null) {
                append(", весь день")
            } else {
                append(", ").append(time(c.time)).append("–").append(time(c.time.plusMinutes(c.durationMinutes.toLong())))
            }
        }
        is Command.CreateTask -> buildString {
            append("Задача «").append(c.title).append("»")
            if (c.date != null) append(" — ").append(date(c.date, today))
            if (c.time != null) append(", ").append(time(c.time))
        }
        is Command.Unknown -> c.reason
    }

    fun time(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    fun date(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        today.plusDays(2) -> "послезавтра"
        else -> buildString {
            append(weekdayShort[d.dayOfWeek]).append(", ").append(d.dayOfMonth).append(' ')
            append(monthsGen[d.monthValue - 1])
            if (d.year != today.year) append(' ').append(d.year)
        }
    }

    fun days(days: Set<DayOfWeek>): String = when (days) {
        DayOfWeek.entries.toSet() -> "каждый день"
        DayOfWeek.entries.filter { it.value <= 5 }.toSet() -> "по будням"
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "по выходным"
        else -> days.sorted().joinToString(", ") { weekdayShort.getValue(it) }
    }

    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it ч" },
            m.takeIf { it > 0 }?.let { "$it мин" },
            s.takeIf { it > 0 }?.let { "$it с" },
        ).joinToString(" ").ifEmpty { "0 с" }
    }
}
