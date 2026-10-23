# Weather App – Plan

Ad-free Android weather app for personal use, with a rain/snow radar served by a backend on the home server.

**Status (2026-10-24):** Phases 0 to 3 are done, including the forecast redesign (Phase 2b), checked on the phone. Next: **Phase 4** (radar backend on the home server). Server notes are in [server.md](server.md).

**Starting a new session:** `CLAUDE.md` is loaded automatically and points here. Read this plan and `server.md`, then continue with the current phase.

## Decisions

| Topic | Decision |
|---|---|
| Distribution | Personal use only (sideloaded APK). Free API tiers are acceptable. Revisit if this ever goes public. |
| Stack | Kotlin + Jetpack Compose (native Android), Material 3 |
| Min / target Android | Min **API 33 (Android 13)**, which is the test phone. Target the latest stable API (compileSdk/targetSdk 37). |
| App id / structure | `dev.r2d8.weather`, single module `:app`, Kotlin sources in `app/src/main/kotlin`. |
| Architecture / DI | MVVM (ViewModel + StateFlow), coroutines, **manual DI** (an `AppContainer` in the Application class, no Hilt). Libraries are added in the phase that needs them: Retrofit/OkHttp + kotlinx.serialization, Room, DataStore, Navigation Compose, MapLibre. No Play Services, Firebase or analytics. |
| Forecast model | Open-Meteo default **best match** (automatic blend of regional models, high resolution over Italy). No model picker for now. |
| Icons | Google Material Symbols as vector drawables in `res/drawable` (`ic_*.xml`, Apache 2.0, credited in the About screen), tinted by Compose. |
| Places | The main screen shows either the phone's location (default) or one saved place, chosen in a drop-down in the top bar. Saved places are managed on a Places screen. `Place.Rome` is only used by tests. |
| Settings | Stored in a Preferences DataStore. Covers theme mode, colour palette, which main-screen sections and which detail cards are shown. Unit settings were left out (metric only). |
| Libraries in use | Retrofit 3 + OkHttp 5 + kotlinx.serialization, Room 2.8 (via KSP), DataStore Preferences 1.3, Navigation Compose, lifecycle-viewmodel-compose, coroutines. MapLibre comes in Phase 5. |
| Build versions (2026-10-23) | Gradle 9.8.2, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00, all in `gradle/libs.versions.toml`. |
| Test device | Samsung Galaxy A32, Android 13, USB debugging |
| Region | Italy / Europe first |
| Language / units | English only; metric only (°C, km/h, mm, hPa) |
| Locations | GPS location plus saved and searchable cities |
| Priorities | 1) correct forecast, 2) rain and snow radar. No satellite cloud imagery. |
| Weather details | Rain-in-next-hour, wind and gusts, UV, sunrise/sunset, humidity, pressure, air quality and pollen. **Every detail can be switched on/off in settings.** |
| Look | Light/dark/system mode and colour themes. |
| Map | MapLibre. Normal map: **OpenFreeMap** (vector, no key, styles liberty/bright/positron/dark). Satellite: **EOX Sentinel-2 cloudless** raster tiles. The user can switch between normal and satellite view. |
| Not planned for now | Home-screen widget, rain/snow notifications (can be added later). |
| Radar | Home-server backend (always on, reachable over Tailscale, also away from home) serves Italian DPC radar tiles with history; RainViewer as optional source, mainly outside Italy. |
| Backend | Written **from scratch** in a new project (Python proposed, see Phase 4). The proof-of-concept was deleted on 2026-10-23, only its findings below remain. |
| Dev OS | Fedora, 8 cores, 14 GB RAM, ~300 GB free, KVM available |
| Server | Ubuntu Server 24.04 LTS on an old laptop, Docker Compose stacks, Tailscale. Details in [server.md](server.md). |

## Feasibility (short)

