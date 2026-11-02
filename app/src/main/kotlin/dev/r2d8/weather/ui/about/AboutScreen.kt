package dev.r2d8.weather.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.r2d8.weather.R

/** Credits required by the licences of the data and assets the app uses. Extend it when sources are added. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About and data sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Source(
                title = "Weather forecast",
                text = "Weather data by Open-Meteo.com, licensed under CC BY 4.0.",
                linkLabel = "open-meteo.com",
                url = "https://open-meteo.com/",
            )
            Source(
                title = "Air quality and pollen",
                text = "Air quality and pollen data by Open-Meteo.com. Generated using Copernicus Atmosphere " +
                    "Monitoring Service information (CAMS).",
                linkLabel = "atmosphere.copernicus.eu",
                url = "https://atmosphere.copernicus.eu/",
            )
            Source(
                title = "Temperature map, model rain and snow line (home server only)",
                text = "Temperature, precipitation and snow line from Deutscher Wetterdienst (DWD), ICON-D2 and " +
                    "ICON-EU models, licensed under CC BY 4.0. Temperature corrected for ground height, rain and " +
                    "snow told apart by the home server.",
                linkLabel = "opendata.dwd.de",
                url = "https://opendata.dwd.de/",
            )
            Source(
                title = "Ground height for the temperature map and snow",
                text = "Mapzen Terrain Tiles: SRTM and GMTED2010 courtesy of the U.S. Geological Survey; ETOPO1 by " +
                    "NOAA; Europe terrain data produced using Copernicus data and information funded by the European " +
                    "Union (EU-DEM layers); Austria © offene Daten Österreichs; Norway © Kartverket; and other sources.",
                linkLabel = "Terrain Tiles attribution",
                url = "https://github.com/tilezen/joerd/blob/master/docs/attribution.md",
            )
            Source(
                title = "Icons",
                text = "Weather icons from Google Material Symbols, licensed under the Apache License 2.0.",
                linkLabel = "fonts.google.com/icons",
                url = "https://fonts.google.com/icons",
            )
        }
    }
}

@Composable
private fun Source(title: String, text: String, linkLabel: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { uriHandler.openUri(url) }) { Text(linkLabel) }
    }
}
