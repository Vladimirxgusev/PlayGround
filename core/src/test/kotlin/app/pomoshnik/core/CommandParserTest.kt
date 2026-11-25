package app.pomoshnik.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandParserTest {
    // Wednesday, 25 November 2026, 12:00
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0)
    private val today = now.toLocalDate()

    private fun parse(s: String) = CommandParser.parse(s, now)

    // --- Alarms ---

    @Test fun alarmWithColonTime() =
        assertEquals(Command.SetAlarm(7, 30), parse("Поставь будильник на 7:30"))

    @Test fun alarmTomorrowMorning() =
        assertEquals(Command.SetAlarm(6, 0), parse("разбуди меня завтра в 6 утра"))

    @Test fun alarmEvening() =
        assertEquals(Command.SetAlarm(20, 0), parse("будильник на 8 вечера"))

    @Test fun alarmPlainHour() =
        assertEquals(Command.SetAlarm(7, 0), parse("заведи будильник на 7"))

    @Test fun alarmWeekdays() = assertEquals(
        Command.SetAlarm(7, 0, days = (1..5).map { DayOfWeek.of(it) }.toSet()),
        parse("будильник по будням на 7:00"),
    )

    @Test fun alarmRelative() =
        assertEquals(Command.SetAlarm(20, 0), parse("поставь будильник через 8 часов"))

    @Test fun alarmWithLabel() =
        assertEquals(Command.SetAlarm(6, 45, "Тренировка"), parse("будильник на 6:45 тренировка"))

    @Test fun alarmHoursWord() =
        assertEquals(Command.SetAlarm(7, 15), parse("будильник на 7 часов 15 минут"))

    @Test fun alarmWithoutTime() { assertIs<Command.Unknown>(parse("поставь будильник")) }

    // --- Timers ---

    @Test fun timerMinutes() = assertEquals(Command.SetTimer(600), parse("таймер на 10 минут"))

    @Test fun timerHalfHour() = assertEquals(Command.SetTimer(1800), parse("поставь таймер на полчаса"))

    @Test fun timerHoursAndMinutes() =
        assertEquals(Command.SetTimer(5400), parse("таймер на 1 час 30 минут"))

    @Test fun timerWords() = assertEquals(Command.SetTimer(300), parse("засеки пять минут"))

    @Test fun timerBareNumber() = assertEquals(Command.SetTimer(900), parse("таймер на 15"))

    // --- Tasks ---

    @Test fun taskReminderTomorrow() = assertEquals(
        Command.CreateTask("Позвонить маме", today.plusDays(1), LocalTime.of(10, 0)),
        parse("Напомни завтра в 10 позвонить маме"),
    )

    @Test fun taskNoDate() =
        assertEquals(Command.CreateTask("Купить молоко"), parse("добавь задачу купить молоко"))

    @Test fun taskRelativeMinutes() = assertEquals(
        Command.CreateTask("Выключить духовку", today, LocalTime.of(12, 15)),
        parse("напомни через 15 минут выключить духовку"),
    )

    @Test fun taskAboutThat() = assertEquals(
        Command.CreateTask("Надо оплатить интернет", today.plusDays(2)),
        parse("напомни послезавтра что надо оплатить интернет"),
    )

    @Test fun taskKeepsCounts() = assertEquals(
        Command.CreateTask("Забронировать столик на 5 человек", today, LocalTime.of(18, 0)),
        parse("напомни в 18:00 забронировать столик на 5 человек"),
    )

    @Test fun taskEveningDefault() = assertEquals(
        Command.CreateTask("Полить цветы", today, LocalTime.of(19, 0)),
        parse("напомни вечером полить цветы"),
    )

    @Test fun taskPastHourGoesToTomorrow() = assertEquals(
        Command.CreateTask("Выпить таблетки", today.plusDays(1), LocalTime.of(9, 0)),
        parse("напомни в 9 утра выпить таблетки"),
    )

    @Test fun taskInWeek() = assertEquals(
        Command.CreateTask("Продлить страховку", today.plusDays(7)),
        parse("поставь задачу продлить страховку через неделю"),
    )

    // --- Events ---

    @Test fun eventMeetingThursday() = assertEquals(
        Command.CreateEvent("Встреча с врачом", LocalDate.of(2026, 11, 26), LocalTime.of(14, 0)),
        parse("Создай встречу с врачом в четверг в 14:00"),
    )

    @Test fun eventAllDayBirthday() = assertEquals(
        Command.CreateEvent("День рождения мамы", LocalDate.of(2027, 5, 5)),
        parse("добавь в календарь день рождения мамы 5 мая"),
    )

    @Test fun eventKeepsNameCaseAndPmHour() = assertEquals(
        Command.CreateEvent("Встреча с Петром", today.plusDays(2), LocalTime.of(15, 0)),
        parse("встреча с Петром послезавтра в 3"),
    )

    @Test fun eventNumericDate() = assertEquals(
        Command.CreateEvent("Концерт", LocalDate.of(2026, 12, 12), LocalTime.of(19, 0)),
        parse("событие концерт 12.12 в 19:00"),
    )

    @Test fun eventFridayEvening() = assertEquals(
        Command.CreateEvent("Созвон с командой", LocalDate.of(2026, 11, 27), LocalTime.of(19, 0)),
        parse("запланируй созвон с командой в пятницу вечером"),
    )

    @Test fun eventDuration() = assertEquals(
        Command.CreateEvent("Совещание", today.plusDays(1), LocalTime.of(10, 0), 120),
        parse("совещание завтра в 10 утра на 2 часа"),
    )

    @Test fun eventDurationFirst() = assertEquals(
        Command.CreateEvent("Тренировка", today.plusDays(1), LocalTime.of(18, 30), 90),
        parse("тренировка на полтора часа завтра в 18:30"),
    )

    @Test fun eventWithoutDate() { assertIs<Command.Unknown>(parse("добавь в календарь встречу")) }

    @Test fun eventNextMonday() = assertEquals(
        Command.CreateEvent("Собрание", LocalDate.of(2026, 11, 30), LocalTime.of(9, 0)),
        parse("собрание в понедельник в 9 утра"),
    )

    @Test fun eventSameWeekdayMeansNextWeek() = assertEquals(
        Command.CreateEvent("Визит к стоматологу", LocalDate.of(2026, 12, 2), LocalTime.of(11, 0)),
        parse("визит к стоматологу в среду в 11:00"),
    )

    // --- Unknown ---

    @Test fun freeTextIsUnknown() { assertIs<Command.Unknown>(parse("купить хлеб")) }

    @Test fun emptyIsUnknown() { assertIs<Command.Unknown>(parse("   ")) }
}
