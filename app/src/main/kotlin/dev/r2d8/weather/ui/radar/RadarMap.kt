package dev.r2d8.weather.ui.radar

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.r2d8.weather.domain.Basemap
import dev.r2d8.weather.domain.MapStyle
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.RadarFrame
import dev.r2d8.weather.domain.RadarLayerSet
import dev.r2d8.weather.domain.TemperatureHour
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val RADAR_LAYER = "radar-layer-"
private const val RADAR_SOURCE = "radar-src-"
private const val PLACE_SOURCE = "place-src"
private const val PLACE_LAYER = "place-layer"
private const val TEMPERATURE_LAYER = "temperature-layer-"
private const val TEMPERATURE_SOURCE = "temperature-src-"
private const val TAPPED_SOURCE = "tapped-src"
private const val TAPPED_LAYER = "tapped-layer"

/** One hour of the temperature map to draw instead of the radar. */
data class TemperatureOverlay(val hour: TemperatureHour, val maxZoom: Int, val tileSize: Int, val attribution: String)

private const val DEFAULT_ZOOM = 6.5
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 14.0

/**
 * The satellite view is the Detailed vector style (for its names, roads and borders) with the EOX
 * Sentinel-2 cloudless picture underneath, see [keepOnSatellite]. The imagery is 10 m per pixel, so
 * beyond about zoom 14 it only gets blurrier; the source stops there and the map stretches it.
 * Free for non-commercial use.
 */
private const val SATELLITE_URL = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"
private const val SATELLITE_ATTRIBUTION =
    "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)"
private const val SATELLITE_SOURCE = "satellite-src"
private const val SATELLITE_LAYER = "satellite-layer"
private const val SATELLITE_MAX_ZOOM = 14f

/**
 * A MapLibre map with the radar on top. MapLibre's MapView is a classic Android View, so it is
 * hosted in an AndroidView and must be told about the lifecycle (start, stop, ...) by hand.
 *
 * All frames in [frames] get a raster layer. Only the one at [index] is drawn; the others are kept at
 * zero opacity so their tiles are fetched and cached, which makes playing the animation smooth.
 */
