package dev.r2d8.weather.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import dev.r2d8.weather.domain.ColorPalette

@Composable
fun WeatherTheme(
    darkTheme: Boolean,
    palette: ColorPalette,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (palette == ColorPalette.Dynamic) {
        // minSdk is 33, so Material You dynamic colours (Android 12+) are always available.
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        fixedColorScheme(palette.hue, darkTheme)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Preview colour for the settings screen; null for dynamic, whose colours come from the wallpaper. */
internal val ColorPalette.swatch: Color?
    get() = if (this == ColorPalette.Dynamic) null else fixedColorScheme(hue, dark = false).primary

internal val ColorPalette.hue: Float
    get() = when (this) {
        ColorPalette.Blue -> 215f
        ColorPalette.Green -> 140f
        ColorPalette.Orange -> 28f
        ColorPalette.Purple -> 275f
        ColorPalette.Dynamic -> 215f // unused: dynamic colours come from the wallpaper
    }

/**
 * A full Material 3 colour scheme derived from one hue: accents at the hue (plus shifted hues for
 * the secondary and tertiary roles) and surfaces as the same hue at low saturation. Lightness
 * values follow the usual Material pattern (light theme: dark accents on pale surfaces; dark
 * theme: pale accents on dark surfaces) so text keeps good contrast.
 */
internal fun fixedColorScheme(hue: Float, dark: Boolean): ColorScheme {
    fun tone(h: Float, saturation: Float, lightness: Float) = Color.hsl(h % 360f, saturation, lightness)

    // Equal HSL lightness is not equal brightness (green looks far lighter than blue), so light-theme
    // accents start at a typical lightness and darken until text on them is readable on both the
    // white-ish cards and the "on" colour (white).
    fun accent(h: Float, saturation: Float, startLightness: Float): Color {
        var lightness = startLightness
        val cardColour = tone(hue, 0.15f, 0.94f)
        while (lightness > 0.05f) {
            val colour = tone(h, saturation, lightness)
            if (contrastRatio(colour, Color.White) >= MIN_CONTRAST && contrastRatio(colour, cardColour) >= MIN_CONTRAST) return colour
            lightness -= 0.01f
        }
        return tone(h, saturation, 0.05f)
    }
    val primary = hue
    val secondary = hue + 30f
    val tertiary = hue + 75f
    return if (dark) {
        darkColorScheme(
            primary = tone(primary, 0.75f, 0.78f),
            onPrimary = tone(primary, 0.70f, 0.15f),
            primaryContainer = tone(primary, 0.55f, 0.28f),
            onPrimaryContainer = tone(primary, 0.70f, 0.90f),
            secondary = tone(secondary, 0.40f, 0.78f),
            onSecondary = tone(secondary, 0.40f, 0.15f),
            secondaryContainer = tone(secondary, 0.30f, 0.28f),
            onSecondaryContainer = tone(secondary, 0.40f, 0.90f),
            tertiary = tone(tertiary, 0.55f, 0.80f),
            onTertiary = tone(tertiary, 0.50f, 0.15f),
            tertiaryContainer = tone(tertiary, 0.35f, 0.28f),
            onTertiaryContainer = tone(tertiary, 0.50f, 0.90f),
            background = tone(primary, 0.15f, 0.08f),
            onBackground = tone(primary, 0.10f, 0.90f),
            surface = tone(primary, 0.15f, 0.08f),
            onSurface = tone(primary, 0.10f, 0.90f),
            surfaceVariant = tone(primary, 0.12f, 0.25f),
            onSurfaceVariant = tone(primary, 0.12f, 0.78f),
            outline = tone(primary, 0.08f, 0.60f),
            outlineVariant = tone(primary, 0.10f, 0.30f),
            surfaceContainerLowest = tone(primary, 0.15f, 0.06f),
            surfaceContainerLow = tone(primary, 0.15f, 0.09f),
            surfaceContainer = tone(primary, 0.15f, 0.11f),
            surfaceContainerHigh = tone(primary, 0.15f, 0.14f),
            surfaceContainerHighest = tone(primary, 0.15f, 0.17f),
        )
    } else {
        lightColorScheme(
            primary = accent(primary, 0.60f, 0.45f),
            onPrimary = Color.White,
            primaryContainer = tone(primary, 0.70f, 0.90f),
            onPrimaryContainer = tone(primary, 0.80f, 0.10f),
            secondary = accent(secondary, 0.30f, 0.45f),
            onSecondary = Color.White,
            secondaryContainer = tone(secondary, 0.35f, 0.90f),
            onSecondaryContainer = tone(secondary, 0.45f, 0.12f),
            tertiary = accent(tertiary, 0.40f, 0.45f),
            onTertiary = Color.White,
            tertiaryContainer = tone(tertiary, 0.45f, 0.90f),
            onTertiaryContainer = tone(tertiary, 0.55f, 0.12f),
            background = tone(primary, 0.15f, 0.98f),
            onBackground = tone(primary, 0.15f, 0.10f),
            surface = tone(primary, 0.15f, 0.98f),
            onSurface = tone(primary, 0.15f, 0.10f),
            surfaceVariant = tone(primary, 0.20f, 0.90f),
            onSurfaceVariant = tone(primary, 0.15f, 0.30f),
            outline = tone(primary, 0.10f, 0.50f),
            outlineVariant = tone(primary, 0.15f, 0.80f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tone(primary, 0.15f, 0.96f),
            surfaceContainer = tone(primary, 0.15f, 0.94f),
            surfaceContainerHigh = tone(primary, 0.15f, 0.92f),
            surfaceContainerHighest = tone(primary, 0.15f, 0.90f),
        )
    }
}

private const val MIN_CONTRAST = 4.6

/** WCAG contrast ratio between two colours (1 = identical, 21 = black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (light + 0.05) / (dark + 0.05)
}
