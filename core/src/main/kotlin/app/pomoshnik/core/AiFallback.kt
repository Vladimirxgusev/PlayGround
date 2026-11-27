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
            - Создавай команду только при ясной просьбе что-либо сделать на телефоне.
            - "напомни ..." — это task; встречи, приёмы, дни рождения, мероприятия — event;
            - относительные даты ("завтра", "в пятницу", "через неделю") считай от текущей даты;
            - если время сказано без "утра/вечера" и речь о встрече или делах, выбирай разумное
              дневное время (например, "в 3" — это 15:00);
            - событие без времени — это событие на весь день (time = null);
            - на вопросы о погоде и курсах НЕ отвечай сам: верни weather или currency, данные подставит приложение;
            - если не знаешь точного ответа на question, так и скажи.
        """.trimIndent()
    }

    private val actionOpening = Regex(
        """^(?:пожалуйста[, ]+)?(?:(?:можешь|можно) +)?(?:постав|завед|установ|включ|запуст|засек|добав|созда|запиш|напомн|запланир|назнач)[\p{L}]*(?=$|[\s,!?])""",
        RegexOption.IGNORE_CASE,
    )
    private val discussionOpening = Regex(
        """^(?:почему|зачем|что|кто|как|сколько|где|когда|какой|какая|какие|расскажи|объясни|сравни|придумай|посоветуй)(?:\s|[?!,.]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun isDeviceRequest(text: String): Boolean = actionOpening.containsMatchIn(text.trim())

    fun isDiscussion(text: String): Boolean = discussionOpening.containsMatchIn(text.trim())

    fun localReply(text: String, serviceUnavailable: Boolean = false): String {
        val words = RuText.normalize(text).trimEnd('?', '!', '.', ',')
        return when {
            words in setOf("привет", "здравствуй", "доброе утро", "добрый вечер") ->
                "Привет! Я на связи. Что обсудим?"
            words in setOf("спасибо", "спасибо тебе", "благодарю") -> "Пожалуйста!"
            isDiscussion(text) || text.trimEnd().endsWith("?") || isDeviceRequest(text) || words.split(' ').size > 5 ->
                if (serviceUnavailable) {
                    "Сейчас не удалось получить ответ от YandexGPT. Давай попробуем ещё раз чуть позже."
                } else {
                    "Для развёрнутого ответа мне нужен YandexGPT. Подключи его в настройках «Секретутки»."
                }
            else -> {
                val phrase = text.trim().take(110)
                when (Math.floorMod(words.hashCode(), 3)) {
                    0 -> "«$phrase» звучит как пароль в клуб любителей абсурда. Считай, что я тебя впустила."
                    1 -> "«$phrase» — отличное название для странной сказки. Она начинается дождливой ночью, когда лягушки решили открыть детективное агентство."
                    else -> "Записываю «$phrase» в словарь выражений, которыми удобно начинать очень неожиданный разговор."
                }
            }
        }
    }

    /** Prompt for answering conversation directly, independent of device actions. */
    fun answerPrompt(now: LocalDateTime): String {
        val date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weekday = weekdayNames[now.dayOfWeek.value - 1]
        return """
            Ты — «Секретутка», русскоязычный голосовой помощник на телефоне. Сегодня $date, $weekday.
            Веди естественный диалог. Отвечай на вопрос сразу и конкретно. На простой вопрос отвечай коротко,
            но если человеку нужно объяснение, сравнение или инструкция, дай достаточно деталей и пример.
            Разделяй длинный ответ на короткие абзацы, допустимы обычные маркеры списка. Без markdown-разметки.
            На шутки, абсурдные словосочетания, просторечие или мат реагируй по сути, легко и с уместным юмором.
            В короткой странной фразе можно увидеть игру слов или предложить мини-историю.
            Если контекста недостаточно для полезного ответа на серьёзный вопрос, задай один точный вопрос.
            Не выдумывай факты, источники, новости, цифры или выполненные действия. Если нужны свежие сведения,
            которых у тебя нет, скажи об этом. Не утверждай, что изменил что-либо на телефоне.
        """.trimIndent()
    }

    fun toCommand(a: Answer, original: String, now: LocalDateTime): Command {
        val time = a.time?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }
        val date = a.date?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        val title = a.title?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
        return when (a.type?.trim()?.lowercase()) {
            "alarm" -> {
                val t = time ?: return Command.Unknown(original, "ИИ не смог определить время будильника", needsAlarmTime = true)
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
