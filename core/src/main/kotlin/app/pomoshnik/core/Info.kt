package app.pomoshnik.core

import app.pomoshnik.core.RuText.L
import app.pomoshnik.core.RuText.R
import app.pomoshnik.core.RuText.rx
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recognises informational questions: weather, exchange rates, time and date, free questions. */
internal object InfoParser {

    private val weatherRx = rx(
        "$L(?:погод[\\p{L}\\p{N}_]*|температур[\\p{L}\\p{N}_]*|прогноз[\\p{L}\\p{N}_]*|дожд[\\p{L}\\p{N}_]*|снег[\\p{L}\\p{N}_]*|зонт[\\p{L}\\p{N}_]*|холодно|тепло|жарко|градус[\\p{L}\\p{N}_]*)$R"
    )
    private val timeRx = rx("$L(?:который\\s+час|сколько\\s+времени|какое\\s+(?:сейчас\\s+)?время)$R")
    private val dateRx = rx(
        "$L(?:какое\\s+(?:сегодня\\s+)?число|какая\\s+(?:сегодня\\s+)?дата|какой\\s+(?:сегодня\\s+)?день(?:\\s+недели)?|" +
            "какое\\s+сегодня|что\\s+за\\s+день)$R"
    )
    private val ratesRx = rx("$L(?:курс[\\p{L}\\p{N}_]*\\s+валют|валют[\\p{L}\\p{N}_]*)$R")
    private val currencyAskRx = rx("$L(?:курс[\\p{L}\\p{N}_]*|сколько|почем|стоит|в\\s+рубл[\\p{L}\\p{N}_]*|рубл[\\p{L}\\p{N}_]*)$R")
    private val questionStartRx = rx(
        "^(?:а\\s+)?(?:что|кто|как|какой|какая|какое|какие|каков[\\p{L}\\p{N}_]*|почему|зачем|сколько|где|куда|откуда|когда|" +
            "чем|чей|чья|ли|расскажи|объясни|скажи|подскажи|посоветуй|переведи|придумай)$R"
    )

    /** Currency words → ISO codes, checked in order. */
    private val currencies = listOf(
        "доллар[\\p{L}\\p{N}_]*|бакс[\\p{L}\\p{N}_]*" to "USD",
        "евро" to "EUR",
        "юан[\\p{L}\\p{N}_]*" to "CNY",
        "фунт[\\p{L}\\p{N}_]*" to "GBP",
        "иен[\\p{L}\\p{N}_]*|йен[\\p{L}\\p{N}_]*" to "JPY",
        "франк[\\p{L}\\p{N}_]*" to "CHF",
        "лир[\\p{L}\\p{N}_]*" to "TRY",
        "тенге" to "KZT",
        "белорусск[\\p{L}\\p{N}_]+\\s+рубл[\\p{L}\\p{N}_]*" to "BYN",
        "гривн[\\p{L}\\p{N}_]*" to "UAH",
        "дирхам[\\p{L}\\p{N}_]*" to "AED",
        "сум[\\p{L}\\p{N}_]*" to "UZS",
        "драм[\\p{L}\\p{N}_]*" to "AMD",
        "лари" to "GEL",
    ).map { (p, code) -> rx("$L(?:$p)$R") to code }

    private val cityStop = setOf(
        "выходные", "выходных", "будни", "городе", "мире", "течение", "целом", "общем", "моем", "нашем",
        "этом", "районе", "области", "центре", "течении",
    )

    fun parse(text: String, input: String, now: LocalDateTime): Command? {
        if (timeRx.containsMatchIn(text)) return Command.Now(Command.Now.Kind.TIME)
        if (dateRx.containsMatchIn(text)) return Command.Now(Command.Now.Kind.DATE)

        val codes = currencies.filter { it.first.containsMatchIn(text) }.map { it.second }
        if (codes.isNotEmpty() && currencyAskRx.containsMatchIn(text)) {
            val amount = amountRx.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            return Command.Currency(codes.distinct(), amount)
        }
        if (ratesRx.containsMatchIn(text)) return Command.Currency(listOf("USD", "EUR", "CNY"))

        if (weatherRx.containsMatchIn(text)) {
            val ex = DateTimeExtractor(now).extract(text, preferPm = false, wantDuration = false)
            return Command.Weather(city(ex.rest), ex.date ?: ex.relative?.toLocalDate())
        }

        if (questionStartRx.containsMatchIn(text) || input.trim().endsWith("?")) return Command.Question(input.trim())
        return null
    }

    private val amountRx = Regex("(?<![\\p{L}\\d])(\\d+(?:[.,]\\d+)?)\\s*(?=\\p{L})")

    // Case-sensitive on purpose: speech recognition capitalises city names.
    private val cityCapsRx = Regex("(?<![\\p{L}\\d])(?:в|во|для)\\s+(\\p{Lu}[\\p{L}-]*(?:\\s+\\p{Lu}[\\p{L}-]*)?)")
    private val cityAnyRx = Regex("(?<![\\p{L}\\d])(?:в|во|для)\\s+([\\p{L}-]{3,})", RegexOption.IGNORE_CASE)

    private fun city(rest: String): String? {
        cityCapsRx.find(rest)?.let { return it.groupValues[1] }
        val m = cityAnyRx.find(rest) ?: return null
        val w = m.groupValues[1]
        if (w.lowercase() in cityStop || weatherRx.matches(w)) return null
        return w
    }
}

