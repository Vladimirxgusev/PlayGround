package app.pomoshnik.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiFallbackTest {
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0)

    @Test fun mapsTask() = assertEquals(
        Command.CreateTask("Купить хлеб", LocalDate.of(2026, 11, 25), LocalTime.of(18, 0)),
        AiFallback.toCommand(AiFallback.Answer("task", "Купить хлеб", "2026-11-25", "18:00"), "x", now),
    )

    @Test fun mapsAlarmWithDays() = assertEquals(
        Command.SetAlarm(7, 5, null, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
        AiFallback.toCommand(AiFallback.Answer("alarm", time = "7:05", days = listOf("SAT", "sun")), "x", now),
    )

    @Test fun eventWithOnlyPastTimeGoesToTomorrow() = assertEquals(
        Command.CreateEvent("Звонок", LocalDate.of(2026, 11, 26), LocalTime.of(9, 0), 30),
        AiFallback.toCommand(AiFallback.Answer("event", "Звонок", null, "09:00", 30), "x", now),
    )

    @Test fun badTimerIsUnknown() {
        assertIs<Command.Unknown>(AiFallback.toCommand(AiFallback.Answer("timer"), "x", now))
    }

    @Test fun otherTypeIsUnknown() {
        assertIs<Command.Unknown>(AiFallback.toCommand(AiFallback.Answer("unknown"), "x", now))
    }

    @Test fun promptHasDate() = assertTrue(AiFallback.systemPrompt(now).contains("2026-11-25, среда, 12:00"))

    @Test fun describes() {
        val today = now.toLocalDate()
        assertEquals(
            "Событие «Встреча» — завтра, 15:00–16:00",
            Describe.command(Command.CreateEvent("Встреча", today.plusDays(1), LocalTime.of(15, 0)), today),
        )
        assertEquals(
            "Будильник на 07:00, по будням",
            Describe.command(Command.SetAlarm(7, 0, days = (1..5).map { DayOfWeek.of(it) }.toSet()), today),
        )
        assertEquals("Таймер на 1 ч 30 мин", Describe.command(Command.SetTimer(5400), today))
        assertEquals(
            "Задача «Х» — пн, 7 декабря",
            Describe.command(Command.CreateTask("Х", LocalDate.of(2026, 12, 7)), today),
        )
    }
}
