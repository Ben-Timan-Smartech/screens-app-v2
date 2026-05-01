# Screens — Android player

Android player app for the Smartech Screens CMS. Runs on store tablets: registers itself against the backend, downloads the assigned playlist, plays videos via ExoPlayer on an infinite loop, and exposes a hidden staff UI for in-store content swaps.

This project is a **scaffold**. The code compiles once you have the Android SDK and a Firebase project; it does not produce a working, registered tablet until the backend (engineering brief, Phase 1–2) is up.

## What's included

- **ExoPlayer playback** (`androidx.media3`) — playlist on loop, full-bleed, immersive
- **Device API client** (Retrofit + OkHttp + kotlinx.serialization) matching the endpoints in `../02-engineering-brief.md`
- **Local video cache** with 8 GB default cap and LRU eviction
- **Firebase Cloud Messaging** receiver for `playlist.updated`, `settings.updated`, `reboot`, `cache.clear`
- **WorkManager heartbeat** — 15 min ping/settings/playlist refresh as an FCM backstop
- **Staff overlay** — four-corner unlock, PIN → brand → video → success, auto-dismiss at 15 s
- **DataStore** persistence for device token, etags, settings
- **Registration** reports RAM + screen dimensions so the backend can set the right quality tier

## What's **not** included (deliberately)

- A bundled `gradle-wrapper.jar` — Android Studio generates it on first sync
- A real `google-services.json` — you need your own Firebase project
- A signing config — add `keystore.properties` before shipping a release build
- Tests — this is a scaffold; write tests as the code solidifies
- Analytics / Sentry — hook up whatever telemetry stack you pick
- A launcher icon beyond a minimal adaptive-icon placeholder

## Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK platform 34 and build-tools 34.x
- A Firebase project with an Android app for `com.smartech.screens`

### Steps

1. Drop `google-services.json` into `app/` (rename from `.example`)
2. Open `player/` in Android Studio — it will sync Gradle and download the wrapper jar
3. Build → **Build bundle(s) / APK(s)** → **Build APK(s)**
4. Find the APK at `app/build/outputs/apk/debug/app-debug.apk`

### Command-line build

Once Android Studio has materialised `gradlew` / `gradle-wrapper.jar`:

```bash
cd player

# Default — both flavors, both build types:
./gradlew assembleDebug

# Specific flavor:
./gradlew assembleModernDebug   # Android 8+ (API 26) — adaptive icon
./gradlew assembleLegacyDebug   # Android 6+ (API 23) — PNG mipmaps for old launchers

# With a real backend baked in:
./gradlew assembleModernRelease -PapiBase=https://api.smartech.group/api -PjoinCode=SMARTECH
```

APKs land at:

```
app/build/outputs/apk/modern/debug/app-modern-debug.apk
app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
```

### Two flavors, when to use which

- **modern** (default): minSdk 26 (Android 8 Oreo). Adaptive launcher icon
  via vector drawable. The everyday build for current-gen tablets.
- **legacy**: minSdk 23 (Android 6 Marshmallow). Ships PNG mipmap icons in
  five densities. Use only on tablets you can't upgrade past Android 6/7.

To regenerate the legacy launcher PNGs after changing the brand mark, run
`python gen-legacy-icons.py` from the repo root. Pillow is the only Python
dep (`pip install pillow`).

## Install on a tablet

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or, for the kiosk flow — launch as default HOME app:
adb shell pm set-home-activity com.smartech.screens/.MainActivity
```

## Architecture quickref

```
ScreensApp (Application)
  ├── DeviceStore     — DataStore preferences: token, etags, overrides
  ├── ApiClient       — OkHttp + Retrofit, Bearer-token interceptor
  ├── VideoCache      — <filesDir>/videos, LRU eviction
  └── PlayerRepository — source of truth for UI state
       ├── ensureRegistered()
       ├── refreshPlaylist()  ← FCM push + heartbeat both trigger this
       ├── refreshSettings()
       └── ping()

MainActivity
  ├── PlayerScreen     — Compose host for ExoPlayer
  └── StaffOverlay     — corner unlock → PIN → brand → video
```

Push payloads are tiny by design; the tablet re-fetches on every push. See `ScreensFcmService.kt`.

## Known follow-ups

- **Staff override playback** — the overlay currently fires `onPick` with `{ /* TODO */ }`. Add a `/device/staff-override` endpoint to the backend and wire it here.
- **PIN hash** — `PinScreen` accepts any 4 digits. Compare against `store.staffPinHash` once the backend pushes it through `/device/settings`.
- **Device-owner mode** — true screen reboot requires DPC privileges. The current code calls `Process.killProcess()` which relies on the launcher relaunching us (works when the app is set as HOME).
- **Quality downgrade on stall** — the backend owns tier selection; the client should report `PLAYBACK_STALLED` via `/device/event` and let the server flip to `LOW_480P`. Hook this into the ExoPlayer `Player.Listener` in `PlayerController`.
- **Orientation override** — `SettingsResponse.orientation` is persisted but not yet read by the Activity. Apply it as `requestedOrientation` on config change.

## File map

```
player/
├── build.gradle.kts        — top-level plugins
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/         — gradle-wrapper.properties (jar added by Android Studio)
└── app/
    ├── build.gradle.kts    — dependencies, Compose, Media3, Firebase
    ├── proguard-rules.pro
    ├── google-services.json.example
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/            — theme, strings, colors, network config, icon
        └── java/com/smartech/screens/
            ├── ScreensApp.kt
            ├── MainActivity.kt
            ├── data/       — Models, DeviceApi, ApiClient, DeviceStore,
            │                  VideoCache, PlayerRepository
            ├── player/     — PlayerController, PlayerScreen
            ├── fcm/        — ScreensFcmService
            ├── staff/      — CornerUnlock, StaffOverlay, StaffScreens
            ├── sync/       — HeartbeatWorker
            └── util/       — DeviceInfo
```
