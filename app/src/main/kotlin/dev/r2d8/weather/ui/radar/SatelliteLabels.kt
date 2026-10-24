package dev.r2d8.weather.ui.radar

internal enum class LayerKind { Symbol, Line, Other }

/**
 * The satellite view is the detailed OpenFreeMap style with its land, water and shading hidden and
 * the satellite picture put underneath. This decides which of that style's layers stay on top:
 * all labels (cities, villages, roads, borders, water names) and the roads and borders themselves,
 * but not small points of interest, one-way arrows, railways, waterways or any filled area.
 */
internal fun keepOnSatellite(id: String, kind: LayerKind): Boolean = when (kind) {
    LayerKind.Symbol -> !id.startsWith("poi_") && !id.startsWith("road_one_way")
    LayerKind.Line ->
        (id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("boundary_")) && "rail" !in id
    LayerKind.Other -> false
}