@Composable
fun RadarMap(
    place: Place,
    basemap: Basemap,
    mapStyle: MapStyle,
    /** The radar, or null while only the temperature map is shown. */
    layers: RadarLayerSet?,
    frames: List<RadarFrame>,
    index: Int,
    opacity: Float,
    /** Height in pixels of the controls drawn over the bottom of the map; the map's credits stay above it. */
    bottomInsetPx: Int,
    onIdle: () -> Unit,
    /** A temperature hour to draw (pass no [frames] at the same time). */
    temperature: TemperatureOverlay? = null,
    /** A point marked on the map (the tapped temperature point). */
    marker: Pair<Double, Double>? = null,
    onTap: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    /** The address to load a map style from: the pinned copy when there is one (see MapDataRepository). */
    styleUrlOf: suspend (MapStyle) -> String,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // The loaded style; null while a new one is loading. Layers can only be added to a loaded style.
    var style by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setMinZoomPreference(MIN_ZOOM)
            m.setMaxZoomPreference(MAX_ZOOM)
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            map = m
        }
        val idle = MapView.OnDidBecomeIdleListener { onIdle() }
        mapView.addOnDidBecomeIdleListener(idle)
        onDispose { mapView.removeOnDidBecomeIdleListener(idle) }
    }

    // The latest callback, so the listener added once always calls the current one.
    val currentOnTap by rememberUpdatedState(onTap)
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { point ->
            currentOnTap(point.latitude, point.longitude)
            false
        }
        m.addOnMapClickListener(listener)
        onDispose { m.removeOnMapClickListener(listener) }
    }

    // A new hour is added on top of the previous one, which is removed once the map has loaded the
    // new tiles: the map never goes blank between hours.
    LaunchedEffect(style, temperature) {
        val s = style ?: return@LaunchedEffect
        installTemperature(s, temperature, opacity)
        if (temperature == null) return@LaunchedEffect
        val current = "$TEMPERATURE_LAYER${temperature.hour.id}"
        val m = map ?: return@LaunchedEffect
        suspendCancellableCoroutine { continuation ->
            val idle = object : MapView.OnDidBecomeIdleListener {
                override fun onDidBecomeIdle() {
                    mapView.removeOnDidBecomeIdleListener(this)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            mapView.addOnDidBecomeIdleListener(idle)
            continuation.invokeOnCancellation { mapView.removeOnDidBecomeIdleListener(idle) }
        }
        if (m.style === s) removeTemperatureLayers(s, except = current)
    }

    LaunchedEffect(style, marker) {
        val s = style ?: return@LaunchedEffect
        installTappedMarker(s, marker)
    }

    LaunchedEffect(map, basemap, mapStyle) {
        val m = map ?: return@LaunchedEffect
        style = null
        // The satellite view starts from the Detailed style and turns it into labels over imagery.
        val url = styleUrlOf(if (basemap == Basemap.Satellite) MapStyle.Detailed else mapStyle)
        m.setStyle(Style.Builder().fromUri(url)) { loaded ->
            if (basemap == Basemap.Satellite) makeSatelliteHybrid(loaded)
            style = loaded
        }
    }

    LaunchedEffect(style, layers, frames) {
        val s = style ?: return@LaunchedEffect
        installRadar(s, layers, frames)
    }

    LaunchedEffect(style, place) {
        val s = style ?: return@LaunchedEffect
        installPlaceMarker(s, place)
    }

    LaunchedEffect(style, frames, index, opacity) {
        val s = style ?: return@LaunchedEffect
        frames.indices.forEach { i ->
            s.getLayerAs<RasterLayer>("$RADAR_LAYER$i")
                ?.setProperties(PropertyFactory.rasterOpacity(if (i == index) opacity else 0f))
        }
        s.layers.filter { it.id.startsWith(TEMPERATURE_LAYER) }
            .forEach { it.setProperties(PropertyFactory.rasterOpacity(opacity)) }
    }

    // Keep MapLibre's logo and the (i) credits button visible above the controls card.
    LaunchedEffect(map, bottomInsetPx) {
        val m = map ?: return@LaunchedEffect
        val margin = 16
        m.uiSettings.setLogoMargins(margin, 0, 0, bottomInsetPx + margin)
        m.uiSettings.setAttributionMargins(margin + 240, 0, 0, bottomInsetPx + margin)
        m.uiSettings.setCompassMargins(0, 0, margin, bottomInsetPx + margin)
    }

    // Centre on the place at first and whenever the place changes.
    LaunchedEffect(map, place) {
        val m = map ?: return@LaunchedEffect
        val target = LatLng(place.latitude, place.longitude)
        if (m.cameraPosition.target?.let { it.latitude == 0.0 && it.longitude == 0.0 } != false) {
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(DEFAULT_ZOOM).build()
        } else {
            m.moveCamera(CameraUpdateFactory.newLatLng(target))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Hides the style's land, water and shading, keeps its labels, roads and borders, and adds the satellite picture below. */
private fun makeSatelliteHybrid(style: Style) {
    style.layers.forEach { layer ->
        val kind = when (layer) {
            is SymbolLayer -> LayerKind.Symbol
            is LineLayer -> LayerKind.Line
            else -> LayerKind.Other
        }
        if (!keepOnSatellite(layer.id, kind)) layer.setProperties(PropertyFactory.visibility(Property.NONE))
    }
    val tiles = TileSet("2.2.0", SATELLITE_URL).apply {
        maxZoom = SATELLITE_MAX_ZOOM
        attribution = SATELLITE_ATTRIBUTION
    }
    style.addSource(RasterSource(SATELLITE_SOURCE, tiles, 256))
    style.addLayerAt(RasterLayer(SATELLITE_LAYER, SATELLITE_SOURCE), 0)
}

/** Replaces any previous radar layers: one source and one raster layer per frame, under the labels. */
private fun installRadar(style: Style, layers: RadarLayerSet?, frames: List<RadarFrame>) {
    style.layers.filter { it.id.startsWith(RADAR_LAYER) }.forEach { style.removeLayer(it.id) }
    style.sources.filter { it.id.startsWith(RADAR_SOURCE) }.forEach { style.removeSource(it.id) }
    if (layers == null) return
    // On the vector map, put the radar below the first label layer so place names stay readable.
    val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
    frames.forEachIndexed { i, frame ->
        val tiles = TileSet("2.2.0", frame.tileUrl).apply {
            maxZoom = (frame.maxZoom ?: layers.maxZoom).toFloat()
            attribution = frame.attribution ?: layers.attribution
        }
        style.addSource(RasterSource("$RADAR_SOURCE$i", tiles, layers.tileSize))
        val layer = RasterLayer("$RADAR_LAYER$i", "$RADAR_SOURCE$i").withProperties(
            PropertyFactory.rasterOpacity(0f),
            PropertyFactory.rasterFadeDuration(0f), // no cross-fade, so animation steps are crisp
        )
        if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)
    }
}

/**
 * Adds the layer for [overlay]'s hour (if not there yet) under the labels; older hours stay until
 * the caller removes them. Null removes all temperature layers.
 */
private fun installTemperature(style: Style, overlay: TemperatureOverlay?, opacity: Float) {
    if (overlay == null) {
        removeTemperatureLayers(style, except = null)
        return
    }
    val id = overlay.hour.id
    if (style.getLayer("$TEMPERATURE_LAYER$id") != null) return
    val tiles = TileSet("2.2.0", overlay.hour.tileUrl).apply {
        maxZoom = overlay.maxZoom.toFloat()
        attribution = overlay.attribution
    }
    style.addSource(RasterSource("$TEMPERATURE_SOURCE$id", tiles, overlay.tileSize))
    val layer = RasterLayer("$TEMPERATURE_LAYER$id", "$TEMPERATURE_SOURCE$id").withProperties(
        PropertyFactory.rasterOpacity(opacity),
        PropertyFactory.rasterFadeDuration(0f),
    )
    val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (firstLabel != null) style.addLayerBelow(layer, firstLabel) else style.addLayer(layer)
}

private fun removeTemperatureLayers(style: Style, except: String?) {
    style.layers.filter { it.id.startsWith(TEMPERATURE_LAYER) && it.id != except }.forEach { style.removeLayer(it.id) }
    val keepSource = except?.replace(TEMPERATURE_LAYER, TEMPERATURE_SOURCE)
    style.sources.filter { it.id.startsWith(TEMPERATURE_SOURCE) && it.id != keepSource }.forEach { style.removeSource(it.id) }
}

/** A small ring where the user tapped on the temperature map; null removes it. */
private fun installTappedMarker(style: Style, marker: Pair<Double, Double>?) {
    val existing = style.getSourceAs<GeoJsonSource>(TAPPED_SOURCE)
    if (marker == null) {
        style.removeLayer(TAPPED_LAYER)
        if (existing != null) style.removeSource(existing)
        return
    }
    val feature = Feature.fromGeometry(Point.fromLngLat(marker.second, marker.first))
    if (existing != null) {
        existing.setGeoJson(feature)
        return
    }
    style.addSource(GeoJsonSource(TAPPED_SOURCE, feature))
    style.addLayer(
        CircleLayer(TAPPED_LAYER, TAPPED_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(Color.TRANSPARENT),
            PropertyFactory.circleStrokeColor(Color.BLACK),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

/** A dot at the selected place, drawn above everything. */
private fun installPlaceMarker(style: Style, place: Place) {
    val feature = Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude))
    val existing = style.getSourceAs<GeoJsonSource>(PLACE_SOURCE)
    if (existing != null) {
        existing.setGeoJson(feature)
        return
    }
    style.addSource(GeoJsonSource(PLACE_SOURCE, feature))
    style.addLayer(
        CircleLayer(PLACE_LAYER, PLACE_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Color.rgb(0x1E, 0x88, 0xE5)),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                // The screen left the composition before its lifecycle ended: shut the map down now.
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
    return mapView
}
