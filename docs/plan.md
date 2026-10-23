# Weather App – Plan

Ad-free Android weather app for personal use, with a rain/snow radar served by a backend on the home server.

**Status (2026-10-24):** Phases 0 to 3 are done, including the forecast redesign (Phase 2b), checked on the phone. **Phase 4** (radar backend) is deployed and waiting for its reboot and 24 h checks. Next: **Phase 5** (map and radar in the app). Phase 4b (radar for Finland) is postponed; Phase 7 (Tailscale security on the phone) is the last phase. Server notes are in [server.md](server.md).

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
- **API (backend):** `GET /frames` (frame ids = epoch seconds, oldest to newest), `GET /tiles/{frame}/{palette}/{z}/{x}/{y}.png`, `GET /palettes`, `GET /health`, `GET /attribution`. Frames are immutable once complete, so past-frame tiles are served with `Cache-Control: public, max-age=31536000, immutable`; only the newest frame gets a short cache.
- **Colours:** user-selectable, including for DPC. The backend renders with a named palette and caches tiles per palette; custom colour stops and the rain/snow colour split come later.
- **Access:** only over Tailscale: the port is published on the Tailscale IP `100.95.20.125`, never on all interfaces. Android allows one VPN at a time, so Tailscale must stay on on the phone. Plain HTTP is fine because Tailscale encrypts the link; the app's network security config allows cleartext for that one address only. Fallbacks if Tailscale on the phone is a problem: Tailscale Funnel or a Cloudflare Tunnel with a token.
- **Data budget (mobile data matters):**
  - Tiles are palette PNGs (a few KB each); fully empty tiles are one shared tiny image.
  - The backend only serves up to about the native resolution (the DPC grid is 1 km, roughly zoom 8); the app **overzooms** beyond that instead of downloading finer tiles that contain no extra information. Tile size (256 or 512 px) is decided by measurement in Phase 5.
  - The app loads only the newest frame at first and fetches older frames when the user presses play or drags the timeline; everything is cached on the phone, so a repeat view costs almost nothing.
  - Target to verify in Phase 5: first full 2-hour animation of a regional view under about 1 MB, a cached repeat view close to 0. The forecast screen already uses compressed JSON and no background work.

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

**Remaining:** the 24 h check (frames keep updating without errors). The clock restarted at the reboot (about 22:36 UTC on 23 Oct); look at `docker logs weather-radar` and `/health` (`last_error`, `downloaded_total`, newest frame age) after that. Nothing else blocks Phase 5.
- Later: rain/snow split.

## Phase 4b – Radar for places outside Italy (postponed by the user, 2026-10-24)

**Decision:** not now. Turku keeps using RainViewer (works in Finland); the home server stays Italy-only. When this is picked up later, build it **before** the app depends on the server API for more than Italy, so the app is not rewritten. Everything below is the researched starting point.

Why: the user studies in Turku for a while and saved it as a place; DPC covers Italy only, so the home-server radar shows nothing there (RainViewer still works). History cannot be kept "per saved place": a national radar mosaic covers a whole country, and the server would have to be told the user's places (which also leaks them). The workable design is **one source per region**, each stored in full.

- Backend gets a `Source` abstraction (id, name, coverage bounds, frame step, ingest, grid, decode). Sources: `dpc` (Italy, exists) and `fmi` (Finland, new). `GET /sources` lists them with bounds; `/frames` and `/tiles/...` take the source id (`/{source}/frames`, `/{source}/tiles/{frame}/{palette}/{z}/{x}/{y}.png`; the current paths stay as aliases for `dpc` until the app uses the new ones). The app picks the source whose bounds contain the place, and falls back to RainViewer if none does or the server is off.
- FMI ingest: list the bucket by the date prefix every few minutes, download new `finrad` files, keep 6 hours (72 frames, about 117 MB on disk, 31 MB decoded per frame in memory).
- Rendering: same pipeline; the FMI grid is TM35FIN at 250 m, so the projection and cell size are per source. dBZ is converted to mm/h with Z = 200 R^1.6 so both sources share the same palettes and legend (documented as an approximation: it ignores snow and bright-band effects, which matters in Finnish winter).
- Open questions: how much to trust the Z-R conversion for snow (a snow-specific relation or a dBZ legend for Finland); whether to add more sources later (Sweden, Estonia, OPERA).
- Done when: the app shows a Finnish frame list for Turku from the server, tiles render in under 100 ms, 24 h of FMI frames are stored without errors, and Italian behaviour is unchanged.

## Phase 5 – Radar in the app

