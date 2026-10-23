# Weather App – Plan

Ad-free Android weather app for personal use, with a rain/snow radar served by a backend on the home server.

**Status (2026-10-23):** Phases 0 and 1 are done. The app builds with `./gradlew` and runs on the Galaxy A32 (hello-world, dark mode, dynamic colours). Next: **Phase 2 (core weather)**. Server notes are in [server.md](server.md).

**Starting a new session:** `CLAUDE.md` is loaded automatically and points here. Read this plan and `server.md`, then continue with the current phase.

## Decisions

| Topic | Decision |
|---|---|
| Distribution | Personal use only (sideloaded APK). Free API tiers are acceptable. Revisit if this ever goes public. |
| Stack | Kotlin + Jetpack Compose (native Android), Material 3 |
| Min / target Android | Min **API 33 (Android 13)**, which is the test phone. Target the latest stable API (compileSdk/targetSdk 37). |
| App id / structure | `dev.r2d8.weather`, single module `:app`, Kotlin sources in `app/src/main/kotlin`. |
| Architecture / DI | MVVM (ViewModel + StateFlow), coroutines, **manual DI** (an `AppContainer` in the Application class, no Hilt). Libraries are added in the phase that needs them: Retrofit/OkHttp + kotlinx.serialization, Room, DataStore, Navigation Compose, MapLibre. No Play Services, Firebase or analytics. |
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

## Phase 2 – Core weather

- Open-Meteo client (forecast + air quality + geocoding), models and mappers, WMO weather codes to icons and text.
- Main screen: current conditions, 15-minute rain-in-next-hour summary, hourly and daily forecast, detail cards (wind and gusts, UV, sunrise/sunset, humidity, pressure, air quality, pollen).
- Caching in Room, last-known data when offline, loading and error states, pull to refresh, attribution screen (Open-Meteo CC BY 4.0).
- Done when: correct data for the current location matches the Open-Meteo website, and works offline with cached data.

## Phase 3 – Locations, settings, look

- GPS location via the platform `LocationManager` (no Play Services), saved places, city search (Open-Meteo geocoding), reorder and delete places.
- Settings screen: switch each detail card on/off, light/dark/system mode, colour themes (Material 3 dynamic colour on Android 12+ plus a few fixed palettes). Units stay metric but are stored as settings so they can be added later.
- Done when: all toggles persist and change the main screen immediately.

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
