# arr-tv

An Android TV app for browsing and controlling Jim's Radarr and Sonarr, which run on his Unraid server.
New movies and shows show up in Plex through the existing Unraid setup. This app never touches Plex directly.

## Stack
- Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Retrofit with kotlinx.serialization, Coil for posters
- AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21, compileSdk 34, minSdk 23
- Talks to the Radarr and Sonarr v3 REST APIs using the `X-Api-Key` header

## Build
- `./gradlew assembleDebug` creates `app/build/outputs/apk/debug/app-debug.apk`
- This VM has only ~3.3 GB RAM, so `gradle.properties` caps the heap and runs Kotlin in-process. Keep it that way.
- `local.properties` is gitignored. It holds the SDK path and the optional `arr.*` dev defaults (URLs and API keys) that get baked into builds so they don't have to be typed on the TV.

## Installing on the TV
Install over ADB on the Sony Bravia (`adb connect <tv-ip>` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
ADB is turned off on the TV after the debloat work in `bravia-speedup`, so turn it back on first.

## Layout
- `data/`: settings storage and the API client and models
- `ui/`: the ViewModel and Compose screens (tabs: Movies, TV Shows, Settings)
