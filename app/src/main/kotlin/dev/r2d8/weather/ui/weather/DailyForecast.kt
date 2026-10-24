package dev.r2d8.weather.ui.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.DayDetail
import dev.r2d8.weather.domain.DayForecast
import dev.r2d8.weather.domain.DayPartSummary
import dev.r2d8.weather.domain.DayParts
import dev.r2d8.weather.domain.HourForecast
import dev.r2d8.weather.domain.RainNowcaster
import dev.r2d8.weather.domain.displayLabel
import dev.r2d8.weather.domain.WeatherReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val NONE_OPEN = Long.MIN_VALUE

private val dayFormat = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)

/**
 * The multi-day list. Tap a day to see its day parts and hour-by-hour values ([details] says which);
 * one day is open at a time. With no details switched on, days do not expand.
 */
@Composable
fun DailyCard(report: WeatherReport, now: Instant, details: Set<DayDetail>) {
    val today = now.atZone(report.zone).toLocalDate()
    val days = remember(report, now) { report.days.filter { it.date >= today } }
    // Saved across rotation; LocalDate is stored as its epoch day.
    var openDay by rememberSaveable { mutableStateOf(NONE_OPEN) }

    SectionCard("${days.size}-day forecast") {
        Column {
            days.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val expandable = details.isNotEmpty()
                val expanded = expandable && openDay == day.date.toEpochDay()
                DayRow(
                    day, today, expanded,
                    onClick = if (expandable) ({ openDay = if (expanded) NONE_OPEN else day.date.toEpochDay() }) else null,
                )
                AnimatedVisibility(visible = expanded) {
                    DayDetails(report, day, now, details)
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: DayForecast, today: LocalDate, expanded: Boolean, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val relative = when (day.date) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> null
            }
            Text(relative ?: dayFormat.format(day.date), style = MaterialTheme.typography.bodyLarge)
            // The date under "Today"/"Tomorrow" avoids confusion when the place is in another time zone.
            if (relative != null) {
                Text(day.date.asDate(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            // Low chances are left blank in the day list to keep it calm; the hourly table shows every value.
            text = day.precipitationProbabilityMax?.takeIf { it >= 10 }?.let { "$it%" }.orEmpty(),
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Icon(
            painter = painterResource(iconFor(day.condition, isDay = true, rainChance = day.precipitationProbabilityMax)),
            contentDescription = day.condition.displayLabel(day.precipitationProbabilityMax),
            modifier = Modifier.padding(horizontal = 12.dp).size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = day.temperatureMin.asTemperature(),
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = day.temperatureMax.asTemperature(),
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.titleSmall,
        )
        if (onClick != null) {
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = if (expanded) "Collapse" else "Expand day",
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Keeps the columns aligned with the expandable layout.
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun DayDetails(report: WeatherReport, day: DayForecast, now: Instant, details: Set<DayDetail>) {
    // For today only the hours still to come; other days show all 24.
    val hours = remember(report, day, now) {
        DayParts.hoursOf(report.hours, day.date, report.zone, from = now.truncatedTo(ChronoUnit.HOURS))
    }
    val parts = remember(hours) { DayParts.summarize(hours, report.zone) }
    Column(Modifier.padding(bottom = 12.dp)) {
        val showHourly = DayDetail.Hourly in details
        if (DayDetail.DayParts in details && parts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                parts.forEach { DayPartCell(it, Modifier.weight(1f)) }
            }
            if (showHourly) Spacer(Modifier.height(12.dp))
        }
        if (showHourly) HourTable(hours, report.zone)
    }
}

@Composable
private fun DayPartCell(part: DayPartSummary, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(part.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Icon(
            painter = painterResource(iconFor(part.condition, part.isDay, part.maxPrecipitationProbability)),
            contentDescription = part.condition.displayLabel(part.maxPrecipitationProbability),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = part.condition.displayLabel(part.maxPrecipitationProbability),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = "${part.temperatureMin.asTemperature()} / ${part.temperatureMax.asTemperature()}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = if (part.precipitationMm >= RainNowcaster.RAIN_THRESHOLD_MM) part.precipitationMm.asMillimetres() else " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun HourTable(hours: List<HourForecast>, zone: ZoneId) {
    Column {
        HourTableRow(
            time = { Text("Hour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            icon = {},
            temperature = "Temp",
            rain = "Rain",
            chance = "Chance",
            header = true,
        )
        hours.forEach { hour ->
            HourTableRow(
                time = { Text(hour.time.asClock(zone), style = MaterialTheme.typography.bodyMedium) },
                icon = {
                    Icon(
                        painter = painterResource(iconFor(hour.condition, hour.isDay, hour.precipitationProbability)),
                        contentDescription = hour.condition.displayLabel(hour.precipitationProbability),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                temperature = hour.temperature.asTemperature(),
                rain = if ((hour.precipitation ?: 0.0) >= RainNowcaster.RAIN_THRESHOLD_MM) hour.precipitation.asMillimetres() else "–",
                chance = hour.precipitationProbability.asChance(),
            )
        }
    }
}

@Composable
private fun HourTableRow(
    time: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    temperature: String,
    rain: String,
    chance: String,
    header: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val colour = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(if (header) 24.dp else 36.dp),
    ) {
        Row(Modifier.width(52.dp)) { time() }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) { icon() }
        Text(temperature, Modifier.weight(1f), textAlign = TextAlign.Center, style = style, color = colour)
        Text(rain, Modifier.weight(1.3f), textAlign = TextAlign.Center, style = style, color = colour)
        Text(
            chance,
            Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = style,
            color = if (header) colour else MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** Precipitation probability as text, "–" when unknown. */
private fun Int?.asChance(): String = if (this == null) "–" else "$this%"
