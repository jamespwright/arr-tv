# arr-tv

An Android TV app for browsing and controlling Jim's Radarr and Sonarr, which run on his Unraid server.
New movies and shows show up in Plex through the existing Unraid setup. This app never touches Plex directly.

## Stack
- Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Retrofit with kotlinx.serialization, Coil for posters
- AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21, compileSdk 34, minSdk 23
- Talks to the Radarr and Sonarr v3 REST APIs using the `X-Api-Key` header

## Build
- `./gradlew assembleRelease` creates `app/build/outputs/apk/release/app-release.apk`. Install that one: it's minified with R8
  and scrolls far more smoothly than the debug build. It's signed with the debug key, so it installs over either build.
- This VM has only ~3.3 GB RAM, so `gradle.properties` caps the heap and runs Kotlin in-process. Keep it that way.
- `local.properties` is gitignored. It holds the SDK path and the optional `arr.*` dev defaults (URLs and API keys) that get baked into builds so they don't have to be typed on the TV.

## Servers
Radarr is at `http://192.168.0.2:7878` and Sonarr is at `http://192.168.0.2:8989`. Both are linuxserver containers on Unraid.
The API keys are in `/mnt/user/appdata/{radarr,sonarr}/config.xml` on Unraid (`ssh root@192.168.0.2`). Copy them into `local.properties` and never commit them.

## Installing and testing on the TV
The TV is the Sony Bravia at `192.168.0.31:5555`. Run `adb connect`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.
Check jank with `adb shell dumpsys gfxinfo com.jim.arrtv` (run it with `reset` first).
ADB debugging is normally turned off on the TV (see `bravia-speedup`), so Jim has to turn it back on first.
When you drive the app with `input keyevent`, check that `com.jim.arrtv` is the resumed activity before every key press.
If the app crashes, the keys go to the launcher and start other apps (it once opened Kayo).
Don't press "Add and download", the Downloads "Remove" buttons, or any search or delete button on a detail page during testing.
They start or delete real downloads (or files) on Jim's server. Monitoring toggles are fine if you switch them straight back.
Send keys by name (`DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`, `DPAD_CENTER`, `BACK`), not raw keycodes. Keycode 23 is OK, not right, and mixing them up once started a real search.
Short names like `up` or `right` are silently ignored.
Detail pages open with focus on a search button, so never send ok straight after opening one. "Watch trailer" is the next button (right on movies, down on shows).
The add page (from a Search result) opens with focus on "Add and download", so move off it before pressing ok.
If the TV's screensaver (`DreamActivity`) is the resumed activity, send `input keyevent KEYCODE_WAKEUP` to dismiss it. `am start` doesn't.

## To-do
Jim's bug reports and feature requests are in `TODO.md`. Add new ones there.

## Layout
- `data/`: settings storage, the API client and models, `Details.kt` (full movie/series/episode records), and `Queue.kt` (groups queue records per download and works out status labels)
- `ui/`: the ViewModel and Compose screens (tabs: Search, Downloads, Movies, TV Shows, Settings).
  Movies and TV Shows have a search box above the grid that filters it (`LibraryContent.kt`).
  Selecting a poster in Movies or TV Shows opens `DetailScreen` (status, search, monitoring, quality, delete, and seasons and episodes for shows).
- `data/Release.kt` works out whether a movie is on streaming yet (the poster badges and the Search tab's "On streaming" filter).
- Trailers: `data/Trailers.kt` finds YouTube ids (TMDB, then Radarr, then a YouTube search). `ui/TrailerScreen.kt` plays them
  in a WebView with YouTube's iframe player. The WebView needs MATCH_PARENT layout params, or the video is 0px tall (sound, black screen).
