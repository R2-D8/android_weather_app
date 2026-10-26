# Weather App – Plan

Ad-free Android weather app for personal use, with a rain/snow radar served by a backend on the home server.

**Status (2026-10-24):** Phases 0 to 3 are done, including the forecast redesign (Phase 2b), checked on the phone. **Phase 5** (map and radar in the app) is done and checked on the phone, except the mobile-data path (the phone's mobile data is off; covered by unit tests only). Open: the 24 h soak check of the radar backend. Phase 4b (radar for Finland) is dropped; Phase 5b (radar forecast and offline maps) is built in the app: offline maps and forecast frames are checked on the phone; Open-Meteo data was tested as a nowcast input and did not help (see Phase 5b). Phase 7 (Tailscale security) is done. Next: model forecast frames (DWD ICON); Phase 6 is the stretch list. Server notes are in [server.md](server.md).

**Starting a new session:** `CLAUDE.md` is loaded automatically and points here. Read this plan and `server.md`, then continue with the current phase.

## Decisions

| Topic | Decision |
|---|---|
| Distribution | Sideloaded APK for personal use and **friends** (free, non-commercial). Free API tiers are acceptable. Revisit if this ever goes public or is monetised (licences below change then). |
| Home server is optional | The connection to the home server is a Settings switch that is **off by default**, with an address field that is empty by default. A fresh install (a friend's) never contacts the server and has no address built in. Everything else (forecast, places, settings, RainViewer radar) works without it. |
| Data usage | The app must be light on mobile data: no background refresh, forecast fetched only on open/resume when older than 10 minutes, compressed responses, small immutable radar tiles that the phone caches, past radar frames loaded only when played, and a measured budget (see "Data budget" under Radar architecture). |
| Stack | Kotlin + Jetpack Compose (native Android), Material 3 |
| Min / target Android | Min **API 33 (Android 13)**, which is the test phone. Target the latest stable API (compileSdk/targetSdk 37). |
| App id / structure | `dev.r2d8.weather`, single module `:app`, Kotlin sources in `app/src/main/kotlin`. |
| Architecture / DI | MVVM (ViewModel + StateFlow), coroutines, **manual DI** (an `AppContainer` in the Application class, no Hilt). Libraries are added in the phase that needs them: Retrofit/OkHttp + kotlinx.serialization, Room, DataStore, Navigation Compose, MapLibre. No Play Services, Firebase or analytics. |
| Forecast model | Open-Meteo default **best match** (automatic blend of regional models, high resolution over Italy). No model picker for now. |
| Icons | Google Material Symbols as vector drawables in `res/drawable` (`ic_*.xml`, Apache 2.0, credited in the About screen), tinted by Compose. |
| Places | The main screen shows either the phone's location (default) or one saved place, chosen in a drop-down in the top bar. Saved places are managed on a Places screen. `Place.Rome` is only used by tests. |
| Settings | Stored in a Preferences DataStore. Covers theme mode, colour palette, which main-screen sections and which detail cards are shown, and what an expanded day shows (night/morning/afternoon/evening and hour by hour, each switchable, both on by default; with both off, days do not expand). Unit settings were left out (metric only). |
| Sharing with friends | The release APK is sent as a file and sideloaded (Android 13+, "Install unknown apps" allowed once). Signed with the app's own release key (`~/.android-keys/weather-release.jks`, passwords in the user-level `~/.gradle/gradle.properties`; both must be backed up, since updates only install over a copy signed with the same key). Only 64-bit ARM (16.7 MB). Friends get the basic app: home server off, no address. Debug builds (the dev phone) keep the debug key, so switching the dev phone to a release build needs one uninstall. |
| Libraries in use | Retrofit 3 + OkHttp 5 + kotlinx.serialization, Room 2.8 (via KSP), DataStore Preferences 1.3, Navigation Compose, lifecycle-viewmodel-compose, coroutines. MapLibre comes in Phase 5. |
| Build versions (2026-10-23) | Gradle 9.8.2, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00, all in `gradle/libs.versions.toml`. |
| Test device | Samsung Galaxy A32, Android 13, USB debugging |
| Region | Italy / Europe first |
| Language / units | English only; metric only (°C, km/h, mm, hPa) |
| Locations | GPS location plus saved and searchable cities |
| Priorities | 1) correct forecast, 2) rain and snow radar. No satellite cloud imagery. |
| Weather details | Rain-in-next-hour, wind and gusts, UV, sunrise/sunset, humidity, pressure, air quality and pollen. **Every detail can be switched on/off in settings.** |
| Look | Light/dark/system mode and colour themes. |
| Map | MapLibre. Normal map: **OpenFreeMap** vector styles, chosen in Settings > Map style: **Detailed** (liberty, the default), Bright, Light (positron), Dark. Satellite: **EOX Sentinel-2 cloudless** imagery **with the Detailed style's labels, roads and borders on top** (city and village names, regions, road names and shields, borders). The user switches between map and satellite on the radar screen; the choice is remembered. |
| Radar screen | A full-screen map opened from a radar button in the weather screen's top bar; it shows the selected place with a marker. Map: MapLibre **native SDK 13.7.1** in an `AndroidView` (the `maplibre-compose` wrapper is 0.x and its docs say the API is not stable). |
| Radar and mobile data | Radar history is **off on metered connections** (mobile data, metered Wi-Fi) unless Settings > "Load radar history on mobile data" is on (default off). Even with it on, on a metered connection history loads only when the user presses Play ("Play loads the last 2 hours (about N MB)"), and "Load once" overrides it for one visit. On Wi-Fi all frames load at once so playing is smooth. |
| Not planned for now | Home-screen widget, rain/snow notifications (can be added later). |
| Radar | **RainViewer is the built-in default** (works for everyone, zoom 7, ~2 h history, credit shown). The **home-server backend** (Italian DPC radar, sharper, longer history, reachable over Tailscale also away from home) is an optional upgrade that must be switched on in Settings. |
| Backend | **Python**, written **from scratch** in a new project folder `~/Desktop/projects/weather-radar` (own git repository). The proof-of-concept was deleted on 2026-10-23, only its findings below remain. |
| Server connection | **Plain HTTP over Tailscale** (WireGuard already encrypts it). The app allows cleartext HTTP only for the server's fixed Tailscale address `100.95.20.125` (network security config); if that IP changes the app needs a rebuild. Tailscale's own configuration is not touched. |
| Radar history | Animation shows the **last 2 hours** by default. The server keeps 6 hours so this can be extended later. |
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
- **DPC raw data is the better Italian source.** REST API `https://radar-api.protezionecivile.it` (no key, any `Origin` accepted): `GET /findLastProductByType?type=SRI` and `POST /downloadProduct {productType, productDate(ms)}` returning a pre-signed GeoTIFF URL. Products: SRI (rain rate mm/h), VMI (reflectivity dBZ), others. 1 km grid, every 5 min, 1200x1400 px, Transverse Mercator (origin 42°N 12.5°E, top-left -600000/650000 m, WGS84), float32, -9999 = no data, about 0.5 MB per frame. Past frames can be requested (6 h back worked). A WebSocket (`wss://radar-wss.protezionecivile.it`, topic `/topic/product`) announces new frames. CC-BY-SA 4.0, credit Radar-DPC. Coverage (measured on a frame from 2026-10-23, 55% of the grid has data): about lon 5.6 to 18.9 E, lat 35.3 to 47.8 N. Covered: all of Italy including Sicily, Sardinia, Corsica, Malta, Slovenia (Ljubljana) and the Nice area. Inside the grid but **no data**: Switzerland, Zagreb and the Croatian coast, Marseille, Tunis. Outside the grid: Austria, Germany, France north of the Riviera, Balkans, Finland. The mask can also shrink when a radar is offline. History is the same 6 hours for the whole mosaic, not per location; outside it the app uses RainViewer.
- **Finland (for Turku), checked 2026-10-24:** FMI (Finnish Meteorological Institute) publishes open radar data, CC BY 4.0, no key, in the public S3 bucket `fmi-opendata-radar-geotiff` (region eu-west-1, listable over HTTPS, e.g. `https://fmi-opendata-radar-geotiff.s3.eu-west-1.amazonaws.com/?list-type=2&prefix=2026/10/23/finrad/`). The Finland composite is `YYYY/MM/DD/finrad/YYYYMMDDHHMM_finland_cappi_600_dbzh_finrad_qc.tif`, one file every 5 minutes (12 per hour), about 1.6 MB, **reflectivity (dBZ) at 600 m height, not rain rate**: 8-bit, 5120 x 6144 cells of 250 m, LZW, projection ETRS89 / TM35FIN (EPSG:3067), top-left corner (-208000, 7926000) m, dBZ = 0.5 x value - 32, value 255 = no data. A rain rate has to be derived with a Z-R relation (for example Z = 200 R^1.6, an approximation). The sibling `finradfast` composite only offers accumulations (1 h, 12 h, 24 h). FMI keeps about 6 days in its download service; the bucket retention is not stated. FMI says its WMS is not meant for bulk or app use, so download the files. The layer names will change in November 2026. Turku lies well inside the composite. Source: https://en.ilmatieteenlaitos.fi/radar-data-on-aws-s3
- **Pan-European (OPERA):** 15-minute, 2 km composites exist in EUMETNET's Open Radar Data archive (CloudFerro S3, 2011 to present), but the terms for free use are unclear (the only licence found is for research and education) and **Italy does not take part in OPERA**, which is why DPC is needed. Not pursued unless the user wants it.
- **Rendering worked in the PoC:** reprojecting to Web Mercator tiles with bilinear interpolation took about 25 ms per tile (numpy + pyproj + Pillow). Compared with RainViewer it showed the same storms, smoother and with stronger cores.
- **Snow:** DPC has no rain/snow distinction (RainViewer does). Idea for later: classify by model temperature near or below 0 °C.
- **Discarded:** EUMETSAT satellite clouds (works, not needed), Open-Meteo grid sampling or raw model files (modelled, not observed rain), LibreWXR (self-hosted OPERA+DPC aggregator, AGPL, untested, possible alternative for pan-European radar).

