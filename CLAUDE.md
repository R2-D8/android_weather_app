# CLAUDE.md

## Project
Ad-free Android weather app, personal use only (sideloaded). Kotlin + Jetpack Compose, min API 33. Weather data from Open-Meteo; radar map (MapLibre) fed by a radar backend on the user's home server (DPC data, reached over Tailscale) with RainViewer as an option. Region focus: Italy/Europe. Test on a Galaxy A32 (Android 13) via USB.

The plan lives in [docs/plan.md](docs/plan.md); home server notes in [docs/server.md](docs/server.md). Read them before starting any work.

## Rules

1. **Keep docs updated.** Whenever code, decisions, requirements or scope change, update `docs/plan.md` (and any other file in `docs/`) in the same piece of work. If this `CLAUDE.md` is affected (new conventions, commands, structure), update it too. Never leave docs stale.
2. **Ask, don't assume.** If anything is unclear or there is more than one reasonable option (libraries, architecture, API choice, UX, scope), ask the user questions instead of guessing.
3. **No ads, no trackers.** Do not add ad SDKs, analytics or tracking libraries. Avoid Google Play Services/Firebase dependencies unless the user approves.
4. **Radar backend is written from scratch.** The proof of concept was deleted on purpose to avoid carrying over errors; only the findings in `docs/plan.md` remain.
5. **Server changes are risky.** The home server (`ssh r2d8@red8`) is only reachable over Tailscale. Never touch its network, Wi-Fi, Tailscale or SSH config, and ask before any destructive step. Claude has no general sudo there: privileged steps are scripts in `/tmp` that the user runs. See [docs/server.md](docs/server.md).
6. **Respect API terms.** Free tiers used here are non-commercial; keep attribution (Open-Meteo CC BY 4.0, radar provider credit) in the app.
7. **The user is new to Android.** They are an experienced programmer but this is their first Android app. Explain Android-specific concepts briefly the first time they come up (Gradle files, manifest, Compose, lifecycle, permissions, adb, etc.), say what each command or file does and why, and point out common pitfalls. Don't over-explain general programming.

## Environment notes
- Dev machine: Fedora Linux. Android Studio is in `~/opt/android-studio` (start with `~/opt/android-studio/bin/studio.sh`), SDK in `~/Android/Sdk` (`ANDROID_HOME` and `platform-tools` are set in `~/.bashrc`).
- Gradle runs on Android Studio's bundled JDK (`~/opt/android-studio/jbr`, currently JDK 25), set via `org.gradle.java.home` in `~/.gradle/gradle.properties` (user-level, not in the project). The system default Java is 27-ea and must not be used for Android builds.
- Keep temporary files out of the project tree.

## Project structure
Single module `:app`, package `dev.r2d8.weather`, Kotlin sources in `app/src/main/kotlin`, versions in `gradle/libs.versions.toml`. Manual DI (no Hilt): `AppContainer` is created in `WeatherApplication` and ViewModels get dependencies from it. Packages: `data` (remote, local/Room, repositories for weather, places and settings, location), `domain` (models and pure logic), `ui` (Compose screens: weather, places, settings, about, theme). Room schemas are exported to `app/schemas` (commit them). Stack, phases and the Phase 2 design notes: see [docs/plan.md](docs/plan.md).

## Commands
Run from the project root (needs `ANDROID_HOME`, see above):
- Build debug APK: `./gradlew :app:assembleDebug` (output `app/build/outputs/apk/debug/app-debug.apk`)
- Install on the phone and start: `./gradlew :app:installDebug && adb shell am start -n dev.r2d8.weather/.MainActivity`
- Phone screenshots for checking the UI: `adb exec-out screencap -p > file.png` (the phone must be unlocked; look at a screenshot before tapping, and never answer system permission dialogs for the user)
- Check the phone is connected: `adb devices` (must say `device`, not `unauthorized`)
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Lint: `./gradlew :app:lintDebug`
- Release APK (signed with the debug key, for sideloading): `./gradlew :app:assembleRelease`
- Logs from the phone: `adb logcat --pid=$(adb shell pidof dev.r2d8.weather)`
