package dev.r2d8.weather.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import dev.r2d8.weather.data.map.PackPlanner
import dev.r2d8.weather.data.map.PackStatus
import dev.r2d8.weather.data.radar.ServerAddress
import dev.r2d8.weather.data.radar.ServerTestResult
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.RadarPalette
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.r2d8.weather.R
import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.ColorPalette
import dev.r2d8.weather.domain.DetailCardType
import dev.r2d8.weather.domain.DayDetail
import dev.r2d8.weather.domain.ForecastSection
import dev.r2d8.weather.domain.ThemeMode
import dev.r2d8.weather.ui.theme.swatch

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serverTest by viewModel.serverTest.collectAsStateWithLifecycle()
    val offlineMaps by viewModel.offlineMaps.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        serverTest = serverTest,
        offlineMaps = offlineMaps,
        onHistoryOnMobileData = { viewModel.setRadarHistoryOnMobileData(it) },
        onHomeServerEnabled = { viewModel.setHomeServerEnabled(it) },
        onHomeServerAddress = { viewModel.setHomeServerAddress(it) },
        onTestServer = { viewModel.testServer(it) },
        onRadarPalette = { viewModel.setRadarPalette(it) },
        onMapStyle = { viewModel.setMapStyle(it) },
        onBack = onBack,
        onOpenAbout = onOpenAbout,
        onThemeMode = { viewModel.setThemeMode(it) },
        onPalette = { viewModel.setPalette(it) },
        onSection = { section, visible -> viewModel.setSectionVisible(section, visible) },
        onCard = { card, visible -> viewModel.setCardVisible(card, visible) },
        onDayDetail = { detail, visible -> viewModel.setDayDetailVisible(detail, visible) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverTest: ServerTestState,
    offlineMaps: OfflineMapsUi,
    onHistoryOnMobileData: (Boolean) -> Unit,
    onHomeServerEnabled: (Boolean) -> Unit,
    onHomeServerAddress: (String) -> Unit,
    onTestServer: (String) -> Unit,
    onRadarPalette: (RadarPalette) -> Unit,
    onMapStyle: (MapStyle) -> Unit,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (ColorPalette) -> Unit,
    onSection: (ForecastSection, Boolean) -> Unit,
    onCard: (DetailCardType, Boolean) -> Unit,
    onDayDetail: (DayDetail, Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Header("Appearance")
            ThemeModePicker(settings.themeMode, onThemeMode)
            PalettePicker(settings.palette, onPalette)

            Header("Sections on the main screen")
            ForecastSection.entries.forEach { section ->
                SwitchRow(section.label, section in settings.visibleSections) { onSection(section, it) }
            }

            Header("When a day is expanded")
            DayDetail.entries.forEach { detail ->
                SwitchRow(detail.label, detail in settings.visibleDayDetails) { onDayDetail(detail, it) }
            }

            Header("Detail cards")
            DetailCardType.entries.forEach { card ->
                SwitchRow(card.label, card in settings.visibleCards) { onCard(card, it) }
            }

            Header("Radar")
            MapStylePicker(settings.mapStyle, onMapStyle)
            ListItem(
                headlineContent = { Text("Load radar history on mobile data") },
                supportingContent = {
                    Text("Off: on mobile data (or any metered connection) the radar shows only the newest picture, to save data.")
                },
                trailingContent = { Switch(checked = settings.radarHistoryOnMobileData, onCheckedChange = onHistoryOnMobileData) },
                modifier = Modifier.clickable { onHistoryOnMobileData(!settings.radarHistoryOnMobileData) },
            )

            OfflineMapsSection(offlineMaps)

            HomeServerSection(
                settings = settings,
                serverTest = serverTest,
                onEnabled = onHomeServerEnabled,
                onAddress = onHomeServerAddress,
                onTest = onTestServer,
                onPalette = onRadarPalette,
            )

            HorizontalDivider(Modifier.padding(top = 16.dp))
            ListItem(
                headlineContent = { Text("About and data sources") },
                leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenAbout),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
            ) { Text(mode.label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PalettePicker(selected: ColorPalette, onSelect: (ColorPalette) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorPalette.entries.forEach { palette ->
            FilterChip(
                selected = palette == selected,
                onClick = { onSelect(palette) },
                label = { Text(palette.label) },
                leadingIcon = palette.swatch?.let { colour ->
                    { Box(Modifier.size(16.dp).clip(CircleShape).background(colour)) }
                },
            )
        }
    }
}

/** Status only: offline maps update by themselves when the app is opened on Wi-Fi. */
@Composable
private fun OfflineMapsSection(state: OfflineMapsUi) {
    Header("Offline maps")
    Text(
        "The map around each saved place (about ${PackPlanner.RADIUS_KM.toInt()} km) is downloaded on Wi-Fi " +
            "while the app is open. There the map loads from the phone and uses no mobile data; only the " +
            "rain pictures are downloaded. The map data is updated about once a month, also only on Wi-Fi. " +
            "Satellite pictures are not stored.",
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.packs.isEmpty()) {
        ListItem(headlineContent = { Text("No saved places yet") })
    }
    state.packs.forEach { (place, status) ->
        ListItem(
            headlineContent = { Text(place.place.name) },
            supportingContent = { Text(status.describe()) },
            trailingContent = if (status is PackStatus.Downloading) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
    }
    state.mapData.version?.let { version ->
        val since = state.mapData.pinnedAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
        Text(
            "Map data version ${version.name}" + (since?.let { ", in use since $it" } ?: ""),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PackStatus.describe(): String = when (this) {
    PackStatus.Waiting -> "Waiting for Wi-Fi"
    is PackStatus.Downloading -> "Downloading" + (percent?.let { " · $it%" } ?: "") + " · ${bytes.asMegabytes()}"
    is PackStatus.Ready -> "Ready · ${bytes.asMegabytes()}"
    is PackStatus.Failed -> "Problem: $message"
}

private fun Long.asMegabytes(): String = "%.1f MB".format(java.util.Locale.ROOT, this / 1_048_576.0)

/** The optional home radar server. Everything here is off or empty until the user fills it in. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeServerSection(
    settings: AppSettings,
    serverTest: ServerTestState,
    onEnabled: (Boolean) -> Unit,
    onAddress: (String) -> Unit,
    onTest: (String) -> Unit,
    onPalette: (RadarPalette) -> Unit,
) {
    Header("Home server (optional)")
    Text(
        "Your own radar server gives sharper Italian radar. It needs Tailscale to be on in this phone. " +
            "It is off by default and nothing is ever sent to it unless you switch it on.",
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow("Use the home server", settings.homeServerEnabled, onEnabled)

    // The text is kept locally while typing so the cursor does not jump; each change is also saved.
    // The saved settings arrive a moment after the screen first appears, so until the user types
    // the field follows them (otherwise it would show an empty field for an address that is saved).
    var address by remember { mutableStateOf(settings.homeServerAddress) }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.homeServerAddress) {
        if (!typed) address = settings.homeServerAddress
    }
    val problem = ServerAddress.problem(address).takeIf { address.isNotBlank() }
    OutlinedTextField(
        value = address,
        onValueChange = {
            typed = true
            address = it
            onAddress(it)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        singleLine = true,
        label = { Text("Server address") },
        placeholder = { Text("address:port") },
        isError = problem != null,
        supportingText = { Text(problem ?: "The address and port of your radar server.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onTest(address) },
            enabled = address.isNotBlank() && serverTest != ServerTestState.Testing,
        ) { Text("Test connection") }
        Spacer(Modifier.width(12.dp))
        when (serverTest) {
            ServerTestState.Idle -> Unit
            ServerTestState.Testing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is ServerTestState.Done -> when (val result = serverTest.result) {
                is ServerTestResult.Ok -> Text(
                    "Connected: ${result.frames} radar pictures stored.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ServerTestResult.Problem -> Text(
                    result.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Text(
        "Server radar colours",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadarPalette.entries.forEach { palette ->
            FilterChip(
                selected = palette == settings.radarPalette,
                onClick = { onPalette(palette) },
                label = { Text(palette.label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MapStylePicker(selected: MapStyle, onSelect: (MapStyle) -> Unit) {
    Text(
        "Map style",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        "Detailed shows the most. The satellite view uses the Detailed names and roads.",
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapStyle.entries.forEach { style ->
            FilterChip(selected = style == selected, onClick = { onSelect(style) }, label = { Text(style.label) })
        }
    }
}
