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

## Cloud deploy (Cloud Run + Drive API)

The CMS backend can run two ways:

- **Local**: `python serve.py` reads media from a Google Drive for Desktop mount on `G:\`. Tablets connect over LAN.
- **Cloud**: Cloud Run container, talks to Google Drive over the Drive API via a service account. No Drive mount needed; tablets connect over the public Cloud Run URL.

### One-time GCP setup for cloud mode

1. **Service account**: GCP Console → IAM & Admin → Service Accounts → Create. Name it something like `screens-cms-drive`. No GCP roles needed.
2. **Generate JSON key**: open the service account → Keys → Add Key → JSON. Download the file.
3. **Share the Drive folders** with the service account email (looks like `screens-cms-drive@<project>.iam.gserviceaccount.com`):
   - The `Brand Content` folder — Viewer access.
   - The folder containing all the `Splash - <Brand>` subfolders — Viewer access.
4. **Find the folder IDs**: open each folder in Drive in a browser. The URL ends with `…/folders/<long_id>`. Copy each.
5. **Store the JSON key as a Secret**: GCP Console → Secret Manager → Create Secret named `drive-credentials`, paste the JSON contents.
6. **Deploy** to Cloud Run (via `gcloud run deploy` or the Console):
   - Mount the secret at `/secrets/drive-credentials.json`
   - Set env vars:
     - `GOOGLE_APPLICATION_CREDENTIALS=/secrets/drive-credentials.json`
     - `SCREENS_DRIVE_BRANDS_ID=<Brand Content folder ID>`
     - `SCREENS_DRIVE_SPLASHES_ID=<splash root folder ID>`
   - Min instances **1**, Max instances **1** (state is in-memory; pinning to one instance prevents auto-scaling from fragmenting the registry).

### How the modes differ

| | Local | Cloud |
|---|---|---|
| Brand videos | Streamed from `G:\…\Brand Content` | Streamed from Drive on demand via `/media/<drive_file_id>`. Player APK caches client-side, so it's a one-time hit per video per device. |
| Splashes | Read from `G:\…\Screens` | Downloaded from Drive into `/tmp` at server boot, then served from local disk. |
| `library.json` | Built by `python scan-videos.py` walking the filesystem | Same `scan-videos.py`, auto-detects cloud mode via `SCREENS_DRIVE_BRANDS_ID` and uses Drive API instead. Triggered on demand from the CMS Drive Sync card. |

## Things that aren't shipped

- Tests — this is still scaffold-stage code
- A real `google-services.json` (only the `.example` is in the tree)
- A signing config for release builds — drop a `keystore.properties` in `player/app/` before shipping
- Persistent state — registry / command queue / per-screen playlist all in-memory; restart wipes them. SQLite swap-in is the next obvious step.
