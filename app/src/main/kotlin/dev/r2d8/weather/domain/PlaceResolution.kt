package dev.r2d8.weather.domain

/**
 * The place the app is currently about: the selected saved place, or the phone's last known
 * location. A selection whose saved place was deleted falls back to the phone's location.
 * Null while neither is known (the phone's location is still being found).
 */
fun resolvePlace(settings: AppSettings, saved: List<SavedPlace>): Place? =
    (settings.selection as? PlaceSelection.Saved)
        ?.let { selection -> saved.firstOrNull { it.id == selection.id }?.place }
        ?: settings.lastGpsPlace
