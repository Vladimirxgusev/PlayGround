package app.pomoshnik.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** What the assistant understood from a phrase. */
sealed interface Command {

    /** Alarm in the stock Clock app. [days] empty = one-off alarm. */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String? = null,
        val days: Set<DayOfWeek> = emptySet(),
    ) : Command

    /** Countdown timer in the stock Clock app. */
    data class SetTimer(val seconds: Int, val label: String? = null) : Command

    /** Calendar event. [time] null = all-day event. */
    data class CreateEvent(
        val title: String,
        val date: LocalDate,
        val time: LocalTime? = null,
        val durationMinutes: Int = 60,
    ) : Command

    /** Google Tasks task. Google Tasks keeps only the due date; [time] goes into the notes. */
    data class CreateTask(
        val title: String,
        val date: LocalDate? = null,
        val time: LocalTime? = null,
    ) : Command

    /** Weather for [city] (null = current location) on [date] (null = now/today). */
    data class Weather(val city: String? = null, val date: LocalDate? = null) : Command

    /** Central Bank of Russia rates for ISO [codes]; [amount] converts that many units to roubles. */
    data class Currency(val codes: List<String>, val amount: Double? = null) : Command

    /** Current time or date. */
    data class Now(val kind: Kind) : Command {
        enum class Kind { TIME, DATE }
    }

    /** A free-form question for the AI. */
    data class Question(val text: String) : Command

    /** A ready answer (from the AI) to show and speak. */
    data class Answer(val text: String) : Command

    /** Not understood. [reason] is shown to the user when no AI fallback is available. */
    data class Unknown(val text: String, val reason: String = "Не понял команду") : Command
}
