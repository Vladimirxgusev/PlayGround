package app.pomoshnik

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pomoshnik.core.InfoFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InfoCardView(card: InfoCard) {
    val colors = MaterialTheme.colorScheme
    val icon = when (card.kind) {
        InfoCard.Kind.WEATHER -> when (card.weatherCode ?: -1) {
            0, 1 -> Icons.Filled.WbSunny
            45, 48 -> Icons.Filled.Air
            in 71..77, 85, 86 -> Icons.Filled.AcUnit
            in 51..67, in 80..82 -> Icons.Filled.WaterDrop
            in 95..99 -> Icons.Filled.Thunderstorm
            else -> Icons.Filled.Cloud
        }
        InfoCard.Kind.RATES -> Icons.Filled.CurrencyExchange
        InfoCard.Kind.TIME -> Icons.Filled.Schedule
        InfoCard.Kind.DATE -> Icons.Filled.CalendarToday
    }
    val tint = when (card.kind) {
        InfoCard.Kind.WEATHER, InfoCard.Kind.TIME -> colors.primary
        InfoCard.Kind.RATES, InfoCard.Kind.DATE -> colors.tertiary
    }
    Column(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(lerp(colors.surfaceVariant, tint, 0.10f), colors.surface)),
            RoundedCornerShape(20.dp),
        ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        card.headline?.let { Text(it, color = colors.onSurface, style = MaterialTheme.typography.headlineLarge) }
        if (card.kind == InfoCard.Kind.WEATHER) {
            if (card.temperatures.size > 1) TemperatureLine(card.temperatures)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (metric in card.metrics) {
                    Column(
                        Modifier.widthIn(min = 65.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(metric.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Text(metric.value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    }
                }
            }
        } else {
            card.metrics.forEachIndexed { index, metric ->
                if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(metric.label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        metric.detail?.let {
                            val sign = when {
                                metric.trend > 0 -> "↑ "
                                metric.trend < 0 -> "↓ "
                                else -> ""
                            }
                            Text(sign + it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    Text(metric.value, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TemperatureLine(values: List<Double>) {
    val smallest = values.min()
    val largest = values.max()
    val span = (largest - smallest).coerceAtLeast(1.0)
    val lineColor = MaterialTheme.colorScheme.primary
    Text("Температура по часам", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(43.dp).semantics {
        contentDescription = "Температура в течение дня от ${InfoFormat.temp(smallest)} до ${InfoFormat.temp(largest)}"
    }) {
        val inset = 3.dp.toPx()
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = inset + (size.width - 2 * inset) * index / (values.size - 1)
            val y = size.height - inset - ((value - smallest) / span).toFloat() * (size.height - 2 * inset)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(lineColor.copy(alpha = 0.25f), Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