- **Forecast data: easy.** Open-Meteo is free for non-commercial use, no key, JSON API with good European models, a geocoding API for city search and an air-quality API (pollen in Europe). Limits: 10,000 calls/day, no uptime guarantee, CC BY 4.0 attribution.
- **Radar map: feasible.** The map side is easy (MapLibre, no Google key). The data source decides the quality: DPC (Italy) raw data is good and has history, RainViewer's free tier is limited (zoom 7, ~2 h history).
- **Overall:** realistic as a solo project. Phases 1–3 are low risk, Phases 4–5 medium.

## Phase 0 – Requirements (done)

### Install on the dev PC (do at the start of Phase 1)

1. **Android Studio** (stable): no official RPM, use the Linux tarball from developer.android.com/studio, extract to `~/opt/android-studio`, run `bin/studio.sh`. It bundles its own JDK (JBR, version 25 in 2026.2.1), which Gradle uses. *(Done: Studio 2026.2.1.8.)*
2. **Android SDK** via Studio's SDK Manager: latest stable platform, Build-Tools, Platform-Tools (adb), Command-line Tools. Emulator optional (KVM works) but the phone is the main target. *(Done: platform 37, build-tools 36.0.0, platform-tools 37.0.1; an emulator and a system image were also installed by the wizard.)*
3. Not needed: no system JDK 21 or `android-tools` package. The system default Java 27-ea must not be used for Android builds: `org.gradle.java.home` in `~/.gradle/gradle.properties` points Gradle to Studio's JBR.
4. Env vars in `~/.bashrc`: `ANDROID_HOME=$HOME/Android/Sdk`, add `platform-tools` and `cmdline-tools/latest/bin` to `PATH`. *(Done.)*
5. Gradle and Kotlin need no separate install (Gradle wrapper, Kotlin Gradle plugin). git is installed.
6. Phone: enable Developer options (tap Build number 7 times) and USB debugging, check with `adb devices`.

No accounts or API keys are needed (Open-Meteo, DPC, RainViewer free tier). Disk need is ~15–25 GB.

### Radar findings (from the proof of concept, now deleted)

- **RainViewer free API:** 13 past frames (~2 h, 10-min steps), no nowcast, pan-European, optional separate snow colours, tiles only up to zoom 7 (the app must overzoom), roughly 100 requests/IP/min stated (500/min seen in headers), personal use, credit required.
- **DPC official tile service (WMS) is unreliable:** only the latest frame, ignores `time`, and returned empty tiles while the raw data had rain. Do not use it.
- **DPC raw data is the better Italian source.** REST API `https://radar-api.protezionecivile.it` (no key, any `Origin` accepted): `GET /findLastProductByType?type=SRI` and `POST /downloadProduct {productType, productDate(ms)}` returning a pre-signed GeoTIFF URL. Products: SRI (rain rate mm/h), VMI (reflectivity dBZ), others. 1 km grid, every 5 min, 1200x1400 px, Transverse Mercator (origin 42°N 12.5°E, top-left -600000/650000 m, WGS84), float32, -9999 = no data, about 0.5 MB per frame. Past frames can be requested (6 h back worked). A WebSocket (`wss://radar-wss.protezionecivile.it`, topic `/topic/product`) announces new frames. CC-BY-SA 4.0, credit Radar-DPC. Coverage: Italy and surroundings.
- **Rendering worked in the PoC:** reprojecting to Web Mercator tiles with bilinear interpolation took about 25 ms per tile (numpy + pyproj + Pillow). Compared with RainViewer it showed the same storms, smoother and with stronger cores.
- **Snow:** DPC has no rain/snow distinction (RainViewer does). Idea for later: classify by model temperature near or below 0 °C.
- **Discarded:** EUMETSAT satellite clouds (works, not needed), Open-Meteo grid sampling or raw model files (modelled, not observed rain), LibreWXR (self-hosted OPERA+DPC aggregator, AGPL, untested, possible alternative for pan-European radar).

### Radar architecture

