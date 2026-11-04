package dev.r2d8.weather.ui.weather

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.Categories
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.DayForecast
import dev.r2d8.weather.domain.Sky
import dev.r2d8.weather.domain.WeatherReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun DetailCards(
    report: WeatherReport,
    now: Instant,
    order: List<DetailCardType>,
    visible: Set<DetailCardType>,
    modifier: Modifier = Modifier,
) {
    // Remembered: the Sun and Moon cards compute rise and set times, which is too much for every recomposition.
    val cards = remember(report, now, order, visible) {
        order.filter { it in visible }.mapNotNull { type -> detailContent(type, report, now)?.let { type to it } }
    }
    // Two cards per row, same height within a row.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (_, content) -> DetailCard(content, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class DetailContent(
    val title: String,
    @DrawableRes val icon: Int,
    val value: String,
    val lines: List<String> = emptyList(),
    val iconRotation: Float = 0f,
)

@Composable
private fun DetailCard(content: DetailContent, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(content.icon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(content.iconRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(content.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(content.value, style = MaterialTheme.typography.headlineSmall)
            content.lines.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Builds the text for one card, or null when there is nothing meaningful to show for it. */
private fun detailContent(type: DetailCardType, report: WeatherReport, now: Instant): DetailContent? {
    val current = report.current
    val today = report.days.firstOrNull { it.date == now.atZone(report.zone).toLocalDate() }
    return when (type) {
        DetailCardType.Wind -> DetailContent(
            title = "Wind",
            icon = R.drawable.ic_arrow_upward,
            value = current.windSpeed.asSpeed(),
            lines = listOfNotNull(
                "Gusts ${current.windGusts.asSpeed()}",
                current.windDirection?.let { "From ${Categories.compassPoint(it)}" },
            ),
            // The arrow points where the wind is going, i.e. opposite to the direction it comes from.
            iconRotation = ((current.windDirection ?: 0) + 180).toFloat(),
        )

        DetailCardType.UvIndex -> {
            val hour = report.hours.firstOrNull { it.time == now.truncatedTo(ChronoUnit.HOURS) }
            val uv = hour?.uvIndex ?: today?.uvIndexMax
            uv?.let {
                DetailContent(
                    title = "UV index",
                    icon = R.drawable.ic_sunny,
                    value = "${it.asUvIndex()} · ${Categories.uvIndex(it).label}",
                    lines = listOfNotNull(today?.uvIndexMax?.let { max -> "Max today ${max.asUvIndex()}" }),
                )
            }
        }

        DetailCardType.Sun -> sunContent(report, now, today)

        DetailCardType.Moon -> moonContent(report, now)

        // Relative humidity says how close the air is to saturation, not how much water it holds:
        // cold air near 100% feels dry. The dew point is what tells how humid it feels.
        // Cloud cover is the model's; naming its main layer shows when it is only thin high cloud.
        DetailCardType.Humidity -> DetailContent(
            title = "Humidity",
            icon = R.drawable.ic_humidity_mid,
            value = current.humidity.asPercent(),
            lines = listOfNotNull(
                current.dewPoint?.let { "Dew point ${it.asTemperature()}" },
                current.cloudCover?.let { total ->
                    val layer = mainCloudLayer(current.cloudCoverLow, current.cloudCoverMid, current.cloudCoverHigh)
                    if (layer == null || total < MIN_CLOUD_FOR_LAYER) "Clouds $total%" else "Clouds $total% · $layer"
                },
            ),
        )

        DetailCardType.Pressure -> DetailContent(
            title = "Pressure",
            icon = R.drawable.ic_compress,
            value = current.pressure.asPressure(),
        )

        DetailCardType.AirQuality -> report.airQuality?.let { air ->
            air.europeanAqi?.let { aqi ->
                DetailContent(
                    title = "Air quality",
                    icon = R.drawable.ic_airwave,
                    value = "$aqi · ${Categories.europeanAqi(aqi).label}",
                    lines = listOfNotNull(
                        air.pm25?.let { "PM2.5 ${it.format1()} µg/m³" },
                        air.pm10?.let { "PM10 ${it.format1()} µg/m³" },
                    ),
                )
            }
        }

        DetailCardType.Pollen -> report.airQuality?.pollen?.takeIf { it.isNotEmpty() }?.let { readings ->
            // Pollen data only exists in Europe and is empty outside the season; hide the card without data.
            val present = readings.filter { it.grainsPerCubicMetre >= 1 }.sortedByDescending { it.grainsPerCubicMetre }
            DetailContent(
                title = "Pollen",
                icon = R.drawable.ic_local_florist,
                value = if (present.isEmpty()) "None" else Categories.pollen(present.first().grainsPerCubicMetre).label,
                lines = present.take(3).map {
                    "${it.type.label} ${it.grainsPerCubicMetre.format0()} grains/m³"
                },
            )
        }
    }
}

private const val MIN_CLOUD_FOR_LAYER = 10

/** "low", "mid" or "high": the layer with the most cloud (ties go to the lower one), or null without layer data. */
internal fun mainCloudLayer(low: Int?, mid: Int?, high: Int?): String? {
    if (low == null || mid == null || high == null) return null
    return when {
        low >= mid && low >= high -> "low"
        mid >= high -> "mid"
        else -> "high"
    }
}

/** One local day's rise and set of [body] at the report's place, or null without coordinates. */
private fun skyDay(body: Sky.Body, report: WeatherReport, now: Instant): SkyLines? {
    val latitude = report.latitude ?: return null
    val longitude = report.longitude ?: return null
    val zone = report.zone
    val date = now.atZone(zone).toLocalDate()
    val day = Sky.day(body, date, zone, latitude, longitude)
    val endOfDay = date.plusDays(1).atStartOfDay(zone).toInstant()
    // A day without a rise (or set) shows the next one instead.
    val rise = day.rise ?: Sky.next(body, rising = true, endOfDay, latitude, longitude)
    val set = day.set ?: Sky.next(body, rising = false, endOfDay, latitude, longitude)
    return SkyLines(day, rise?.let { whenText(it, date, zone) }, set?.let { whenText(it, date, zone) }, rise, set)
}

private class SkyLines(
    val day: Sky.Day,
    /** "07:59" today, "tomorrow 01:24", "Thu 01:24" within a week, "15 Jan 11:35" later; null if none within a year. */
    val rise: String?,
    val set: String?,
    val riseAt: Instant?,
    val setAt: Instant?,
)

private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** [time] relative to the local [today]: just the clock today, with "tomorrow", a weekday or a date otherwise. */
internal fun whenText(time: Instant, today: LocalDate, zone: ZoneId): String {
    val local = time.atZone(zone)
    val days = ChronoUnit.DAYS.between(today, local.toLocalDate())
    val clock = time.asClock(zone)
    return when {
        days == 0L -> clock
        days == 1L -> "tomorrow $clock"
        days in 2..6 -> "${weekdayFormat.format(local)} $clock"
        else -> "${dayMonthFormat.format(local)} $clock"
    }
}

/** Sunrise as the big value and sunset below; polar night and midnight sun say so and give the next change. */
private fun sunContent(report: WeatherReport, now: Instant, today: DayForecast?): DetailContent? {
    val sky = skyDay(Sky.Body.Sun, report, now)
    if (sky == null) {
        // Reports cached before coordinates were kept: Open-Meteo's times.
        return today?.let {
            DetailContent(
                title = "Sun",
                icon = R.drawable.ic_wb_twilight,
                value = it.sunrise?.asClock(report.zone) ?: "–",
                lines = listOf("Sunset ${it.sunset?.asClock(report.zone) ?: "–"}"),
            )
        }
    }
    val day = sky.day
    val value: String
    val lines: List<String>
    when {
        day.rise == null && day.set == null -> {
            value = if (day.upAllDay) "Midnight sun" else "Polar night"
            lines = listOfNotNull(
                if (day.upAllDay) sky.set?.let { "Sunset $it" } else sky.rise?.let { "Sunrise $it" },
            )
        }
        day.rise == null -> {
            value = "No sunrise"
            lines = listOfNotNull("Sunset ${sky.set}", sky.rise?.let { "Sunrise $it" })
        }
        else -> {
            value = sky.rise ?: "–"
            lines = listOf("Sunset ${sky.set ?: "–"}")
        }
    }
    return DetailContent(title = "Sun", icon = R.drawable.ic_wb_twilight, value = value, lines = lines)
}

/** Phase now, then today's moonrise and moonset in time order; a missing one is replaced by the next. */
private fun moonContent(report: WeatherReport, now: Instant): DetailContent? {
    val sky = skyDay(Sky.Body.Moon, report, now) ?: return null
    val phase = Sky.moonPhase(now)
    val events = listOfNotNull(
        sky.riseAt?.let { it to "Rise ${sky.rise}" },
        sky.setAt?.let { it to "Set ${sky.set}" },
    ).sortedBy { it.first }.map { it.second }
    val day = sky.day
    val lines = listOf(phase.name) +
        listOfNotNull(
            "Up all day".takeIf { day.upAllDay },
            "Below the horizon all day".takeIf { day.rise == null && day.set == null && !day.upAllDay },
        ) + events
    return DetailContent(title = "Moon", icon = R.drawable.ic_clear_night, value = "${phase.percent}% lit", lines = lines)
}

private fun Double.format0() = String.format(Locale.ROOT, "%.0f", this)

private fun Double.format1() = String.format(Locale.ROOT, "%.1f", this)
