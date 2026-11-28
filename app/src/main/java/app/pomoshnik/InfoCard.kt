package app.pomoshnik

import org.json.JSONArray
import org.json.JSONObject

data class InfoReply(val text: String, val card: InfoCard)

data class InfoCard(
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val headline: String? = null,
    val metrics: List<Metric> = emptyList(),
    val weatherCode: Int? = null,
    val temperatures: List<Double> = emptyList(),
) {
    enum class Kind { WEATHER, RATES, TIME, DATE }
    data class Metric(val label: String, val value: String, val detail: String? = null, val trend: Int = 0)

    internal fun encode() = JSONObject().put("kind", kind.name).put("title", title)
        .put("subtitle", subtitle).put("headline", headline).put("weatherCode", weatherCode)
        .put("temperatures", JSONArray(temperatures))
        .put("metrics", JSONArray(metrics.map {
            JSONObject().put("label", it.label).put("value", it.value)
                .put("detail", it.detail).put("trend", it.trend)
        }))

    companion object {
        internal fun decode(value: JSONObject): InfoCard? = runCatching {
            val metrics = value.getJSONArray("metrics")
            val temperatures = value.optJSONArray("temperatures")
            InfoCard(
                kind = Kind.valueOf(value.getString("kind")),
                title = value.getString("title"),
                subtitle = value.getString("subtitle"),
                headline = value.optionalText("headline"),
                metrics = List(metrics.length()) { index ->
                    val metric = metrics.getJSONObject(index)
                    Metric(metric.getString("label"), metric.getString("value"), metric.optionalText("detail"),
                        metric.optInt("trend"))
                },
                weatherCode = value.optInt("weatherCode").takeUnless { value.isNull("weatherCode") },
                temperatures = List(temperatures?.length() ?: 0) { temperatures!!.getDouble(it) },
            )
        }.getOrNull()

        private fun JSONObject.optionalText(key: String): String? = optString(key).takeUnless { isNull(key) }
    }
}
