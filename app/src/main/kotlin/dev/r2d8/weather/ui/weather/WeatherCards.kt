package dev.r2d8.weather.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.r2d8.weather.domain.HourForecast
import dev.r2d8.weather.domain.RainNowcast
import dev.r2d8.weather.domain.RainNowcaster
import dev.r2d8.weather.domain.WeatherReport
import dev.r2d8.weather.domain.displayLabel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun CurrentCard(report: WeatherReport, now: Instant) {
    val current = report.current
    val today = report.days.firstOrNull { it.date == now.atZone(report.zone).toLocalDate() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconFor(current.condition.kind, current.isDay)),
            contentDescription = current.condition.label,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(current.temperature.asTemperature(), fontSize = 76.sp, style = MaterialTheme.typography.displayLarge)
        Text(current.condition.label, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val low = today?.temperatureMin
        val high = today?.temperatureMax
        if (low != null && high != null) {
            TemperatureRange(low, high, current.temperature)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "Feels like ${current.apparentTemperature.asTemperature()}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // When the place is in another time zone its "today" can differ from the phone's date, so say what time it is there.
        if (ZoneId.systemDefault().rules.getOffset(now) != report.zone.rules.getOffset(now)) {
            Text(
                text = "Local time ${now.asClock(report.zone)} · ${now.asDate(report.zone)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RainCard(report: WeatherReport, now: Instant) {
    val nowcast = remember(report, now) { RainNowcaster.summarize(report.rainSlots, now) }
    SectionCard("Rain in the next hour") {
        Text(nowcast.describe(), style = MaterialTheme.typography.titleMedium)
        val slots = remember(report, now) { report.rainSlots.filter { it.end > now }.take(8) }
        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // Bars scale to at least 1 mm per 15 minutes so drizzle does not look like a downpour.
            val scale = maxOf(1.0, slots.maxOf { it.millimetres })
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.Bottom) {
                slots.forEach { slot ->
                    val fraction = (slot.millimetres / scale).toFloat().coerceIn(0f, 1f)
                    val rains = slot.millimetres >= RainNowcaster.RAIN_THRESHOLD_MM
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 1.dp)
                            .height(if (rains) maxOf(4.dp, 48.dp * fraction) else 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (rains) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+1 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+2 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun RainNowcast.describe(): String = when (this) {
    RainNowcast.Dry -> "No rain expected"
    RainNowcast.Unavailable -> "No short-term rain data for this place"
    is RainNowcast.RainingNow -> {
        val rain = "${intensity.label.replaceFirstChar { it.uppercase() }} rain now"
        if (stopsInMinutes == null) "$rain, not stopping within the hour" else "$rain, stopping in ${stopsInMinutes.asMinutes()}"
    }
    is RainNowcast.RainStarting ->
        "${intensity.label.replaceFirstChar { it.uppercase() }} rain starting in ${startsInMinutes.asMinutes()}"
}

private fun Int.asMinutes(): String = if (this <= 1) "a moment" else "$this min"

@Composable
fun HourlyCard(report: WeatherReport, now: Instant) {
    val hours = remember(report, now) {
        val start = now.truncatedTo(ChronoUnit.HOURS)
        report.hours.filter { it.time >= start }.take(24)
    }
    SectionCard("Hourly forecast") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(hours, key = { it.time.epochSecond }) { HourItem(it, report, isNow = it.time == hours.first().time) }
        }
    }
}

@Composable
private fun HourItem(hour: HourForecast, report: WeatherReport, isNow: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
        Text(if (isNow) "Now" else hour.time.asHour(report.zone), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Icon(
            painter = painterResource(iconFor(hour.condition, hour.isDay, hour.precipitationProbability)),
            contentDescription = hour.condition.displayLabel(hour.precipitationProbability),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(hour.temperature.asTemperature(), style = MaterialTheme.typography.titleSmall)
        val probability = hour.precipitationProbability
        Text(
            text = if (probability != null && probability >= 10) "$probability%" else " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * Today's lowest and highest temperature with a bar between them; the dot marks the current
 * temperature (kept on the bar if it is slightly outside the forecast range).
 */
@Composable
private fun TemperatureRange(low: Double, high: Double, current: Double?) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val fraction = if (current == null || high <= low) null else ((current - low) / (high - low)).toFloat().coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Today from ${low.asTemperature()} to ${high.asTemperature()}"
        },
    ) {
        Text(low.asTemperature(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.padding(horizontal = 12.dp).width(160.dp).height(12.dp)) {
            val y = size.height / 2
            val stroke = 4.dp.toPx()
            drawLine(track, Offset(stroke / 2, y), Offset(size.width - stroke / 2, y), strokeWidth = stroke, cap = StrokeCap.Round)
            if (fraction != null) {
                val radius = size.height / 2
                val x = radius + (size.width - 2 * radius) * fraction
                drawCircle(dot, radius = radius, center = Offset(x, y))
            }
        }
        Text(high.asTemperature(), style = MaterialTheme.typography.titleMedium)
    }
}
