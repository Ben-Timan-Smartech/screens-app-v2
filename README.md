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

## Auth & users (Google Sign-In)

The CMS is gated by Google Sign-In, locked to four Workspace domains:
`smartechworld.com`, `smartech.buzz`, `smartechwrld.com`,
`seeyoutmrw.com`. Anyone in those domains can sign in directly — they
appear in **Users & permissions** with the **Viewer** role (read-only),
and the Owner can promote them from there. Roles: Owner (singular),
Super admin, Admin, Manager, User, Viewer, Brand partner.

To switch back to invite-only (no auto-provisioning), set the env var
`SCREENS_AUTOPROVISION_ROLE` to `off`. To raise the default role for
new sign-ups, set it to one of the role names (e.g. `user`).

Sessions live in `screens.db` (SQLite); cookies are HttpOnly,
SameSite=Lax, Secure when `SCREENS_PUBLIC_URL` starts with `https://`.

Required env vars on Cloud Run:

| Var | Value |
|---|---|
| `SCREENS_GOOGLE_CLIENT_ID` | OAuth Client ID from GCP Console (see below) |
| `SCREENS_PUBLIC_URL`       | `https://screens.smartechworld.com` |
| `SCREENS_DB_PATH`          | `/data/screens.db` (defaults to that in the Dockerfile) |
| `SCREENS_OWNER_EMAIL`      | Optional override for the Owner seed email |
| `SCREENS_OWNER_NAME`       | Optional override for the Owner display name |
| `SCREENS_AUTOPROVISION_ROLE` | Default role for self-signups (default `viewer`; set to `off` for invite-only) |

If `SCREENS_GOOGLE_CLIENT_ID` is unset, the login page shows a clear
"sign-in not configured" message instead of crashing.

### Setting up the Google OAuth client (one-time)

1. GCP Console → APIs & Services → Credentials → **Create Credentials → OAuth client ID**.
2. Application type: **Web application**.
3. Authorised JavaScript origins:
   - `https://screens.smartechworld.com`
   - `http://localhost:8765` (for local dev)
4. **No** Authorised redirect URIs needed — we use the credential / ID-token flow, not the redirect flow.
5. Copy the **Client ID** into the `SCREENS_GOOGLE_CLIENT_ID` env var.

## Persistent storage (SQLite over GCS FUSE)

`screens.db` holds users, sessions, and (next sprint) the Drive Sync
state. Cloud Run is stateless, so the file lives in a Cloud Storage
bucket mounted at `/data` via the gen2 execution environment's
Cloud Storage FUSE volume support.

### One-time bucket setup

```bash
PROJECT=screens-app-v2
BUCKET=${PROJECT}-db

gcloud storage buckets create gs://$BUCKET \
  --project=$PROJECT \
  --location=europe-west2 \
  --uniform-bucket-level-access

# Versioning gives us point-in-time recovery if the SQLite file ever
# gets corrupted — small bucket, lifecycle keeps cost negligible.
gcloud storage buckets update gs://$BUCKET --versioning

# Cloud Run's runtime service account needs read/write on the bucket.
SA=$(gcloud run services describe screens-app-v2 \
  --region=europe-west2 --format='value(spec.template.spec.serviceAccountName)')
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$SA" \
  --role="roles/storage.objectAdmin"
```

### Deploy with the FUSE volume + auth env vars

The deploy command must use `--execution-environment=gen2` (gen1
doesn't support volume mounts). Replace `<CLIENT_ID>` with the OAuth
client ID from the section above.

```bash
gcloud run deploy screens-app-v2 \
  --source=. \
  --region=europe-west2 \
  --execution-environment=gen2 \
  --min-instances=1 --max-instances=1 \
  --add-volume=name=db,type=cloud-storage,bucket=${BUCKET} \
  --add-volume-mount=volume=db,mount-path=/data \
  --set-env-vars=SCREENS_DB_PATH=/data/screens.db \
  --set-env-vars=SCREENS_GOOGLE_CLIENT_ID=<CLIENT_ID> \
  --set-env-vars=SCREENS_PUBLIC_URL=https://screens.smartechworld.com \
  --set-env-vars=SCREENS_DRIVE_BRANDS_ID=1qY7alGpc_MsI72neKPpQtrkgGUvsd9YO \
  --set-env-vars=SCREENS_DRIVE_SPLASHES_ID=1STZ9YXg154dFe4aldxp6Z3E0TFWaVN0C \
  --set-env-vars=GOOGLE_APPLICATION_CREDENTIALS=/secrets/drive-credentials.json \
  --update-secrets=/secrets/drive-credentials.json=drive-credentials:latest
```

On first boot the Owner row is seeded automatically from
`SCREENS_OWNER_EMAIL` (default `ben@smartechworld.com`). Sign in with
that Google account, then invite teammates from **Users & permissions**.

## Things that aren't shipped

- Tests — this is still scaffold-stage code
- A real `google-services.json` (only the `.example` is in the tree)
- A signing config for release builds — drop a `keystore.properties` in `player/app/` before shipping
- Drive Sync incremental rebuild — currently every sync re-walks the entire folder. SQLite tables for `videos` / `brands` / `sync_runs` + Drive `changes.list` page tokens are the next obvious step.
- Tablet auth — `/api/screens/register` and `/api/screens/heartbeat` are unauthenticated. A device API key is on the roadmap.
