# Screens App V2

In-store retail screens platform for Smartech Group. Web-based CMS for
managing playlists across stores plus an Android player APK that runs
on the in-store tablets / TV-class devices.

## What's in here

```
.
├── app/                — CMS frontend (React via Babel-standalone, no build step)
├── player/             — Android player APK source (Kotlin / Jetpack Compose)
├── brand/              — Brand assets served at /brand/ (favicons, logos)
├── serve.py            — CMS backend: HTTP server, per-screen state, command queue
├── scan-videos.py      — Drive sync: walks Brand Content/, generates library.json
├── gen-legacy-icons.py — One-off script for the legacy build flavor's launcher icons
└── open-dashboard.bat  — Convenience launcher: starts serve.py + opens the CMS in a browser
```

## Running the CMS

Requirements:
- Python 3.9+
- A folder of brand-organised MP4s mounted at `Brand Content/` (the Drive sync points here)

```bash
python serve.py
```

Then open `http://<your-ip>:5051/`. The same URL works from any tablet
on the LAN — the player APK uses it for registration and polling.

## Building the Android player

Requirements:
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17, Android SDK platform 34

```bash
cd player
./gradlew assembleModernDebug
# APK lands at app/build/outputs/apk/modern/debug/app-modern-debug.apk
```

For a build that talks to a real backend rather than the placeholder:

```bash
./gradlew assembleModernRelease -PapiBase=https://api.smartech.group/api -PjoinCode=SMARTECH
```

See `player/README.md` for the full build / install / firebase notes.

## Architecture sketch

```
┌──────────────────────┐      3 s polling      ┌─────────────────────┐
│ Tablet / TV (player) │ ───────────────────▶  │   serve.py (CMS)    │
│ ExoPlayer + Compose  │ ◀─── command queue ── │  per-screen state   │
└──────────────────────┘                       └─────────────────────┘
        ▲                                                ▲
        │ registers with /api/screens/register           │ /api/* JSON
        │                                                │
        │                                       ┌────────┴────────┐
        │                                       │  CMS web app    │
        │                                       │  (app/, browser)│
        │                                       └─────────────────┘
```

- The CMS holds per-screen playlist state and a small command queue keyed on `deviceId`.
- The player polls every 3 s for playlist revisions + queued commands; the server-persisted state is the source of truth so offline tablets just pick up edits on their next reconnect.
- Splash + brand-logo files are served directly from disk via `/splash/*`, `/media/*`, `/brand/*`.

## Staff unlock — getting into the on-device admin UI

| Device | Gesture |
|---|---|
| Touch tablet | Tap the four corners in order: top-left → top-right → bottom-right → bottom-left, within 4 s |
| Android TV / Fire TV / signage box | Hold OK / Select / Enter for ~1.5 s |

Either gesture pops the staff PIN screen.

## Things that aren't shipped

- Tests — this is still scaffold-stage code
- A real `google-services.json` (only the `.example` is in the tree)
- A signing config for release builds — drop a `keystore.properties` in `player/app/` before shipping
