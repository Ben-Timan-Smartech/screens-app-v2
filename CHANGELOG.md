# Changelog

Release notes for the Screens app — both the CMS (auto-deployed to Cloud Run
on every merge to `main`) and the Android player APK (built + published by
GitHub Actions on each tagged release). See [README → Releasing](README.md#releasing)
for how versions are cut.

The matching section below is used **verbatim** as the GitHub Release body and
the in-app "what's new" the player overlay shows. Write it for Ben and the
people installing the APK, not for engineers.

Rules:
- Newest at the top.
- Header is exactly `## vX.Y.Z` (leading `v`, no date, no trailing text) — the
  release workflow keys off this format.
- Plain-English bullets: what changed and why it matters.
- One section per version; don't edit an already-released section.

---

## v0.1.3

Video downloads actually work now, plus a handful of polish.

- **Video downloads no longer get stuck in a retry loop.** The proxy
  through Cloud Run was buffering the entire Drive response into
  memory before sending the first byte, blowing past the tablet's
  10-second read timeout. Rewrote it to stream via `urllib` directly —
  first chunk now lands in milliseconds. Also stopped sending an
  approximate `Content-Length` header (the cached `sizeMb` was rounded
  to 1 decimal MB), which was making OkHttp on the player premature-EOF
  the stream and never write the file to cache.
- **Download status badges** in the staff overlay's playlist view.
  Green tick when the file is on disk, spinner while it's downloading
  or hasn't started, red ✕ if the last attempt failed.
- **Staff overlay can edit its own playlist again.** The auth layer
  added in v0.1.1 was rejecting tablet-side edits as unauthenticated;
  the server now accepts playlist + mix-splash changes for a
  registered device's own deviceId.
- **CI builds are ~30 % faster.** Release workflow runs modern + legacy
  in parallel on separate runners and gives the Kotlin compiler a
  bigger JVM heap. Manual `workflow_dispatch` runs can also skip the
  legacy flavor for ~50 % savings when iterating on a modern-only
  fleet.

## v0.1.2

Reliability fixes after the first day in production.

- **"Reboot screen" actually comes back now.** The CMS button used to
  kill the player process and rely on AlarmManager to relaunch it —
  Android 11+ cancels alarms when the scheduling process dies, so the
  player went dark. Switched to an in-place activity restart that
  resets the player loop without killing the JVM.
- **Manual "Check for updates" button** in the on-device staff
  overlay (Device admin → Actions). Forces an update poll without
  waiting for the 6-hour timer or driving to the CMS.
- **`/apk` shortcut** on the CMS host streams the latest APK directly.
  `https://screens.smartechworld.com/apk` starts the modern build
  download immediately; `/apk/legacy` does the same for Android 6/7.
  Sidesteps networks that block GitHub's release CDN.
- **`/api/library/refresh` survives container restarts.** Cloud Run
  writes `library.json` to the FUSE-mounted bucket now, with auto-sync
  on boot when the file's empty. After tonight's redeploys, the CMS
  brand library populated automatically; previously it would have
  needed a manual Drive Sync click.
- **Drive rate-limit smoothing** for `/media/<id>`. We were calling
  `drive.files.get()` once per video request — Drive started 404ing
  half of them under load. Metadata now reads from the cached
  `library.json` and only falls back to the Drive API for files we
  haven't indexed.
- **Streaming retry** on the well-known google-api-python-client
  auth-refresh race (`NoneType has no attribute 'read'`). Was making
  ~1% of video downloads fail on first try.

## v0.1.1

Self-updating + onboarding flow. Tablets can now keep themselves on the
latest build with no admin involvement, and getting a new screen running is
a one-link download instead of a copy-paste from chat.

- **Download Player APK from the login screen.** A new "Download Player
  APK" link appears below the sign-in button, visible without an account.
  Anyone setting up a tablet can grab the installer directly.
- **In-app auto-update.** The player checks `/api/release/latest` on launch
  and every 6 hours. When the server reports a newer version, it
  downloads the APK, shows a full-screen "Updating to vX.Y.Z…" dialog,
  and hands off to Android's installer. As long as builds are signed
  with the stable release keystore, the update is in-place — no data
  loss, no reinstall.
- **CMS: "Update player APK" button** on every screen's detail page.
  Triggers the same updater flow immediately rather than waiting for the
  6-hour tick. Activity log records who pushed the update.
- **Release-key signing in CI.** When the `KEYSTORE_B64` /
  `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` repo secrets are
  configured, the release workflow signs APKs with the stable key. With
  no secrets, CI falls back to debug-signed APKs (still install via
  adb, but the in-app updater can't swap them in place).
- **Server-side APK proxy.** `/api/release/download/{modern|legacy}`
  streams the latest release's APK to anyone who asks — needed because
  the source repo is private and GitHub's anonymous CDN won't serve
  private assets. Set `SCREENS_GITHUB_TOKEN` on Cloud Run.
- **Offline-resilient playback** was already in place: every video is
  pre-downloaded to local storage before it starts playing, and the
  player reads from `file://` URIs. Network drops only affect the
  refresh cycle — the current playlist keeps looping.

## v0.1.0

First versioned release. Everything that landed in the auth + persistence push
on 2026-05-08 is now bundled under one number.

- **Google Sign-In** for the CMS. Anyone with a `@smartechworld.com`,
  `@smartech.buzz`, `@smartechwrld.com`, or `@seeyoutmrw.com` email can sign in
  with their Google account.
- **Self-onboarding.** New sign-ins land as Viewer (read-only) and show up in
  Settings → Users. The Owner promotes them from there.
- **Users & permissions page.** Invite, edit role, disable, remove. Owner row
  is locked against demotion or deletion.
- **Roles:** Owner (singular), Super admin, Admin, Manager, User, Viewer, Brand
  partner. Sidebar items and API routes are gated by role from a central
  permissions matrix.
- **Persistent user store** on Cloud Storage. Users + sessions survive Cloud
  Run restarts and revision rollovers.
- **Heartbeat fix** for tablets that had saved an `http://` server URL —
  auto-upgrades to `https://` on boot so they reconnect after the Cloud Run
  deploy.
- **Live Activity log** and **CMS library auto-refresh** so changes show up
  without a hard reload.
- **TV mode polish** on the player APK: amber focus ring, BackHandler chain,
  hold-OK staff unlock gated to the player loop, dynamic HDMI resolution.
- **Drive API integration** for the cloud deploy — brand content streams
  through `/media/<drive_id>` and splash videos hydrate to a local cache on
  boot.