- MapLibre map with a **normal / satellite switch**:
  - Normal: OpenFreeMap vector tiles (`https://tiles.openfreemap.org/styles/liberty`, also `bright`, `positron`, `dark`; the dark style follows the app's dark mode). No key, no stated limits, donation-funded, no SLA, attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" (MapLibre shows it automatically).
  - Satellite: EOX Sentinel-2 cloudless 2024 raster tiles (`https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg`, tested up to zoom 16). 10 m/px, so blurry beyond about zoom 14. Free for personal non-commercial use (CC BY-NC-SA 4.0), attribution required: "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2024)". Becomes a commercial licence if the app is ever monetised or distributed.
  - Not chosen: Esri World Imagery (sharper, but a key and unclear third-party terms), OSM's own tile servers (not for app use), Mapbox/MapTiler (key and limits).
- Radar overlay with animated past frames (last 2 hours by default), play/pause, timeline slider, opacity, legend, colour palette setting, attribution (Radar-DPC, RainViewer, map data). RainViewer works out of the box (zoom limit 7, overzoom). Past frames load lazily, see the data budget above.
- **Settings > Home server** (new): a switch, **off by default**, an address field, **empty by default**, and a "Test connection" button. When off, the app never contacts the server. When on but unreachable, the radar falls back to RainViewer with a small notice. The only server-related thing compiled in is the cleartext exception for `100.95.20.125` in the network security config.
- Done when: radar animates smoothly on the phone with RainViewer, and with the home server at home and away from home over Tailscale; a fresh install with default settings makes no request to the server; the measured data use meets the budget.

## Phase 6 – Later / stretch

Rain vs snow colours for DPC, notifications, home-screen widget, "rain starts in X minutes" from radar nowcast, severe-weather alerts. Server: Pi-hole, password manager, git server (see [server.md](server.md)).

## Phase 7 – Tailscale security on the phone (last phase)

Goal: keep the convenience of reaching the home server from the phone, while making a lost or compromised phone a small problem instead of a way into the server.

**What is known (cross-checked 2026-10-24, measurements from the user's Galaxy A32 against published reports):**

| Topic | Published | Measured on the phone |
|---|---|---|
| Idle data | Forum reports of ~64-byte packets every 5 to 10 s, roughly 30 MB a week or ~120 MB a month idle ([forum 1](https://forum.tailscale.com/t/data-consumption-when-idle-on-metered-connection/3241), [forum 2](https://forum.tailscale.com/t/volume-of-idle-traffic/1318)); a Tailscale reply says the mobile apps are tuned to reduce idle traffic and no knobs exist ([forum 3](https://forum.tailscale.com/t/ways-to-minimize-idle-traffic/2188)). Gigabyte-per-day cases had other causes (two overlay networks, flapping relays). | About 0.2 MB in ~12 h on Wi-Fi (Android's per-app counters) and 31 KB inside the tunnel (`tun0`). Roughly 10x below the forum figure. Android showed only "foreground" entries for the app, so this may be an undercount: treat it as a lower bound. |
| Battery | Anecdotes only, no official figures: from about 1% over ten days ([lemmy](https://lemmy.klein.ruhr/post/34612)) to ~5% a day, up to 30 to 40% overnight on GrapheneOS with the cause disputed ([lemmy](https://feddit.it/post/8664648)). Depends on traffic through the tunnel; split tunnelling (only tailnet addresses use it, which is the default without an exit node) is reported as unnoticeable. | Tailscale's VPN service ran 12 h 20 min; about 5 s of CPU; not in the per-app power list. Fits the low end. |
| Reliability | Reports of the Android app dropping every 10 to 20 minutes unless it is the system's always-on VPN or exempt from battery optimisation ([forum](https://forum.tailscale.com/t/android-constantly-stopping/4689)). | Not observed (connected the whole charge cycle). |
| Lost phone | Official guidance: nodes re-authenticate every 180 days by default, keep key expiry on, remove the device in the admin console, use least-privilege access rules, auto-update clients ([Tailscale security best practices](https://tailscale.com/kb/1196/security)). Access rules are deny-by-default once defined ([grants syntax](https://tailscale.com/kb/1538/grants-syntax)). | Not checked: the user has not shown the tailnet policy or the phone's key-expiry setting. |

**Risks in this setup:** (1) as far as the plan knows the tailnet still has the default policy that lets every device reach every other, so the phone could reach the server's SSH port (port 22 is open on the Tailscale interface); (2) SSH password login is still enabled on the server and `r2d8` is a sudoer; (3) Android allows one VPN at a time; (4) the server's own key expiry is disabled (deliberately, for availability).

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