- The **backend** runs in Docker on the home server, ingests DPC frames, stores history, and serves tiles plus a frame list. The app shows them over MapLibre. Outside Italy (or if the server is unreachable) the app can use RainViewer.
- **Ingest:** use the DPC WebSocket to learn about new frames, with a fallback check of `findLastProductByType` every 1–2 min that downloads only when a new frame exists. No blind polling of the download endpoint.
- **History:** hours of frames kept is a backend setting, chosen later (PoC used 3 h).
- **Colours:** user-selectable, including for DPC. The backend renders with a named palette or custom colour stops passed by the app, and caches tiles per palette. Rain/snow colour split later.
- **Access:** only over Tailscale (publish the port on the Tailscale IP, never on all interfaces). Android allows one VPN at a time, so Tailscale must stay on on the phone. Fallbacks if that is a problem: Tailscale Funnel or a Cloudflare Tunnel with a token.

## Phase 1 – Project setup (done 2026-10-23)

- Install the tools (list above), create the project (Kotlin, Compose, Material 3, min API 33), Gradle wrapper, `git init`, `.gitignore`. *(Done. The project was written by hand from the terminal instead of Studio's wizard, so it contains only what we need.)*
- Run a hello-world on the Galaxy A32. *(Done. If `adb devices` shows `unauthorized`: revoke USB debugging authorizations on the phone, toggle USB debugging and replug with the screen unlocked.)*
- Decisions (confirmed 2026-10-23, see the Decisions table): application id `dev.r2d8.weather`, single module, MVVM + StateFlow, manual DI, and the library list above, added per phase.
- Done when: app installs and starts on the phone, builds from the command line with `./gradlew`, CLAUDE.md "Commands" section filled in. *(All met: debug install on the phone, `assembleDebug`, `assembleRelease`, `lintDebug` and `testDebugUnitTest` verified.)*

## Phase 2 – Core weather (done 2026-10-24)

- Open-Meteo client (forecast + air quality + geocoding), models and mappers, WMO weather codes to icons and text. *(Done; the geocoding client is used by the Phase 3 city search.)*
- Main screen: current conditions, 15-minute rain-in-next-hour summary, hourly and daily forecast, detail cards (wind and gusts, UV, sunrise/sunset, humidity, pressure, air quality, pollen). *(Done, functional Material 3 look; polish and themes are Phase 3.)*
- Caching in Room, last-known data when offline, loading and error states, pull to refresh, attribution screen (Open-Meteo CC BY 4.0). *(Done.)*
- Done when: correct data for the current location matches the Open-Meteo website, and works offline with cached data.

### How it works (for later phases)

- **Code layout** (`dev.r2d8.weather`): `data/remote` (Retrofit interfaces and DTOs), `data/local` (Room), `data/WeatherRepository` + `Mappers`, `domain` (app models, rain nowcast, category bands), `ui/weather` (screen, cards, ViewModel), `ui/about`, `AppContainer` (manual DI).
- **Requests:** `timeformat=unixtime` and `timezone=auto`, so times are epoch seconds and the response names the location's zone. Forecast: current, 15-minute precipitation (16 slots), hourly and 10 daily values. Air quality (EAQI, PM, pollen) is a separate request; if it fails, the forecast still loads and the cached air quality is kept.
- **Cache:** one Room row per place (coordinates rounded to 2 decimals) holding the raw JSON of both responses plus the download time. The UI only observes the cache; refresh writes to it. The app refreshes on start and on resume if the cache is older than 10 minutes, and always on pull-to-refresh. Unreadable cached JSON is treated as empty.
- **Rain in the next hour:** Open-Meteo precipitation values describe the **preceding** 15 minutes, so a slot at time T covers (T-15 min, T]. A slot counts as rain from 0.1 mm (about 0.4 mm/h); intensity bands are light under 2.5 mm/h, moderate under 7.6, heavy above.
- **Bands:** UV by WHO, air quality by the European AQI, pollen with simple generic bands (real thresholds differ per species, so the number is shown too). Pollen is hidden where the API returns no pollen data.
- **Detail cards** are listed in the `DetailCardType` enum (in `domain/AppSettings.kt`); the main screen shows the ones enabled in settings.
- **Tests:** domain logic, mappers (real sample responses in `app/src/test/resources`) and the repository (mock HTTP server plus an in-memory fake DAO).
- **Time handling gotchas:**
  - Open-Meteo's `unixtime` **daily** timestamps step by exactly 24 h and ignore DST, so after a clock change they sit at 23:00 of the previous day. The mapper takes the first day's local date and counts up (found on the first device run: "Sun 25" appeared twice around the 25 Oct 2026 change; there is a regression test). Hourly and 15-minute timestamps are real instants and are fine.
  - Times for a place (hours, sunrise) are shown in the **place's** zone; "Updated" and the offline banner use the **phone's** zone. The dev phone is set to Europe/Helsinki while the test place is Rome, which is how this showed up.
- **Checked on the phone (2026-10-24):** loads and caches, refresh on start/resume, pull to refresh, offline banner with cached data (airplane mode). Values come straight from the API and were not compared side by side with the Open-Meteo website.

## Phase 2b – Forecast redesign (done 2026-10-24)

Requested after Phase 2: hourly detail like 3bmeteo, with each of the 10 days expandable into hours. 3bmeteo's web page was used as inspiration only (no assets copied): day-part cards (night/morning/afternoon/evening) and an hourly table. No screenshots of their app were available.

- Top 24-hour strip stays as it was.
- **10-day list:** each row shows day, max rain chance (hidden under 10%), icon, min and max. Tap a row to expand it inline (one open at a time, state survives rotation). Today shows only the hours still to come.
- **Expanded day:** four day-part cells (condition icon and text, min/max, rain total) and an hourly table with icon, temperature, rain in mm and rain chance. A day part shows its **most severe** condition (`ConditionKind.severity`) so a storm at 17:00 is not hidden by a sunny morning.
- **Not included for now (chosen by the user):** wind, gusts, humidity, pressure, UV, snow line and freezing level per hour. Open-Meteo provides them if wanted later; the hourly data for all 10 days is already downloaded.
- Code: `ui/weather/DailyForecast.kt`, `domain/DayParts.kt` (with tests).

## Phase 3 – Locations, settings, look (done 2026-10-24)

- GPS location via the platform `LocationManager` (no Play Services), saved places, city search (Open-Meteo geocoding), reorder and delete places. *(Done.)*
- Settings screen: each detail card and each main-screen section on/off, light/dark/system mode, colour palette (dynamic Material You plus Blue, Green, Orange, Purple). *(Done. Units were left out on purpose.)*
- Done when: all toggles persist and change the main screen immediately. *(Checked on the phone: palette, mode and a section toggle applied at once.)*

### How it works (for later phases)

- **Choosing the place:** `PlaceSelection` is `Gps` or `Saved(id)`, stored in settings. If a selected saved place is deleted the selection falls back to the phone's location. Adding a place from search saves it, selects it and returns to the main screen.
- **Phone location:** `LocationProvider` asks for approximate or precise permission when "Current location" is shown. Network location is tried first (approximate is enough), then GPS, then the last known fix; the place name comes from the system `Geocoder` with "Current location" as fallback. The result is stored as `lastGpsPlace` so there is something to show while locating, and the lookup is repeated on resume after 5 minutes. States without a place: permission prompt (with "Choose a place instead") and a "couldn't find your location" screen.
- **Privacy:** requests to Open-Meteo use coordinates rounded to 2 decimals (about 1 km), never the exact fix. Search queries go to Open-Meteo's geocoding service.
- **Storage:** `saved_places` table in Room (database version 2, auto-migration from 1 keeps the weather cache); settings in a Preferences DataStore. Switches are stored as the set of things switched **off**, so new cards or sections appear by default.
- **Theme:** the fixed palettes are generated from a hue (`ui/theme/Theme.kt`); light-theme accents darken themselves until they reach 4.6:1 contrast, and a unit test checks key pairs for all palettes. `MainActivity` waits for the stored theme before drawing so there is no flash of wrong colours, and sets the status/navigation bar icon style to match the app theme rather than the system's.
- **Navigation:** routes `weather`, `places`, `settings`, `about`; About is reached from Settings.
- **Search results:** rows with the same name and region are collapsed, and Italian matches are listed first (country code `IT`, the service's own order is kept within each group; 20 results are requested so Italian ones are not cut off). To change the preferred countries edit `PREFERRED_COUNTRIES` in `PlacesRepository`.
- **Rain icon rule:** the WMO weather code can say "overcast" while the rain probability is high. For cloudy, partly cloudy and foggy hours, day parts and days with a rain probability of at least 50% (`RAIN_LIKELY_PERCENT` in `domain/WeatherCondition.kt`) the light-rain icon and the text "Chance of rain" are shown. Clear skies and real precipitation codes are never changed. The current-conditions card follows the observed code only.
- **Time zones:** "Today" and "Tomorrow" follow the place's own calendar and the rows show the date underneath. When the place's UTC offset differs from the phone's, the current-conditions card adds "Local time HH:mm · weekday date". (The dev phone is on Helsinki time, so Italian places are still on the previous day for one hour after midnight.)
- **App icon:** adaptive icon (sun behind a cloud on a blue gradient, plus a monochrome layer for Android 13 themed icons) in `res/drawable/ic_launcher_*.xml` and `res/mipmap-anydpi/ic_launcher.xml`.
- **Known gaps:** the Places screen reorders with up/down buttons, not drag and drop.
- **Dev notes:** the debug build is slow (a few frames per second on the A32 at times), which is normal for debug; try `assembleRelease` if performance matters. Lint passes without errors; remaining warnings are the missing icon, `allowBackup`, one long icon path and a state-type hint.

## Phase 4 – Radar backend (new project on the home server)

- New repository/folder for the backend (not the PoC). Language: Python proposed (mature geo and raster libraries: numpy, pyproj, Pillow/rasterio), typed, with tests that use a stored sample GeoTIFF. Language to be confirmed at the start of this phase.
- Components: DPC ingest (WebSocket + fallback check, retries, gaps), frame store with pruning by `HISTORY_HOURS`, tile renderer (palette parameter, tile cache), HTTP API (`/frames`, `/tiles/...`, `/health`, attribution), Docker image, Compose stack in `/srv/stacks/weather-radar`, unprivileged container user, memory limit, port published only on the Tailscale IP, log rotation (already configured).
- Open: rain/snow split, how much history, whether to also fetch RainViewer through the backend.
- Radar findings above are the starting knowledge; nothing from the PoC code is reused.
- Done when: the stack runs on the server after a reboot, frames keep updating for 24 h without errors, a tile renders in well under 100 ms, and the API is reachable from the PC and the phone over Tailscale.

## Phase 5 – Radar in the app

- MapLibre map with a **normal / satellite switch**:
  - Normal: OpenFreeMap vector tiles (`https://tiles.openfreemap.org/styles/liberty`, also `bright`, `positron`, `dark`; the dark style follows the app's dark mode). No key, no stated limits, donation-funded, no SLA, attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" (MapLibre shows it automatically).
  - Satellite: EOX Sentinel-2 cloudless 2024 raster tiles (`https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg`, tested up to zoom 16). 10 m/px, so blurry beyond about zoom 14. Free for personal non-commercial use (CC BY-NC-SA 4.0), attribution required: "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)". Becomes a commercial licence if the app is ever monetised or distributed.
  - Not chosen: Esri World Imagery (sharper, but a key and unclear third-party terms), OSM's own tile servers (not for app use), Mapbox/MapTiler (key and limits).
- Radar overlay from the backend with animated past frames, play/pause, timeline slider, opacity, legend, colour palette setting, RainViewer option (zoom limit 7, overzoom), automatic fallback to RainViewer when the home server is unreachable, attribution (Radar-DPC, RainViewer, map data).
- Done when: radar animates smoothly on the phone at home and away from home over Tailscale.

## Phase 6 – Later / stretch

Rain vs snow colours for DPC, notifications, home-screen widget, "rain starts in X minutes" from radar nowcast, severe-weather alerts. Server: Pi-hole, password manager, git server (see [server.md](server.md)).

## Server work remaining (not tied to a phase)

Reboot when someone is at home and optional firewall/SSH hardening. See [server.md](server.md).
