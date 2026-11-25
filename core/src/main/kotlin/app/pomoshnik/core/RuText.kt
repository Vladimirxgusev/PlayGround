package app.pomoshnik.core

/** Helpers for matching Russian text. */
internal object RuText {
    /** Word boundaries that work for Cyrillic (`\b` does not). */
    const val L = "(?<![\\p{L}\\d])"
    const val R = "(?![\\p{L}\\d])"

    // No inline flags such as (?U): Android's ICU regex rejects them and the app crashes.
    // IGNORE_CASE is Unicode-aware on both the JVM (Kotlin adds UNICODE_CASE) and Android.
    fun rx(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Keeps letter case (names in titles), unifies ё and punctuation. */
    fun normalize(input: String): String = input
        .replace('ё', 'е').replace('Ё', 'Е')
        .replace(Regex("[,!?;«»\"“”()]"), " ")
        .replace(Regex("[–—]"), " ")
        .replace(Regex("\\.(?!\\d)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun collapse(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    fun capitalize(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/** Numbers written as digits or as Russian words ("двадцать пять"). */
internal object RuNumbers {
    private val units = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "одного" to 1,
        "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19,
    )
    private val tens = mapOf("двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50)

    /** Non-capturing pattern for one number. */
    val PATTERN: String = run {
        val u = units.keys.sortedByDescending { it.length }.joinToString("|")
        val t = tens.keys.joinToString("|")
        "(?:\\d+|(?:$t)(?:\\s+(?:$u))?|(?:$u))"
    }

    fun parse(s: String): Int? {
        val x = s.trim().lowercase()
        x.toIntOrNull()?.let { return it }
        if (x == "час") return 1
        var total = 0
        for (p in x.split(Regex("\\s+"))) total += tens[p] ?: units[p] ?: return null
        return total
    }
}

/** A length of time: seconds plus calendar days and months. */
internal data class Span(val seconds: Long = 0, val days: Int = 0, val months: Int = 0) {
    val hasClockPart get() = seconds > 0
}

internal object Durations {
    private const val ALL_UNITS =
        "секунд[уы]?|сек|минут[уы]?|мин|час(?:а|ов)?|ч|дн(?:я|ей)|день|суток|сутки|недел[юиь]|месяц(?:а|ев)?"
    private const val CLOCK_UNITS = "секунд[уы]?|сек|минут[уы]?|мин|час(?:а|ов)?|ч"
    private const val SPECIAL = "полчаса|полтора\\s+часа|полторы\\s+минуты"

    /** Pattern for a duration like "1 час 30 минут", "полчаса", "неделю". */
    fun pattern(clockOnly: Boolean = false): String {
        val units = if (clockOnly) CLOCK_UNITS else ALL_UNITS
        val part = "(?:(?:${RuNumbers.PATTERN})\\s*)?(?:$units)${RuText.R}"
        val one = "(?:(?:$SPECIAL)${RuText.R}|$part)"
        return "$one(?:\\s+(?:и\\s+)?$one)*"
    }

    private val partRx = RuText.rx(
        "${RuText.L}(?:(${RuNumbers.PATTERN})\\s*)?($ALL_UNITS)${RuText.R}"
    )

    fun parse(text: String): Span? {
        var s = text.lowercase()
        var seconds = 0L
        var days = 0
        var months = 0
        var any = false
        fun special(p: String, sec: Long) {
            val r = RuText.rx("${RuText.L}$p${RuText.R}")
            if (r.containsMatchIn(s)) {
                seconds += sec; any = true; s = r.replace(s, " ")
            }
        }
        special("полчаса", 1800)
        special("полтора\\s+часа", 5400)
        special("полторы\\s+минуты", 90)
        for (m in partRx.findAll(s)) {
            val n = m.groups[1]?.value?.let { RuNumbers.parse(it) ?: return null } ?: 1
            val u = m.groups[2]!!.value
            any = true
            when {
                u.startsWith("сек") -> seconds += n
                u.startsWith("мин") -> seconds += n * 60L
                u.startsWith("час") || u == "ч" -> seconds += n * 3600L
                u.startsWith("дн") || u == "день" || u.startsWith("сут") -> days += n
                u.startsWith("недел") -> days += 7 * n
                u.startsWith("месяц") -> months += n
            }
        }
        return if (any) Span(seconds, days, months) else null
    }
}
