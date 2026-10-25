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

## Servers
Radarr is at `http://192.168.0.2:7878` and Sonarr is at `http://192.168.0.2:8989`. Both are linuxserver containers on Unraid.
The API keys are in `/mnt/user/appdata/{radarr,sonarr}/config.xml` on Unraid (`ssh root@192.168.0.2`). Copy them into `local.properties` and never commit them.

## Installing and testing on the TV
The TV is the Sony Bravia at `192.168.0.31:5555`. Run `adb connect`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
ADB debugging is normally turned off on the TV (see `bravia-speedup`), so Jim has to turn it back on first.
When you drive the app with `input keyevent`, check that `com.jim.arrtv` is the resumed activity before every key press.
If the app crashes, the keys go to the launcher and start other apps (it once opened Kayo).
Don't press "Add and download" or the Downloads "Remove" buttons during testing. They start or delete real downloads on Jim's server.

## Layout
- `data/`: settings storage, the API client and models, and `Queue.kt` (groups queue records per download and works out status labels)
- `ui/`: the ViewModel and Compose screens (tabs: Search, Downloads, Movies, TV Shows, Settings)
