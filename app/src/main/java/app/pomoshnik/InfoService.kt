package app.pomoshnik

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import app.pomoshnik.core.CityNames
import app.pomoshnik.core.Command
import app.pomoshnik.core.InfoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Answers informational questions from free services that work in Russia without keys:
 * Open-Meteo for weather and the Central Bank of Russia rates (cbr-xml-daily.ru mirror).
 */
class InfoService(private val context: Context, private val settings: Settings) {

    private data class Place(val name: String, val lat: Double, val lon: Double, val population: Long)

    // --- Weather ---

    suspend fun weather(c: Command.Weather): String {
        val today = LocalDate.now()
        val date = c.date ?: today
        if (date.isBefore(today)) throw IOException("прогноз бывает только на будущее")
        if (date.isAfter(today.plusDays(6))) throw IOException("прогноз есть только на неделю вперёд")

        val place = when {
            c.city != null -> findCity(c.city) ?: throw IOException("не нашёл город «${c.city}»")
            else -> currentPlace()
                ?: settings.homeCity.takeIf { it.isNotBlank() }?.let { findCity(it) }
                ?: throw IOException("не знаю, где вы. Разрешите доступ к местоположению или укажите город в настройках")
        }

        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&wind_speed_unit=ms&timezone=auto&forecast_days=7"
        val json = JSONObject(get(url))

        val current = json.optJSONObject("current")?.let {
            InfoFormat.WeatherNow(
                temperature = it.getDouble("temperature_2m"),
                feelsLike = it.optDouble("apparent_temperature", it.getDouble("temperature_2m")),
                code = it.optInt("weather_code", -1),
                windMs = it.optDouble("wind_speed_10m", 0.0),
            )
        }
        val daily = json.getJSONObject("daily")
        val days = daily.getJSONArray("time")
        val index = (0 until days.length()).firstOrNull { days.getString(it) == date.toString() }
            ?: throw IOException("нет прогноза на эту дату")
        val chance = daily.optJSONArray("precipitation_probability_max")
            ?.takeIf { !it.isNull(index) }?.optInt(index)
        val day = InfoFormat.WeatherDay(
            date = date,
            code = daily.getJSONArray("weather_code").optInt(index, -1),
            min = daily.getJSONArray("temperature_2m_min").getDouble(index),
            max = daily.getJSONArray("temperature_2m_max").getDouble(index),
            precipitationChance = chance,
        )
        return InfoFormat.weather(place.name, today, current, day)
    }

    /** Looks up every likely nominative form and keeps the most populous match. */
    private suspend fun findCity(raw: String): Place? {
        var best: Place? = null
        for (name in CityNames.candidates(raw).take(6)) {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&format=json&name=" +
                URLEncoder.encode(name, "UTF-8")
            val results = runCatching { JSONObject(get(url)).optJSONArray("results") }.getOrNull() ?: continue
            if (results.length() == 0) continue
            val r = results.getJSONObject(0)
            val place = Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"), r.optLong("population", 0))
            if (best == null || place.population > best.population) best = place
        }
        return best
    }

    private suspend fun currentPlace(): Place? {
        val loc = lastLocation() ?: return null
        @Suppress("DEPRECATION")
        val name = withContext(Dispatchers.IO) {
            runCatching {
                Geocoder(context, Locale("ru", "RU")).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
        }
        return Place(name ?: "Рядом с вами", loc.latitude, loc.longitude, 0)
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 3 * 3600_000L) return recent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && providers.isNotEmpty()) {
            val fresh = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { cont.resume(it) }
                }
            }
            if (fresh != null) return fresh
        }
        return recent
    }

    // --- Exchange rates ---

    suspend fun rates(c: Command.Currency): String {
        val json = JSONObject(get("https://www.cbr-xml-daily.ru/daily_json.js"))
        val valute = json.getJSONObject("Valute")
        val date = runCatching { OffsetDateTime.parse(json.getString("Date")).toLocalDate() }.getOrElse { LocalDate.now() }
        val rates = c.codes.mapNotNull { code ->
            valute.optJSONObject(code)?.let {
                InfoFormat.Rate(
                    code = code,
                    name = it.getString("Name"),
                    nominal = it.optInt("Nominal", 1),
                    value = it.getDouble("Value"),
                    previous = it.optDouble("Previous").takeIf { p -> !p.isNaN() },
                )
            }
        }
        if (rates.isEmpty()) throw IOException("ЦБ не публикует курс ${c.codes.joinToString(", ")}")
        return InfoFormat.rates(rates, date, c.amount)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("сервис ответил $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
