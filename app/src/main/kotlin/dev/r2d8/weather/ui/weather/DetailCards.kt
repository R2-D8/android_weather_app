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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.Categories
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.Moon
import dev.r2d8.weather.domain.WeatherReport
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun DetailCards(report: WeatherReport, now: Instant, visible: Set<DetailCardType>, modifier: Modifier = Modifier) {
    val cards = DetailCardType.entries
        .filter { it in visible }
        .mapNotNull { type -> detailContent(type, report, now)?.let { type to it } }
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

        DetailCardType.Sun -> today?.let {
            DetailContent(
                title = "Sun",
                icon = R.drawable.ic_wb_twilight,
                value = it.sunrise?.asClock(report.zone) ?: "–",
                lines = listOf("Sunset ${it.sunset?.asClock(report.zone) ?: "–"}"),
            )
        }

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

/** Today's moonrise and moonset in time order, and the phase now. Needs the report's coordinates. */
private fun moonContent(report: WeatherReport, now: Instant): DetailContent? {
    val latitude = report.latitude ?: return null
    val longitude = report.longitude ?: return null
    val today = now.atZone(report.zone).toLocalDate()
    val day = Moon.day(today, report.zone, latitude, longitude)
    val phase = Moon.phase(now)
    val events = listOfNotNull(
        day.rise?.let { it to "Rise ${it.asClock(report.zone)}" },
        day.set?.let { it to "Set ${it.asClock(report.zone)}" },
    ).sortedBy { it.first }.map { it.second }
    // The Moon rises about 50 minutes later each day, so once a month a day has no rise (or no set).
    val times = when {
        events.isNotEmpty() -> events + listOfNotNull(
            "No moonrise today".takeIf { day.rise == null },
            "No moonset today".takeIf { day.set == null },
        )
        day.upAllDay -> listOf("Up all day")
        else -> listOf("Below the horizon all day")
    }
    return DetailContent(
        title = "Moon",
        icon = R.drawable.ic_clear_night,
        value = "${phase.percent}% lit",
        lines = listOf(phase.name) + times,
    )
}

private fun Double.format0() = String.format(Locale.ROOT, "%.0f", this)

private fun Double.format1() = String.format(Locale.ROOT, "%.1f", this)