/**
 * City names arrive in the prepositional case ("в Москве"). Geocoding wants the
 * nominative, so this proposes likely nominative forms; the app looks each up and
 * keeps the most populous match.
 */
object CityNames {
    fun candidates(raw: String): List<String> {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val perWord = words.map { forms(it) }
        var combos = listOf("")
        for (options in perWord) {
            combos = combos.flatMap { prefix -> options.map { (prefix + " " + it).trim() } }.take(12)
        }
        return (combos + raw.trim()).distinct()
    }

    private fun forms(w: String): List<String> {
        val out = mutableListOf<String>()
        val l = w.lowercase()
        // Hyphenated names change only the last part: Санкт-Петербурге → Санкт-Петербург.
        if ('-' in w) {
            val head = w.substringBeforeLast('-')
            return forms(w.substringAfterLast('-')).map { "$head-$it" }
        }
        when {
            l.endsWith("ем") -> { out += w.dropLast(2) + "ий"; out += w.dropLast(2) + "ее" } // Нижнем → Нижний
            l.endsWith("ом") -> { out += w.dropLast(2) + "ый"; out += w.dropLast(2) + "ой" } // Великом → Великий? best effort
            l.endsWith("ии") -> out += w.dropLast(1) + "я" // Анталии → Анталия
            l.endsWith("е") -> {
                val stem = w.dropLast(1)
                out += stem + "а"  // Москве → Москва
                out += stem        // Пскове → Псков
                out += stem + "ь"  // Ярославле → Ярославль
                out += stem + "я"  // Туле? → (Тула handled above); Ялте → Ялта
                out += stem + "о"  // Иванове → Иваново
            }
            l.endsWith("и") -> {
                out += w.dropLast(1) + "ь"  // Твери → Тверь, Казани → Казань
                out += w.dropLast(1) + "а"
            }
            l.endsWith("у") -> out += w.dropLast(1) + "а"
        }
        out += w
        return out.distinct()
    }
}

/** Builds the Russian answer texts for informational commands. */
object InfoFormat {
    private val ru = Locale("ru", "RU")

    private val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val monthsGen = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    fun now(kind: Command.Now.Kind, now: LocalDateTime): String = when (kind) {
        Command.Now.Kind.TIME -> "Сейчас %02d:%02d".format(now.hour, now.minute)
        Command.Now.Kind.DATE -> "Сегодня ${weekdays[now.dayOfWeek.value - 1]}, ${now.dayOfMonth} " +
            "${monthsGen[now.monthValue - 1]} ${now.year} года"
    }

    data class WeatherNow(val temperature: Double, val feelsLike: Double, val code: Int, val windMs: Double)
    data class WeatherDay(val date: LocalDate, val code: Int, val min: Double, val max: Double, val precipitationChance: Int?)

    fun weather(place: String, today: LocalDate, current: WeatherNow?, day: WeatherDay): String = buildString {
        val isToday = day.date == today
        append(place).append(", ").append(Describe.date(day.date, today)).append(": ")
        if (isToday && current != null) {
            append("сейчас ").append(temp(current.temperature))
            if (abs(current.feelsLike - current.temperature) >= 2) {
                append(", ощущается как ").append(temp(current.feelsLike))
            }
            append(", ").append(sky(current.code))
            append(", ветер ").append(current.windMs.roundToInt()).append(" м/с. ")
            append("За день от ").append(temp(day.min)).append(" до ").append(temp(day.max))
        } else {
            append("от ").append(temp(day.min)).append(" до ").append(temp(day.max))
            append(", ").append(sky(day.code))
        }
        day.precipitationChance?.let { if (it >= 20) append(", вероятность осадков ").append(it).append("%") }
        append('.')
    }

    fun temp(t: Double): String {
        val r = t.roundToInt()
        return when {
            r > 0 -> "+$r°"
            r < 0 -> "-${-r}°"
            else -> "0°"
        }
    }

    /** WMO weather codes as used by Open-Meteo. */
    fun sky(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "в основном ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        56, 57 -> "ледяная морось"
        61 -> "небольшой дождь"
        63 -> "дождь"
        65 -> "сильный дождь"
        66, 67 -> "ледяной дождь"
        71 -> "небольшой снег"
        73 -> "снег"
        75 -> "сильный снег"
        77 -> "снежная крупа"
        80, 81 -> "ливень"
        82 -> "сильный ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "без осадков"
    }

    data class Rate(val code: String, val name: String, val nominal: Int, val value: Double, val previous: Double?)

    fun rates(rates: List<Rate>, date: LocalDate, amount: Double?): String = buildString {
        append("Курс ЦБ на ").append(date.dayOfMonth).append(' ').append(monthsGen[date.monthValue - 1]).append(':')
        for (r in rates) {
            append('\n')
            if (amount != null) {
                append(number(amount)).append(' ').append(r.code).append(" = ")
                    .append(money(amount * r.value / r.nominal)).append(" руб.")
            } else {
                if (r.nominal != 1) append(r.nominal).append(' ').append(r.name.lowercase(ru)) else append(r.name)
                append(" — ").append(money(r.value)).append(" руб.")
                r.previous?.let { prev ->
                    val diff = r.value - prev
                    if (abs(diff) >= 0.005) {
                        append(if (diff > 0) " (выше на " else " (ниже на ").append(money(abs(diff))).append(')')
                    }
                }
            }
        }
    }

    private fun money(v: Double) = String.format(ru, "%,.2f", v)
    private fun number(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(ru, "%.2f", v)
}
