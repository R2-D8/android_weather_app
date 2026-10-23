package dev.r2d8.weather.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
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
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onOpenAbout = onOpenAbout,
        onThemeMode = { viewModel.setThemeMode(it) },
        onPalette = { viewModel.setPalette(it) },
        onSection = { section, visible -> viewModel.setSectionVisible(section, visible) },
        onCard = { card, visible -> viewModel.setCardVisible(card, visible) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (ColorPalette) -> Unit,
    onSection: (ForecastSection, Boolean) -> Unit,
    onCard: (DetailCardType, Boolean) -> Unit,
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

            Header("Detail cards")
            DetailCardType.entries.forEach { card ->
                SwitchRow(card.label, card in settings.visibleCards) { onCard(card, it) }
            }

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
