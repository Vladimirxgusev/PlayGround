package app.pomoshnik.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoTest {
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0)
    private val today = now.toLocalDate()

    private fun parse(s: String) = CommandParser.parse(s, now)

    @Test fun weatherHere() = assertEquals(Command.Weather(), parse("Какая погода"))

    @Test fun weatherTomorrowInCity() =
        assertEquals(Command.Weather("Казани", today.plusDays(1)), parse("какая погода завтра в Казани"))

    @Test fun weatherTwoWordCity() =
        assertEquals(Command.Weather("Нижнем Новгороде"), parse("погода в Нижнем Новгороде"))

    @Test fun weatherWeekdayIsNotCity() =
        assertEquals(Command.Weather(null, LocalDate.of(2026, 11, 27)), parse("будет ли дождь в пятницу"))

    @Test fun weatherLowercaseCity() =
        assertEquals(Command.Weather("сочи"), parse("какая температура в сочи"))

    @Test fun currencyDollar() = assertEquals(Command.Currency(listOf("USD")), parse("Курс доллара"))

    @Test fun currencyTwo() =
        assertEquals(Command.Currency(listOf("USD", "EUR")), parse("какой курс доллара и евро"))

    @Test fun currencyGeneric() =
        assertEquals(Command.Currency(listOf("USD", "EUR", "CNY")), parse("курс валют"))

    @Test fun currencyAmount() =
        assertEquals(Command.Currency(listOf("USD"), 100.0), parse("сколько будет 100 долларов в рублях"))

    @Test fun currencyYuan() = assertEquals(Command.Currency(listOf("CNY")), parse("почем юань"))

    @Test fun timeQuestion() = assertEquals(Command.Now(Command.Now.Kind.TIME), parse("Который час?"))

    @Test fun dateQuestion() = assertEquals(Command.Now(Command.Now.Kind.DATE), parse("какое сегодня число"))

    @Test fun freeQuestion() =
        assertEquals(Command.Question("Сколько лететь до Сочи?"), parse("Сколько лететь до Сочи?"))

    @Test fun freeQuestionNoMark() =
        assertEquals(Command.Question("почему небо голубое"), parse("почему небо голубое"))

    @Test fun reminderAboutRatesIsTask() = assertEquals(
        Command.CreateTask("Узнать курс доллара", today.plusDays(1)),
        parse("напомни завтра узнать курс доллара"),
    )

    @Test fun cityForms() {
        assertTrue("Москва" in CityNames.candidates("Москве"))
        assertTrue("Казань" in CityNames.candidates("Казани"))
        assertTrue("Нижний Новгород" in CityNames.candidates("Нижнем Новгороде"))
        assertTrue("Санкт-Петербург" in CityNames.candidates("Санкт-Петербурге"))
        assertTrue("Сочи" in CityNames.candidates("Сочи"))
    }

    @Test fun weatherText() {
        val day = InfoFormat.WeatherDay(today, 73, -5.4, -1.2, 70)
        assertEquals(
            "Москва, сегодня: сейчас -3°, ощущается как -8°, снег, ветер 4 м/с. За день от -5° до -1°, вероятность осадков 70%.",
            InfoFormat.weather("Москва", today, InfoFormat.WeatherNow(-3.2, -7.9, 73, 4.3), day),
        )
        assertEquals(
            "Москва, завтра: от +1° до +6°, пасмурно.",
            InfoFormat.weather("Москва", today, null, InfoFormat.WeatherDay(today.plusDays(1), 3, 0.8, 5.6, 10)),
        )
    }

    @Test fun ratesText() {
        val usd = InfoFormat.Rate("USD", "Доллар США", 1, 92.5, 92.1)
        val jpy = InfoFormat.Rate("JPY", "Японских иен", 100, 60.12, 60.12)
        assertEquals(
            "Курс ЦБ на 26 ноября:\nДоллар США — 92,50 руб. (выше на 0,40)\n100 японских иен — 60,12 руб.",
            InfoFormat.rates(listOf(usd, jpy), LocalDate.of(2026, 11, 26), null),
        )
        assertEquals(
            "Курс ЦБ на 26 ноября:\n100 USD = 9 250,00 руб.",
            InfoFormat.rates(listOf(usd), LocalDate.of(2026, 11, 26), 100.0),
        )
    }

    @Test fun nowText() =
        assertEquals("Сегодня среда, 25 ноября 2026 года", InfoFormat.now(Command.Now.Kind.DATE, now))

    @Test fun aiWeather() = assertEquals(
        Command.Weather("Москва", today),
        AiFallback.toCommand(AiFallback.Answer("weather", city = "Москва", date = "2026-11-25"), "x", now),
    )

    @Test fun aiAnswer() = assertEquals(
        Command.Answer("Около двух часов."),
        AiFallback.toCommand(AiFallback.Answer("question", answer = "Около двух часов."), "x", now),
    )
}
