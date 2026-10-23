package dev.r2d8.weather.ui.theme

import dev.r2d8.weather.domain.ColorPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private val fixedPalettes = ColorPalette.entries.filter { it.swatch != null }

    @Test
    fun `text on key surfaces is readable in every fixed palette, light and dark`() {
        val failures = mutableListOf<String>()
        fixedPalettes.forEach { palette ->
            listOf(false, true).forEach { dark ->
                val scheme = fixedColorScheme(palette.hue, dark)
                val pairs = mapOf(
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                    "onSurfaceVariant/surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "primary/surfaceContainer (icons, accents)" to (scheme.primary to scheme.surfaceContainer),
                    "tertiary/surfaceContainer (rain chance text)" to (scheme.tertiary to scheme.surfaceContainer),
                )
                pairs.forEach { (name, colours) ->
                    val ratio = contrastRatio(colours.first, colours.second)
                    if (ratio < 4.5) failures += "${palette.label} dark=$dark $name = ${"%.2f".format(ratio)}"
                }
            }
        }
        assertTrue("Contrast below 4.5:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