### Radar architecture

- **Two radar sources behind one screen:** RainViewer (built in, default, no setup) and the home server (optional, off by default, see Decisions). The app has a small `RadarSource` abstraction (frame list + tile URL template + attribution) so the map code does not care which one is active.
- The **backend** runs in Docker on the home server, ingests DPC frames, keeps 6 hours of history, and serves tiles plus a frame list over HTTP. The app shows them as a raster layer over MapLibre. If the server is switched on but unreachable, the app falls back to RainViewer.
- **Ingest:** the plan was to use the DPC WebSocket to learn about new frames, with a fallback check of `findLastProductByType` that downloads only when a new frame exists. No blind polling of the download endpoint. (Implemented as polling only, timed to when the next frame is due; see Phase 4.)
- **API (backend):** `GET /frames` (frame ids = epoch seconds, oldest to newest), `GET /tiles/{frame}/{palette}/{z}/{x}/{y}.png`, `GET /coverage?lat=&lon=` (is a point covered by the newest frame?), `GET /palettes`, `GET /health`, `GET /attribution`. Frames are immutable once complete, so past-frame tiles are served with `Cache-Control: public, max-age=31536000, immutable`; only the newest frame gets a short cache.
- **Colours:** user-selectable, including for DPC. The backend renders with a named palette and caches tiles per palette; custom colour stops and the rain/snow colour split come later.
- **Access:** only over Tailscale: the port is published on the Tailscale IP `100.95.20.125`, never on all interfaces. Android allows one VPN at a time, so Tailscale must stay on on the phone. Plain HTTP is fine because Tailscale encrypts the link; the app's network security config allows cleartext for that one address only. Fallbacks if Tailscale on the phone is a problem: Tailscale Funnel or a Cloudflare Tunnel with a token.
- **Data budget (mobile data matters):**
  - Tiles are palette PNGs (a few KB each); fully empty tiles are one shared tiny image.
  - The backend only serves up to about the native resolution (the DPC grid is 1 km, roughly zoom 8); the app **overzooms** beyond that instead of downloading finer tiles that contain no extra information. Tiles are 256 px.
  - Radar history is loaded lazily and cached; on a metered connection only when the user asks (see Decisions). The forecast screen uses compressed JSON and no background work.
  - **Measured on the phone (Wi-Fi, empty map cache, regional view, 2026-10-24):** opening the radar the first time cost about **3.4 MB** in total with the home server and about **4.1 MB** with RainViewer. Of that, **roughly 3 MB is the vector basemap** (OpenFreeMap dark style: style, fonts, map tiles), which is cached afterwards (the map cache file was 3.2 MB). The 13 radar frames themselves cost **0.4 MB from the home server** (counted at the server's network interface) and **about 1.1 MB from RainViewer** (derived by difference; its tiles are 20 to 35 KB each). Playing the animation afterwards cost about 3 KB. A repeat view is nearly free. The app shows "about 2 MB" (RainViewer) or "about 1 MB" (server) before loading history on mobile data.
  - **Map style data (first open, empty cache, same regional view, RainViewer frames included, 2026-10-24, one run each; Detailed twice with the same result):** Dark 4.3 MB, Bright 4.8 MB, Light 4.8 MB, **Detailed 6.1 MB**, satellite with labels 5.3 MB. Detailed costs about 1.8 MB more than Dark the first time and nothing more once cached. (An earlier four-style run was invalid because the app was still in satellite mode, which always uses the Detailed style; the basemap choice is remembered between visits.)
  - Panning to a new area with all frames loaded fetches that area's tiles for every frame (several MB at once with RainViewer), which is why history on a metered connection waits for Play.
  - Idea if the first-open cost matters: a lighter basemap style or lower maximum zoom; not done.

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
- **Rain icon rule (revised 2026-10-26):** the weather code and the amount come from one detailed model, the rain probability from about 30 coarser ensemble runs (the share with at least 0.1 mm in that hour). So "overcast, 0 mm, 92%" happens and usually means "very likely a few drops, timing uncertain" (Turku, 26 Oct: 87-92% at 22-23 h with 0 mm, then 0.1 mm of drizzle at midnight). For cloudy, partly cloudy and foggy hours, day parts and days with a probability of at least 50% (`RAIN_LIKELY_PERCENT`), a **possible-rain icon** (cloud with one drop, `ic_rain_possible`, display-only kind `ConditionKind.PossibleRain`) and the text "Chance of light rain" are shown; the mm column stays "–". Real rain icons only where the main forecast has rain. (Before: the light-rain icon, which looked like rain the forecast did not have.) Clear skies and real precipitation codes are never changed. The current-conditions card follows the observed code only.
- **Day icon in the 10-day list (2026-10-26):** Open-Meteo's daily weather code is the worst weather of the whole day, so one heavy shower at 1 am made the day look like a downpour. The icon is now computed from the hours (`domain/DayOverview.kt`): only waking hours 7:00-22:00 count, and for today only the hours still to come (late evening, the remaining night hours). Thunder in any of them gives a storm icon; rain or snow in at least 2 of them gives the most common wet condition; otherwise the most common dry condition (ties go to the cloudier one). The rain chance that feeds the possible-rain icon comes from the same hours. The printed percentage stays the day's maximum, except today, where it covers the rest of the day. Without hourly data the daily code is used.
- **Current-conditions card (2026-10-26):** "H 12°  L 7°" replaced by a range bar for today ("10° ━━●━ 12°", dot at the current temperature, kept on the bar if slightly outside) with "Feels like" on its own line below.
- **Time zones:** "Today" and "Tomorrow" follow the place's own calendar and the rows show the date underneath. When the place's UTC offset differs from the phone's, the current-conditions card adds "Local time HH:mm · weekday date". (The dev phone is on Helsinki time, so Italian places are still on the previous day for one hour after midnight.)
- **App icon:** adaptive icon (sun behind a cloud on a blue gradient, plus a monochrome layer for Android 13 themed icons) in `res/drawable/ic_launcher_*.xml` and `res/mipmap-anydpi/ic_launcher.xml`.
- **Known gaps:** the Places screen reorders with up/down buttons, not drag and drop.
- **Dev notes:** the debug build is slow (a few frames per second on the A32 at times), which is normal for debug; try `assembleRelease` if performance matters. Lint passes without errors; remaining warnings are the missing icon, `allowBackup`, one long icon path and a state-type hint.

## Phase 4 – Radar backend (done 2026-10-24; 24 h soak check still running)

Project: `~/Desktop/projects/weather-radar` (own git repo, public on GitHub at https://github.com/R2-D8/weather_radar_backend, kept updated by pushing after each piece of work; README, CLAUDE.md). Python, aiohttp, numpy, pyproj, Pillow; 92 tests, ruff and strict mypy clean. Not the deleted proof of concept.

**Done (2026-10-24):**
- Ingest: polls DPC `findLastProductByType` **timed to when the next frame is due** (frames come every 5 minutes and appear about 9 to 10 minutes late; the delay is learned; after a new frame the service sleeps until the next is due, then re-checks every 30 s, so about 1 to 3 tiny requests per 5 minutes instead of 5 a minute-poll would make) and downloads missing frames, newest first, at most 12 per poll, with retries, a growing pause while DPC is down, and a check that every download really is a frame on the expected grid before it is stored. 6 hours of history are kept (`RADAR_HISTORY_HOURS`). A real start with an empty store fetched 12 frames in about 7 s.
- Renderer: tile from frame in **about 20 ms** (target under 100 ms), rainy tiles 3 to 6 KB, empty tiles one shared 116-byte PNG. Bilinear sampling, averaged lower-detail copies of the grid for zoomed-out tiles, 4-bit palette PNG, 256 or 512 px tiles, zoom 0 to 8.
- API as in Radar architecture (`/frames`, `/tiles/...`, `/palettes`, `/health`, `/attribution`); two palettes (`classic`, `blues`); tiles in memory cache (cold 20 to 45 ms over HTTP, cached under 1 ms); past frames are `immutable` for the phone's cache.
- Docker image (263 MB) tested locally as an unprivileged user with a read-only filesystem and all capabilities dropped; Compose stack and deploy scripts written.

**Findings that differ from the plan or notes:**
- **WebSocket not used.** `wss://radar-wss.protezionecivile.it` accepts a connection but did not answer a STOMP handshake and sent nothing in 40 s, so announcements are unverified. Polling the tiny `findLastProductByType` JSON once a minute is what is implemented; a WebSocket can be added later if it ever matters.
- The GeoTIFF tags confirm the notes exactly (1 km grid, top-left corner (-600000, 650000) m, Transverse Mercator at 42 N 12.5 E, WGS84, no-data -9999). Every file is checked against them.
- **Geometry check:** unit tests cover the projection origin, north-up/east-right orientation and cell centring. A comparison with RainViewer tiles of the same moment was inconclusive (their echo area is about 2.5 times larger, intensities do not correlate), so the final visual check is overlaying the tiles on a basemap in Phase 5.
- Found while testing: reading a download with a single `read()` can return a partial body; it now reads to the end in chunks.

**Security:** the service runs as its own unprivileged user `weather-radar` (uid/gid 10001: not in the sudo or docker groups, no login shell, no home), not as `r2d8`. The container has a read-only root filesystem, no capabilities, `no-new-privileges`, 768 MB memory limit and its port is published only on `100.95.20.125`. The user and `./data` directory are created by `deploy/create-user.sh`, which the user runs with sudo (copied to `/tmp/weather-radar-create-user.sh` on the server).

**Deployed (2026-10-24):** `deploy/create-user.sh` was run by the user (it created user `weather-radar`, uid 10001, **gid 987**: that first version let the system pick the group id; the script now creates gid 10001 and repairs an existing group when re-run, which is optional because the container's uid 10001 owns the data folder), then `deploy/deploy.sh` built and started the stack in `/srv/stacks/weather-radar`. Checked from the PC over Tailscale: `/health` ok, 24 of 73 frames after the first minute (12 per 20 s poll), tiles 3 to 5 KB in 0.2 to 0.6 s including the network, container user `uid=10001(radar)`, data folder unreadable by `r2d8` as intended.

**Verified:**
- The phone reaches `/health` over Tailscale (user confirmed, 2026-10-24).
- **Reboot test passed (2026-10-24):** the server came back on the new kernel 6.8.0-147 with no reboot pending; Tailscale had its address; the container (`restart: unless-stopped`) was up and healthy about a minute after boot with 0 restarts, and all 73 frames in the data volume survived.

**Remaining:** the 24 h check (frames keep updating without errors). The clock restarted at the last redeploy (the `/coverage` endpoint, about 22:45 UTC on 23 Oct); look at `docker logs weather-radar` and `/health` (`last_error`, `downloaded_total`, newest frame age) after that. Nothing else blocks Phase 5.
- Later: rain/snow split.

## Phase 4b – Radar for places outside Italy (dropped by the user, 2026-10-24)

**Decision:** dropped, not planned. Places outside Italy (such as Turku) use RainViewer; the home server stays Italy-only. The FMI findings stay under research findings above; the backend design for it was removed from this plan and is in git history if ever needed.

## Phase 5 – Radar in the app (done 2026-10-24; mobile-data path untested on a real mobile connection)

- MapLibre map with a **normal / satellite switch**:
  - Normal: OpenFreeMap vector styles (`https://tiles.openfreemap.org/styles/{liberty,bright,positron,dark}`), selectable in Settings (Detailed = liberty by default). No key, no stated limits, donation-funded, no SLA, attribution shown by MapLibre's (i) button.
  - Satellite: EOX Sentinel-2 cloudless 2024 raster tiles (`https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg`), source max zoom 14 (10 m/px, blurry beyond), **with labels**: the app loads the Detailed style, hides its land, water, building and shading layers, keeps all symbol layers except small points of interest and one-way arrows, keeps roads, bridges and borders (not railways, waterways or parks), and inserts the satellite layer at the bottom (`ui/radar/SatelliteLabels.kt` holds the keep-or-hide rule, unit-tested). Imagery is free for personal non-commercial use (CC BY-NC-SA 4.0), attribution "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)" in the source (shown in the (i) dialog). Becomes a commercial licence if the app is ever monetised or sold.
  - Not chosen: Esri World Imagery (key and unclear terms), OSM's own tile servers (not for app use), Mapbox/MapTiler (key and limits).
- **Radar overlay** with the last 2 hours (13 frames, one every 10 minutes): play/pause, timeline slider with the frame time and "x min ago", opacity slider, legend (home server only), credits line plus MapLibre's (i), place marker. RainViewer is the default and works for everyone.
- **Settings:** "Radar" (history on mobile data, default off) and "Home server (optional)": switch (off), address field (empty), "Test connection", server radar colours (Classic, Blues). Nothing is sent to the server unless the switch is on and an address is set (unit-tested); a failed or irrelevant server falls back to RainViewer with a notice ("not reachable (is Tailscale on?)", "no radar for this place", "no frames yet", unusable address).
- Done when: radar animates smoothly on the phone with RainViewer *(yes)*, and with the home server at home and away from home over Tailscale *(yes, from Turku to the server in Italy)*; a fresh install with default settings makes no request to the server *(unit-tested)*; the measured data use meets the budget *(see Data budget: radar frames 0.4 to 1.1 MB, the first-time basemap about 3 MB)*.

### How it works (for later phases)

- **Code** (`dev.r2d8.weather`): `data/radar/` (`RadarApis`: RainViewer and home-server Retrofit APIs and DTOs; `ServerAddress`: normalising the typed address and the cleartext rule; `RadarRepository`: picks the source, falls back, thins the server's 5-minute frames to every 10 minutes over 2 hours), `data/NetworkMonitor` (metered or not, from `ConnectivityManager`), `domain/Radar.kt`, `domain/PlaceResolution.kt` (shared by the weather and radar screens), `ui/radar/` (`RadarViewModel`, `RadarScreen`, `RadarMap`), Settings additions.
- **Map internals:** one raster source and one raster layer **per frame**; only the current frame has opacity above 0, the others stay at 0 so their tiles are fetched and cached, which makes playing smooth. Radar layers sit below the first label layer of the vector style. `RadarMap` forwards the Android lifecycle to the classic `MapView`, rebuilds layers after every style change, and keeps MapLibre's logo and (i) button above the controls card. The ViewModel's playback loop keeps its own frame index (reading it back from derived state repeated frames; found by a unit test).
- **Source choice:** server only if the switch is on, the address is usable and `GET /coverage` says the place is covered, else RainViewer. RainViewer: `https://api.rainviewer.com/public/weather-maps.json`, 13 past frames, tiles `{host}{path}/256/{z}/{x}/{y}/2/1_1.png`, max zoom 7.
- **Network security config** (`res/xml/network_security_config.xml`): cleartext HTTP blocked everywhere except `100.95.20.125`, the home server's Tailscale address (a unit test guards this). `ACCESS_NETWORK_STATE` is the only new permission.
- **Findings:**
  - RainViewer's free tiles **ignore the colour-scheme option**: all schemes returned identical pictures and sizes. Its picture includes very faint echoes as a tan haze, so it looks muddier and shows about 2.5 times the rain area DPC does.
  - RainViewer tiles are heavy (20 to 35 KB, 8-bit RGBA) compared with the server's palette PNGs (3 to 6 KB); zoom 8 and above returns a 1.3 KB placeholder.
  - **Alignment check:** the server's tiles and RainViewer's, for the same moment, show the same storm cells in the same places (overlay checked), so the DPC grid geometry is right.
  - The phone to server round trip over Tailscale was about 390 ms in one test, which suggests a relayed rather than direct path; worth a look in Phase 7.
- **Bugs found and fixed during testing:** a corrupted-file edit slip in my tooling (not in the app); the playback loop repeating frames; the home-server address field showing empty although saved (copied before the settings had loaded); the map's credits button hidden behind the controls.
- **Known gaps:** no legend for RainViewer (its API gives none); the mobile-data behaviour (newest frame only, Play loads history, "Load once") is covered by unit tests but could not be tried on a real mobile connection because mobile data is off on the dev phone; RainViewer shows only past frames (no nowcast on the free tier); the APK was 51 MB because MapLibre ships native libraries for four CPU types (fixed 2026-10-24: release builds are 64-bit ARM only, 16.7 MB); map labels are in each place's local language (OpenFreeMap), and the satellite view's roads and labels come from the Detailed style only, whatever style is chosen for the normal map.

## Phase 5b – Radar forecast and offline maps (app side done; model frames next)

**Decisions (2026-10-24):** a home-server forecast up to **+30 minutes** (Italy only); offline maps = **pinned map version + bigger cache + packs around each saved place** (no satellite packs); updates **when the app is opened on Wi-Fi** (no background work).

**Done:** backend nowcast deployed (`nowcast.py`, `GET /forecast/{base}/{lead}/...`, `forecast` list in `/frames`); computed on first request after each new frame in about 1.2 s on the server, tiles about 3 KB, container memory about 134 MB (OpenCV added). Re-run of the backtest with the deployed code (16 start times): CSI >= 1 mm/h 0.51 -> 0.68 at +10, 0.37 -> 0.54 at +20, 0.31 -> 0.44 at +30 min. **Known limitation:** the optical flow under-measures motion in the faint parts of a shower (about 70 % of the true speed there, 90 % in the cores), which looked like a lag; the second backtest below found that on real rain, speeding the motion up makes scores worse, so it is not a practical problem.

**Done in the app (2026-10-24):**
- **Forecast frames:** shown after "now" on the radar slider, with the time in a different colour, "forecast +N min" and a note that new showers cannot be predicted. Only used if the forecast belongs to the newest frame. Checked on the phone with the server on (Lancenigo-Villorba, rain over Friuli, +30 min frame). The user's phone now has the home server switched on (`100.95.20.125:8080`).
- **Offline maps** (`data/map`), checked on the phone:
  - **Pinned map version.** `MapDataRepository` reads OpenFreeMap's TileJSON (`https://tiles.openfreemap.org/planet`) and writes each style to `files/map/` with the `openmaptiles` source changed from `url` to the fixed versioned `tiles` address (`StylePinning`). The map loads styles from `https://pinned-style.invalid/<style>.json`, a made-up address that an OkHttp interceptor in MapLibre's HTTP client answers from disk (`.invalid` never resolves, so nothing leaks). A local `file://` address would also show the map, but it is unclear whether MapLibre's offline downloader can read one; the fixed address works for both and stays the same when the version changes.
  - **Updates.** A newer version is taken only on Wi-Fi and only after 30 days. Once a day, on any connection, one HEAD request checks that the pinned version still exists; if it is gone (404/410) the app moves to the newest at once, since otherwise the map would be blank.
  - **Bigger cache.** MapLibre's ambient cache goes from 50 to 250 MB.
  - **Packs per saved place** (`OfflinePacks`, `PackPlanner`). A box of about 60 x 60 km (30 km radius), zoom 0 to 12, made with the Detailed style. All four styles use the same tiles, so the others work too; only fonts a style never showed online may be missing. Within OpenFreeMap's terms: at most 3,000 tiles, at most 50 MB (above that, the most detailed zoom is dropped and the pack is retried), at most 4 parallel requests while downloading, and a User-Agent naming the app. Each pack stores its place, coordinates and version, so it is replaced when the place moves or the version changes (the old pack is deleted only after the new one is complete) and deleted when the place is removed. No Chinese/Japanese/Korean glyphs.
  - **When.** `OfflineMapsUpdater` runs only while the app is on screen (`repeatOnLifecycle(STARTED)` in `MainActivity`). It starts 3 s after opening, restarts when the saved places or the connection change, and downloads only on an unmetered connection. Leaving the app pauses a download and opening it again resumes it. There is no background work.
  - **Settings, "Offline maps".** A status line per saved place (waiting for Wi-Fi, downloading %, ready with size, problem) and the map data version in use.
  - **Measured (2026-10-24, Wi-Fi):** Lancenigo-Villorba pack 17.6 MB, Turku 19.0 MB, both done in under 20 s. Normal and satellite views render from the pinned style.
  - **Not done:** satellite packs (EOX terms unclear). Radar frames still need a connection, so without one the radar screen shows its "could not load" message rather than a bare map.

**Second backtest: can Open-Meteo data improve the nowcast? (2026-10-24, answer: no)**
- **Data.** DPC serves past frames at least 14 days back (checked 6 h to 336 h), so no change to the server was needed: 5-minute frames for 8 six-hour rain windows between 10 and 24 October were downloaded to the PC (about 580 frames; events on 10, 12, 18, 20-21, 23 and 24 Oct, including the large 20-21 Oct one). Open-Meteo's Historical Forecast API (archived model forecasts stitched from the first hours of each run, so slightly better than what is available live) gave hourly 700/850 hPa winds and 15-minute precipitation on a 0.25° grid (1,650 points inside the radar area), interpolated to the 1 km radar grid.
- **Method.** A start every 20 minutes with rain on at least 0.3 % of the area (121 starts), scored at +10/+20/+30 min against the observed frame: CSI at 1 and 5 mm/h, and fractions skill score (FSS, 1 mm/h, 11 km window, more forgiving of small position errors). Differences are paired per start.
- **Model winds point the right way but are too fast.** Direction agreement with the radar motion over rain was 0.95-0.99 (700 hPa), but the 700 hPa wind is about 1.6 times the speed at which the rain actually moved.
- **Results at +30 min** (CSI >= 1 mm/h / CSI >= 5 / FSS):

  | Variant | +30 min |
  |---|---|
  | Rain stays where it is | 0.413 / 0.228 / 0.714 |
  | Current nowcast (one 10-min motion step) | 0.504 / 0.286 / 0.805 |
  | Model wind where the radar motion is uncertain | 0.503 / 0.285 / 0.804 (no change) |
  | 50 % radar motion + 50 % model wind | 0.501 / 0.278 / 0.806 |
  | Model wind only (700 hPa) | 0.477 / 0.252 / 0.787 |
  | Radar direction, model speed | 0.502 / 0.279 / 0.804 |
  | Radar motion x 1.2 | 0.496 / 0.273 / 0.798 |
  | Model rain trend as growth/decay | 0.503 / 0.282 / 0.804 |
  | Blend 15 % model rain | 0.507 / 0.280 / 0.805 |
  | Blend 30 % model rain | 0.484 / 0.267 / 0.777 |
  | Model rain only | 0.230 / 0.068 / 0.423 |
  | **Average of the last two motion steps** (radar only) | **0.511 / 0.292 / 0.813** |

- **Conclusions.** Nothing from Open-Meteo helps at 10-30 minutes: the radar's own motion is closer to how rain moves than the model winds, and model rain is placed too imprecisely at 1 km. The speed-up variants also lost, so the "under-measured speed" seen on synthetic showers is not a problem on real rain. The one gain is radar-only: averaging the motion of the last two 10-minute steps, +0.005 to +0.008 on every score and lead, better in about 70 % of starts and on 6 of 7 days. Averaging three steps or weighting 0.5/0.3/0.2 was no better.
- **Done:** the backend now averages two motion steps when the frame 20 minutes earlier is there (`compute(..., oldest=...)`), deployed 2026-10-24 (backend commit ef48425). Model data may still be useful for model forecast frames beyond the nowcast (next item), where radar extrapolation has no skill left.

**Next (agreed):** research model forecast frames (below).

**Idea raised by the user, not decided:** use weather-model forecasts beyond the 30-minute nowcast (see "Model forecast frames" below).


**Radar forecast (nowcast).**
- RainViewer removed forecast frames from its free API on 1 January 2026 (with it: one colour scheme only, max zoom 7, 100 requests per IP per minute; https://rainviewer.com/api/transition-faq.html). DPC publishes observations only.
- Option: the home server computes its own short forecast by moving the newest DPC frame along the motion measured between recent frames (optical flow, semi-Lagrangian advection). **Backtest on real DPC data** (10 start times over about 3 hours on 23 Oct 2026, OpenCV Farneback flow over 10 minutes, motion capped at 35 km per 10 min), critical success index (overlap of forecast and observed rain, 0 to 1) against "the rain stays where it is":

  | Lead | CSI >= 1 mm/h | CSI >= 5 mm/h | correlation |
  |---|---|---|---|
  | 15 min | 0.46 -> **0.62** | 0.33 -> **0.43** | 0.73 -> 0.88 |
  | 30 min | 0.33 -> **0.46** | 0.25 -> **0.29** | 0.59 -> 0.75 |
  | 60 min | 0.23 -> 0.28 | 0.20 -> 0.17 | 0.45 -> 0.54 |
  | 90 min | 0.18 -> 0.21 | 0.18 -> 0.13 | 0.40 -> 0.42 |

  Useful to about 30 minutes, marginal at 60, not worth it beyond; one evening only, so indicative. It cannot predict new storms forming. Italy only (DPC area). Libraries such as pysteps may do somewhat better. Other sources not evaluated: Rainbow.ai has a nowcast API with a free tier (needs an account and key).

**Model forecast frames (idea, to verify).** Radar extrapolation only moves existing rain; a weather model also forms and dissolves storms, so it is the better source beyond about 30 to 60 minutes (it places rain less precisely in the first hour, which is why the two are usually blended: nowcast first, model after). Candidates, free and without a key, to be checked before deciding: DWD open data (opendata.dwd.de) with ICON-EU (about 7 km, all of Europe including Finland, hourly) and ICON-D2 (about 2 km, Germany and the Alps including northern Italy). The server would download the precipitation fields, render them with the same palettes as tiles and the app would show them after the nowcast frames, labelled as model forecast. Open-Meteo is point-based, so sampling a map grid from it would use many of its 10,000 daily calls; raw model files fit better. (The earlier "discarded" note about model data was about using it *instead of* radar, not as forecast frames.)

**Offline maps.**
- Radar cannot be offline (new frame every 5 minutes); past frames are already cached on the phone.
- Cache rules measured from the servers' headers: OpenFreeMap vector tiles may be kept 10 years (`max-age=315360000`), its style 1 day; EOX satellite tiles 7 days (revalidated with `Last-Modified`); RainViewer tiles 2 days.
- **Catch:** OpenFreeMap publishes a new map version weekly (tile URLs contain the version, e.g. `planet/20261018_080001_pt/`), and the style points to the newest. So any area viewed is downloaded again roughly every week (first open of a regional view with the Detailed style is about 5 MB of map data).
- Measured tile sizes (median of six places): vector 80 to 210 KB per tile (z6 211, z10 118, z12 93, z13 80, z14 165 KB); satellite JPEG 19 to 28 KB.
- OpenFreeMap allows pre-downloading areas for offline use, but each download at most 3,000 tiles or 50 MB, at most 4 parallel requests, with a User-Agent that identifies the app; larger needs: contact them, or self-host (they offer weekly planet downloads). A pack around one saved place (about 60 x 60 km) would be about 10 MB to zoom 12 or about 33 MB to zoom 13; all of Italy exceeds the limits. EOX gives no terms for bulk or offline caching on its site (it points to cloudless@eox.at), so satellite should not be pre-downloaded without asking them.
- Design as proposed before building (built as described under "Done in the app"; the style is not bundled but downloaded and pinned on first use): (1) pin the OpenFreeMap map version in a style bundled with the app and move to a newer version only on Wi-Fi, for example monthly, falling back to the newest if the pinned one is gone; (2) raise MapLibre's map cache from the default 50 MB so viewed areas stay; (3) optionally, MapLibre offline packs around saved places, downloaded on Wi-Fi within OpenFreeMap's limits. Updates happen when the app is opened on Wi-Fi (no background work, rule 8), unless the user wants a background job.

## Phase 6 – Later / stretch

Rain vs snow colours for DPC, notifications, home-screen widget, "rain starts in X minutes" from radar nowcast, severe-weather alerts. Server: Pi-hole, password manager, git server (see [server.md](server.md)).

## Phase 7 – Tailscale security on the phone (done 2026-10-24)

Goal: keep the convenience of reaching the home server from the phone, while making a lost or compromised phone a small problem instead of a way into the server.

**What is known (cross-checked 2026-10-24, measurements from the user's Galaxy A32 against published reports):**

| Topic | Published | Measured on the phone |
|---|---|---|
| Idle data | Forum reports of ~64-byte packets every 5 to 10 s, roughly 30 MB a week or ~120 MB a month idle ([forum 1](https://forum.tailscale.com/t/data-consumption-when-idle-on-metered-connection/3241), [forum 2](https://forum.tailscale.com/t/volume-of-idle-traffic/1318)); a Tailscale reply says the mobile apps are tuned to reduce idle traffic and no knobs exist ([forum 3](https://forum.tailscale.com/t/ways-to-minimize-idle-traffic/2188)). Gigabyte-per-day cases had other causes (two overlay networks, flapping relays). | About 0.2 MB in ~12 h on Wi-Fi (Android's per-app counters) and 31 KB inside the tunnel (`tun0`). Roughly 10x below the forum figure. Android showed only "foreground" entries for the app, so this may be an undercount: treat it as a lower bound. |
| Battery | Anecdotes only, no official figures: from about 1% over ten days ([lemmy](https://lemmy.klein.ruhr/post/34612)) to ~5% a day, up to 30 to 40% overnight on GrapheneOS with the cause disputed ([lemmy](https://feddit.it/post/8664648)). Depends on traffic through the tunnel; split tunnelling (only tailnet addresses use it, which is the default without an exit node) is reported as unnoticeable. | Tailscale's VPN service ran 12 h 20 min; about 5 s of CPU; not in the per-app power list. Fits the low end. |
| Reliability | Reports of the Android app dropping every 10 to 20 minutes unless it is the system's always-on VPN or exempt from battery optimisation ([forum](https://forum.tailscale.com/t/android-constantly-stopping/4689)). | Not observed (connected the whole charge cycle). |
| Lost phone | Official guidance: nodes re-authenticate every 180 days by default, keep key expiry on, remove the device in the admin console, use least-privilege access rules, auto-update clients ([Tailscale security best practices](https://tailscale.com/kb/1196/security)). Access rules are deny-by-default once defined ([grants syntax](https://tailscale.com/kb/1538/grants-syntax)). | Not checked: the user has not shown the tailnet policy or the phone's key-expiry setting. |

**Checked 2026-10-24 (read-only):**
- Tailnet devices: `r2d8-laptop` 100.86.74.124 (key expires 2027-03-16), `A32 di Lorenzo` 100.125.5.80 (key expires **2026-11-11**: the phone will ask to log in to Tailscale again then), `red8` 100.95.20.125 (key expiry disabled, deliberately).
- Paths (`tailscale ping`): phone to server **direct** (about 64 ms), laptop to phone direct, laptop to server **relayed** through Tailscale's Frankfurt relay (60-125 ms; slower, not less safe). The earlier 330-390 ms phone pings were relayed at the time.
- The server listens for SSH on all interfaces (`0.0.0.0:22`, also the guest Wi-Fi), the radar only on the Tailscale address (`100.95.20.125:8080`). Password login and the firewall state need root to read.
- **From the phone today:** port 8080 answers (`HTTP/1.0 200 OK`) and so does the server's SSH (`SSH-2.0-OpenSSH_9.6p1`): the risk below is real. The laptop has no SSH server (connection refused).
- Phone probes use Android's `nc` without `-z`: `adb shell 'printf "GET /health HTTP/1.0\r\n\r\n" | timeout 5 nc 100.95.20.125 8080 | head -1'` and `adb shell 'timeout 5 nc 100.95.20.125 22 </dev/null | head -1'`.

**Earlier note:** a ping from the phone to the server over Tailscale took about 390 ms; look at `tailscale status` on the PC and the phone to see whether the connection is direct or relayed (a relayed path is slower, not less safe).

**Done 2026-10-24:**
- **Work item 1-2, access rules.** The tailnet had the default allow-all policy. Replaced (by the user, in the admin console) with this policy; the old one is the default "allow all" plus Funnel for members:

  ```jsonc
  {
    "hosts": {"laptop": "100.86.74.124", "red8": "100.95.20.125", "phone": "100.125.5.80"},
    "grants": [
      {"src": ["laptop"], "dst": ["*"], "ip": ["*"]},
      {"src": ["phone"], "dst": ["red8"], "ip": ["tcp:8080"]},
    ],
    "ssh": [{"action": "check", "src": ["autogroup:member"], "dst": ["autogroup:self"], "users": ["autogroup:nonroot", "root"]}],
    // "nodeAttrs" with Funnel: commented out (not used)
    "tests": [
      {"src": "laptop", "accept": ["red8:22", "red8:8080", "phone:5555"]},
      {"src": "phone", "accept": ["red8:8080"], "deny": ["red8:22", "red8:80", "laptop:22"]},
    ],
  }
  ```

  Verified from the phone: `/health` on 8080 answers and the radar works in the app (server frames and forecast); port 22 no longer answers; the laptop is unreachable. The laptop still reaches the server's SSH; the server cannot reach the phone. Ping from the phone still works (Tailscale lets ICMP through to devices you may reach at all). Grants also control plain OpenSSH; the `ssh` section only concerns Tailscale SSH, which is not used.
- **Work item 3, SSH password login off** (script run by the user with sudo, 10-minute self-revert timer, confirmed and made permanent): see [server.md](server.md).

- **Work item 6, app notice.** When the home server cannot be reached, the radar notice and "Test connection" now say which case it is, using whether this app's traffic goes through a VPN (`NetworkMonitor.isVpnActive`: the active network for the app has `TRANSPORT_VPN`): "Tailscale is off on this phone: turn it on to use your home server." or "The home server is not answering. Tailscale is on, so the server may be down or restarting." Unit-tested; checked on the phone with Tailscale on (port 8081, which the policy blocks, gave the second message; the address was put back to 8080 afterwards). The "Tailscale is off" case was not tried on the phone.
- **Work item 4, lost-phone procedure** (written, not practised):
  1. From any browser, open https://login.tailscale.com/admin/machines and remove `A32 di Lorenzo` (or disable its key). From then on it cannot reach anything in the tailnet; even before that it can only reach the radar on port 8080.
  2. Lock or wipe the phone with Samsung Find My Mobile or Google Find My Device.
  3. A new or returning phone gets a new Tailscale address: update `"phone"` in the policy's `hosts` (the tests will then pass again).
  - The phone's Tailscale key expires on **2026-11-11** (180 days after login); Tailscale asks to log in again then. Keep key expiry on for the phone, and keep a screen lock.
- **Work item 5 (a week of battery and data numbers): dropped by the user.** The table keeps the one-day measurement.

**Phase 7 is done** apart from the optional practice run of the lost-phone steps.

**Risks in this setup (as found before the work above):** (1) as far as the plan knows the tailnet still has the default policy that lets every device reach every other, so the phone could reach the server's SSH port (port 22 is open on the Tailscale interface); (2) SSH password login is still enabled on the server and `r2d8` is a sudoer; (3) Android allows one VPN at a time; (4) the server's own key expiry is disabled (deliberately, for availability).

**Work:**
1. Read the current tailnet policy and device list together with the user (the user pastes the policy file from the admin console; Claude never changes the Tailscale setup remotely).
2. Write and test an access rule so the phone can reach only the radar server on TCP 8080, while the PC keeps SSH. Draft (own reconstruction, **verify against the grants reference before use**): `{"grants": [{"src": ["<phone's 100.x address>"], "dst": ["100.95.20.125"], "ip": ["tcp:8080"]}]}` plus a grant for the PC. Test from the phone with `adb shell nc -zv 100.95.20.125 8080` (must work) and port 22 (must fail).
3. Server: turn off SSH password login (key login already works), with the revert timer and second session required by `docs/server.md`.
4. Phone: confirm key expiry is on for the phone and note its expiry date; write down the lost-phone procedure (remove the device in the admin console) and practise it once with a spare or the PC; keep the screen lock on. Optional: set Tailscale as Android's always-on VPN if drops are seen, and leave "block connections without VPN" **off** so the phone keeps normal internet if Tailscale drops.
5. Measure for a week: a small script that records Tailscale's data and battery counters from the phone once a day (`adb shell dumpsys batterystats` / `netstats`); replace the table's "measured" column with the result.
6. App side (small): the home-server switch stays off by default; consider showing "Tailscale not connected" in the radar notice when the server is unreachable.
7. Record the final policy in `docs/server.md`.
- Done when: the phone can fetch `/health` on port 8080 and cannot open port 22, SSH password login is off, the lost-phone procedure is written and tested, and a week of battery and data numbers is in this table.

## Server work remaining (not tied to a phase)

Reboot when someone is at home and optional firewall/SSH hardening. See [server.md](server.md).
