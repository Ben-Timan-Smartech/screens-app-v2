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

## v0.1.31

Tablet command palette no longer pops the soft keyboard.

### Why it did

v0.1.29 used a `BasicTextField` for the search input. Focusing a
real text field on Android tells the system "this view wants
text" and Android dutifully shows the IME — which on a TV-class
box (no touchscreen, but plenty of Android infrastructure) means
a full on-screen keyboard appearing over the player. Useless to a
USB-keyboard user, ugly on the screen.

### Fix

The search input is no longer a `BasicTextField`. It's a plain
`Text` that displays the current query plus a blinking amber
caret. Keystrokes land via the outer Box's `onPreviewKeyEvent`,
which reads `nativeKeyEvent.unicodeChar` to map each key to its
character (Shift + locale layout handled automatically). The Box
is `.focusable()` and grabs focus on open, so keystrokes go to
this handler instead of bleeding through to the player below.
**No IME is ever invoked**, on any device class.

Backspace deletes the last character. Modifier keys (Ctrl, Alt,
Meta) other than Shift are ignored so they don't type junk into
the query.

The visible UX is identical for the keyboard user — type, see
characters appear, press Enter — just without the OS keyboard
joining the party.

Tablet-only.

## v0.1.30

Hotfix for v0.1.29 — the code was fine, the build was not.

### Why v0.1.29 didn't ship an APK

`player/gradle/gradle-daemon-jvm.properties` had `toolchainVendor=
JETBRAINS` plus a cached foojay URL ID for the matching JDK 21.
foojay rotates those IDs server-side, and on 2026-06-16 the cached
IDs all started returning HTTP 400 from `api.foojay.io/disco/v3.0/
ids/.../redirect`. Every release build failed at the Gradle
toolchain provisioning step before compiling a line of Kotlin. v0.1.29
never produced an installable APK.

### Fix

Switched `toolchainVendor` to `ADOPTIUM` (Eclipse Temurin — the
de-facto Android JDK distribution and the one GitHub Actions
runners pre-install) and removed the cached URL pins so Gradle
re-queries foojay each build instead of trusting stale IDs. On the
runner, Temurin 21 is already on disk, so Gradle picks it up
locally without hitting foojay at all.

Same v0.1.29 payload — tablet command palette, calibration clock
shortcut, all of it — just on a build pipeline that actually
produces a binary.

Build-config only. Tablet code unchanged from v0.1.29.

## v0.1.29

Tablet-side command palette — press `/` on a USB keyboard plugged
into the box and a Linear/Vercel-style command launcher appears
over the player, same shortcut as the CMS.

### Why this exists

The Sumvision Cyclone (and most cheap signage boxes) doesn't ship
with a remote. Operators show up with a USB keyboard. Up to now
the only way to admin the box was the hold-OK gesture from
v0.1.0, plus a few keyboard shortcuts. v0.1.29 makes `/` the
universal "do something" key: same muscle memory as the CMS
palette, works while content is playing, no need to hunt for the
PIN-and-overlay path for safe actions.

### The catalogue

Three commands in the MVP:

| Command | Needs PIN? | What it does |
|---|---|---|
| **Refresh playlist now** | No | Pings `refreshNow()` — re-poll the server immediately. Useful when a CMS push is in flight and the box is in Slow mode (10-min polls). |
| **Show calibration clock (60 s)** | No | Triggers the v0.1.15 calibration overlay locally by POSTing to the screen's own `/api/sync-groups/<deviceId>/calibrate` endpoint. Giant ticking server-corrected clock for 60 s. |
| **Open device admin** | Yes | Fires the staff-unlock bus, same as hold-OK. PIN screen comes up; full admin available after. |

PIN-gated commands carry a small monospaced `needs PIN` chip
next to the label so operators see the escalation before they
commit. Safe commands run inline and close the palette.

### Interaction

- **Open:** press `/` on a USB keyboard (any time, including
  while video is playing). Also accepts the numpad `/`.
- **Filter:** type into the input. Word-AND matching across
  label + hint, same as the CMS palette.
- **Navigate:** ↑ / ↓ arrow keys.
- **Run:** ↵ on the highlighted command.
- **Dismiss:** Esc, or tap the dark scrim outside the card.

The hotkey is captured in `MainActivity.dispatchKeyEvent` with
`return true` so the keystroke doesn't bleed into whatever view
has focus underneath. Suppressed while the staff overlay is up
(so `/` typed into the PIN screen or admin fields behaves
normally as a character).

### Implementation note

`PlayerRepository.triggerLocalCalibration` is a thin wrapper
around the existing server endpoint — no new server code needed.
The server already accepts a lone deviceId as a one-screen
"group" (added in v0.1.15 for exactly this kind of single-screen
diagnostic). The tablet POSTs, the response writes
`calibrateUntilMs` to its per-screen record, the next poll
surfaces it, and `CalibrationOverlay` (also v0.1.15) renders.

Tablet-only release.

## v0.1.28

Command palette hotkey now fires on the on-tablet preview page
and inside the library's video preview modal.

### Why it wasn't firing

v0.1.27 listened for `/` at window level in the **bubble** phase.
That works on most pages, but on pages with a focused `<video
controls>` element (the Content Library preview modal) or with
their own page-level keydown handler (the on-tablet preview's
stage router), the keystroke could be consumed before bubbling
to the window. Firefox's "find as you type" can also swallow `/`
on a non-text-input focus.

### Fix

The listener moves to `document` in the **capture** phase
(`addEventListener('keydown', onKey, true)`). Capture runs from
the root down before any bubbling handler sees the event, so
nothing on the page can swallow `/` first. Plus the handler now
calls both `preventDefault()` and `stopPropagation()` when it
fires the palette, so subsequent handlers don't see a stray `/`
either (and Firefox doesn't open its find bar).

Same suppression rules apply — typing `/` into a real text input
(input / textarea / select / contenteditable) still types `/`
into the field, exactly as you'd expect.

CMS-only release.

## v0.1.27

Slash-key command palette in the CMS. Press `/` (or Cmd/Ctrl-K)
from any page, get a Linear/Vercel-style modal with filterable
commands, argument autocomplete, and an inline help panel.

### How it works

- **Open** — `/` or Cmd/Ctrl-K. Suppressed while focus is in
  another text input so typing `/foo` into a search box doesn't
  pop the palette.
- **Filter** — type into the search input. Word-AND matching across
  label, hint, description, and keywords ("open scr" → "Open a
  screen", no need for a contiguous substring).
- **Navigate** — ↑ / ↓ arrow keys, hover with mouse, or just keep
  typing to narrow.
- **Execute** — ↵. For commands that need an argument the modal
  switches into a second stage; for everything else it runs and
  closes.
- **Dismiss** — Esc, or click the dark scrim outside the panel.

### Argument autocomplete (the second stage)

Some commands need a target — which screen to refresh, which sync
group to calibrate, which video to open. Pressing ↵ on those moves
the palette into a second stage that lists live options pulled from
`/api/screens` or the in-memory library:

- **Open a screen…** → fleet list, filterable by name / store /
  screen code.
- **Refresh a screen now…** → same, runs the `refresh` command
  on the picked deviceId.
- **Update player APK on a screen…** → same, triggers the in-app
  updater.
- **Reboot a screen…** → same, fires the `reboot` command.
- **Calibrate a sync group…** → lists current sync groups with
  member counts; running it fires the 60-second calibration
  overlay on every member.
- **Open a video…** → library entries, filterable by brand /
  product / title; opens the preview modal.

Backspace at empty input backs from the arg stage to the command
stage so you can recover from a wrong pick without re-opening.

### Inline help panel

The bottom of the modal shows the highlighted command's full
description plus what it needs:

> **Calibrate a sync group…**
> Puts every screen in the chosen sync group into calibration
> mode for 60 s…
>
> **Needs:** a sync group — you'll pick after pressing ↵.

So you know what a command does and what target it'll act on
**before** you commit. The "needs <type>" tag on each row in the
list is a quick at-a-glance signal that the command is a
two-stage one.

### Permissions-gated

Commands are filtered against the signed-in user's permissions
matrix (same `can()` helper the sidebar uses). A Viewer doesn't
see destructive actions like Reboot, Update APK, or Calibrate.

### Out of scope

CMS-only release. Tablets unaffected. Action listeners on the
target screens (e.g. Settings → run Drive Sync) are still
event-based — most commands navigate + dispatch; the receiving
screen needs to listen for the dispatched event to do its part.
Upload-panel + library-refresh listeners are wired in this
release; Settings hooks for Drive Sync trigger remain a small
follow-up.

## v0.1.26

"On a cold start there is no video playing but there is something
in the playlist." — yes, because the splash was looping while the
content downloaded. The admin showed items because `intendedPlaylist`
is hydrated from the cached playlist record, but ExoPlayer was
playing the bundled splash until enough items had landed on disk
for `publish()` to flip to `State.Playing`.

### Loading content overlay

New `ColdStartLoadingOverlay` renders on top of the splash when:

- playback isn't yet `State.Playing`, AND
- `intendedPlaylist` is non-empty, AND
- at least one download is in flight

Shows in the lower-third of the screen as a dark pill:

> **LOADING CONTENT**
>
> 1 of 4 items ready
>
> ████████░░░░░░░░  42%
>
> `42%   5.4 / 12.7 MB   1.8 MB/s`

Progress is the sum of downloaded bytes / sum of total bytes across
every in-flight download — a real measure, not a spinner. Speed
shown when bytes-per-second is above the noise floor. Auto-hides
the moment the first poll publishes `State.Playing`.

Sits in the lower-third so the branded splash still dominates and
we're not replacing brand content with a dialog.

### Downloads start earlier on cold start

`rehydrateFromCache` now triggers `cache.ensure` for any items that
aren't on disk yet, in a background coroutine right after the
playlist is restored. Before this, downloads had to wait for the
live-sync coroutine to finish registration + settings + the first
state poll (~5 s on average). Kicking them off from rehydrate saves
those few seconds on a fresh install or after a "Clear cache"
command.

Tablet-only.

## v0.1.25

Fills in the gap between "everything is fine" and "the app died" —
warnings and errors that happen during normal operation now reach
the server so an engineer can read them remotely.

### What was missing

`/api/crashes` only captures **uncaught exceptions**. Anything the
app catches and logs as a warning (decoder fallback firing, drift
tick skipped, heavy-video filter trip, network blip swallowed by
`runCatching`) stayed on the tablet's in-memory `LogBuffer` and
only surfaced in the on-device Recent activity panel. If the
tablet wasn't right next to you, you couldn't see them — which
made diagnosing "it kinda crashed once" reports painful.

### The new pipeline

- `LogBuffer` now keeps a monotonic sequence number per entry. A
  new `drainSinceSeq(cursor, minLevel)` method returns everything
  newer than the cursor at-or-above the given level.
- `PlayerRepository.shipRecentWarningsIfPending()` ranges over
  `Level.W` and `Level.E` entries since the last shipped cursor
  and POSTs them as a batch to a new `/api/logs` endpoint.
- Hooked into the existing heartbeat loop (10 s cadence) — same
  coroutine, no new timer. No-op when there's nothing new.
- On HTTP success the cursor advances; on failure it stays put
  so the next tick retries the same range. Buffer-trim guard
  means at most the last 100 entries are recoverable, which is
  fine given 10 s cadence.

### Server side

- `POST /api/logs` accepts `{deviceId, appVersion, entries: [{time,
  level, tag, message, cause?}]}`. Auth-free (same reasoning as
  `/api/crashes` — a tablet shipping diagnostics may not have a
  CMS session). Appends to `<logs_dir>/<deviceId>.jsonl`.
- `GET /api/logs` returns the newest entries across all devices,
  or `?deviceId=X` for a single tablet. Supports `?limit=N`
  (default 200, max 2000). Gated on `activity.view` like
  `/api/crashes`.
- Log files auto-trim above 2 MB so a flapping tablet can't fill
  disk on Cloud Run.

### What I can now read

```
GET /api/logs?deviceId=<x>
```

returns the timeline of warnings and errors as they're shipped
from the tablet. Pre-crash signal (decoder fallback warnings,
guarded array-bounds bail-outs, bitrate-filter skips) and
non-fatal noise now both surface. Together with `/api/crashes`
this completes the diagnostic loop — read the logs, ship a fix,
no waiting for the next uncaught exception.

## v0.1.24

Squeeze a bit more out of ExoPlayer on low-spec hardware. Pairs
with v0.1.23 — the bitrate filter blocks the obvious offenders;
this release helps with the borderline cases that get through but
stress the decoder.

### Two tier-aware knobs

`PlayerController.buildExoPlayer(context)` now reads
`DeviceInfo.decoderTierFor(ramMb)` and configures ExoPlayer
accordingly:

- **LoadControl buffer sizes.** Default Media3 buffers 50 s / 50 s
  of media in flight — for a 10 Mbps clip that's ~60 MB of RAM on
  top of decoded-frame buffers. On a 1 GB Amlogic box the OS
  starts swapping background tasks. Low-tier devices now use
  10 s min / 20 s max (peak ~25 MB) and that's plenty for
  local-file playback — we already have the file on disk, there's
  no network-rebuffer risk to insure against. Medium / high keep
  generous 30 s / 60 s buffers.

- **Decoder fallback.** `DefaultRenderersFactory
  .setEnableDecoderFallback(true)` tells the renderer to try the
  next codec instance — often the software fallback — if the
  primary one crashes or refuses init. Slower than hardware
  decode but it keeps the video on-screen instead of going black.
  Enabled on every tier; only fires when the hardware path
  genuinely fails, so capable hardware pays nothing.

### And one always-on

`setHandleAudioBecomingNoisy(false)` on the ExoPlayer builder.
Default behaviour is to pause when "audio is about to be noisy"
(headphones unplugged, Bluetooth disconnect, etc.) — irrelevant
for a kiosk that's the only thing playing, and rare devices
spuriously fire the event when a phone-call ringtone arrives over
Bluetooth, which would auto-pause the player. Disabling it on the
kiosk path costs nothing and removes a class of "the screen just
stopped" stories.

### What this doesn't do

- No magic 4K-on-1GB-RAM playback. The bitrate filter from v0.1.23
  is still the line of defence against content that's truly
  beyond the device.
- No software-fallback for the splash. Splash is a low-bitrate
  bundled clip; decoder fallback only matters when the primary
  path fails.
- No tier-aware track selection. Single-bitrate MP4s — there's
  no rendition to pick.

Tablet-only release.

## v0.1.23

Per-device bitrate guard — videos that would overload a low-spec
box now get skipped with a visible warning instead of crashing
playback.

### Why

A 202 MB video on a TX3 Mini took the player down. The Mini is a
1 GB Amlogic S905W; its hardware H.264 decoder isn't built to chew
through that kind of bitrate. The previous "queue everything and
hope" model meant one badly-encoded source could kill an entire
screen.

### Decoder tier per device

`DeviceInfo.snapshot()` now buckets the host into a rough class
based on installed RAM:

- **low** — <1.5 GB (TX3 Mini, generic Android TV sticks)
- **medium** — 1.5–3 GB (most retail tablets)
- **high** — 3 GB+ (Pixel-class, capable Fire TVs)

…and pairs each tier with a safe per-item bitrate ceiling:

- low → 10 Mbps  (covers compressed 1080p H.264)
- medium → 25 Mbps  (high-bitrate 1080p sources)
- high → 80 Mbps  (4K + visually-lossless masters)

### The guard itself

`PlayerRepository.refreshLivePlaylist` now computes
`bitrate_Mbps = sizeMb * 8 / durationSec` for every incoming
library item. Anything that exceeds the device's safe ceiling is
**dropped from the playlist** before the download attempt — no
bandwidth wasted on a file we can't play. Each skip emits a
`LogBuffer.w()` entry that surfaces in the v0.1.22 Recent activity
viewer with the message:

> Skipped heavy video 'XYZ' — 53.9 Mbps exceeds 10 Mbps safe
> ceiling for low-tier device (RAM 1024 MB). Compress the source
> or push to a higher-spec screen.

Items without size or duration metadata pass through — we'd rather
attempt-and-watchdog than refuse-and-blank on partial info.

### Heartbeat now reports tier

`decoderTier` and `safeBitrateMbps` are sent up with each
heartbeat. The CMS now has enough to warn at push time — "this
video is 35 Mbps and the target screen is a low-tier box" — once
the corresponding UI lands in a follow-up release.

Tablet-side change only (server just stashes the new heartbeat
fields). No new endpoint.

## v0.1.22

Device admin → Recent activity is now collapsible, with a filter
viewer.

### What changed

Recent activity used to be a tall card pinned to the top of the
right pane. After v0.1.21 started shipping crash reports + the
v0.1.18 drift-skip logs into the same buffer, the panel grew long
enough that D-pad operators had to traverse 50+ rows to reach the
Reboot / Reinitialise actions below — exactly what the operator
flagged.

v0.1.22 turns the panel into a **single focusable preview card**:

- One press of DOWN on the remote scrolls past the whole panel to
  the next card. No more entry-by-entry traversal.
- The preview shows three coloured count pills (Errors, Warnings,
  Info), the latest entry as a one-line teaser, and a "Tap to
  browse all N events" hint.

### Full viewer with filter chips

Tapping the card (or pressing Enter on TV) opens a full-screen
viewer with a dark scrim and a centred Bone-coloured card holding:

- **Filter chips** — `All / Errors / Warnings / Info`. Selected
  chip pulls the level's accent colour as background; unselected
  is a bordered ghost button. The counts on each chip update live
  with the log.
- **Scrollable LazyColumn** of filtered entries. Row backgrounds
  + the carry-over no-op clickable pattern from v0.1.15 keep
  D-pad scroll working on TV.
- **Close** button + Back key both dismiss; tapping outside the
  card on the scrim also closes.

When a filter is active and the matching log is empty, the empty
state copy adapts ("No errors recorded.", "No warnings recorded.",
etc.) instead of the generic "no activity yet" text.

### Colour palette stays consistent

The chip + dot palette is the same one `levelColor()` has used
since v0.1.0:

- Error → red `#A63824`
- Warning → amber `#E8A33D`
- Info → green `#3D8C4B`

So a count-pill in the preview, a dot next to an entry, and a
filter chip in the viewer all signal the same level the same way.

Tablet-only release.

## v0.1.21

The "delete a video and the tablet falls over" fix, plus the
plumbing to catch the next surprise crash without driving to the
device with adb.

### Why it crashed

Inside `PlayerController`, two functions index into `state.itemIds`
and `state.itemDurationsMs` — the parallel lists describing the
current sync-group loop. When staff removed an item from the
tablet's playlist editor, the local state could briefly fall out
of sync with ExoPlayer's queue while the new playlist propagated.
A drift tick or transition listener that happened to fire in that
window threw `IndexOutOfBoundsException` and killed the activity.

### Defensive guards

Both `snapToGroupExpectedItem` and `correctDriftInCurrentItem` are
now wrapped in a try/catch that logs the exception and skips a
tick rather than propagating up through the ExoPlayer listener
callback. An invariant check at the top of each — `itemIds.size ==
itemDurationsMs.size` and `!isEmpty()` — bails early when the
state is obviously mid-update. The next anchor refresh or
transition restores the loop.

### Crash reporter — see the next one before you ship the fix

New `CrashReporter` (tablet) installs a `Thread.setDefault
UncaughtExceptionHandler` in `ScreensApp.onCreate()`. Anything that
throws past every catch in the app is written to
`<filesDir>/crashes/<timestamp>.json` with the stack trace, app
version, device model, last 40 log-buffer entries, and the
deviceId / screenCode. The process then dies the way Android
expects — we don't try to keep going.

On the next launch, `PlayerRepository.drainCrashesIfPending()`
ships every spooled file to the new `POST /api/crashes` endpoint;
files are deleted on a 2xx and retained on failure so a temporary
network drop doesn't lose the report.

Server-side:
- `POST /api/crashes` writes the record to `/data/crashes/
  <deviceId>-<timeMs>.json`, with a 500-file cap so a runaway
  tablet can't fill the disk. Logs to the activity feed as a
  red-toned event.
- `GET /api/crashes` returns a 200-item summary list (newest
  first). Gated on `activity.view`.
- `GET /api/crashes?file=<name>` returns the full record
  including the stack and recent log.

This is the loop the operator asked for: a crash happens, the
tablet ships it on the next boot, the engineer reads it via
`curl /api/crashes` (or the CMS once the viewer page lands)
**before** building the next release.

### Why not Crashlytics / Sentry

The CMS is already on Cloud Run on the same host, so one endpoint
saves a third-party setup, a separate auth boundary, and the
question of whether retail networks allow `sentry.io` (they often
don't). The crash dataset is small and the read flow is just
JSON-over-HTTPS.

## v0.1.20

Real in-CMS video upload — finally replacing the v0.1.6 "Coming
soon" placeholder.

### What ships

- **Server**: new `POST /api/library/upload` multipart endpoint.
  Accepts a video file plus `brand`, optional `title`, optional
  `product`, optional `durationSec`. Saves the file to a writable
  `UPLOADS_DIR` (defaults to `/data/uploads` on Cloud Run, sibling
  of `library.json`), appends an entry to `library.json`, returns
  the new record. A new `/uploaded/<file>` URL prefix serves the
  saved video back to the player with the same range-streaming
  path `/media/` already uses, so the tablet sees no difference
  from a Drive-synced asset.

- **UI**: the Content Library's "Upload content" button now opens
  a real form — file picker with drag-and-drop, brand dropdown
  (sourced from the live library, with a "type a new brand" escape
  hatch), title pre-filled from the filename, optional product
  field. Submits via XHR so the progress bar tracks bytes-on-the-
  wire, not just request lifecycle.

- **Refresh — optimistic, no round-trip.** The upload response
  already contains the new library entry, so the panel hands it
  straight to `useLibrary` via a `library-refresh` `CustomEvent`
  with `detail.video`. The hook pushes the row into `MOCK_VIDEOS`
  and bumps the version counter — the grid re-renders **inside
  the same tick** as the upload completing. No `/api/library`
  round-trip on the critical "I just uploaded" path; the next
  interval tick reconciles with the server in the background.
  Previously the panel dispatched a bare event and forced a
  full re-fetch — perceptibly slow on a 450 kB library payload.

- **Library polling now gzipped.** `/api/library` responses are
  ~450 kB raw on a typical fleet, and every open CMS tab polls
  this once a minute. `_send_json` now gzips bodies over 4 kB
  when the client advertises `Accept-Encoding: gzip`. Drops the
  wire payload to ~80 kB, with no client change needed — the
  browser decompresses transparently. Smaller responses skip
  compression so per-response CPU is unchanged.

### Limits + safety

- **1 GB cap** on the multipart body. Above this the server
  returns 413 rather than reading into RAM. Single-instance Cloud
  Run has 4 GB.
- **Extension allow-list**: `mp4`, `mov`, `m4v`, `webm`, `mkv`.
  Anything else gets a 415 before any bytes are written to disk.
- **Filename safety**: title is slugified, the on-disk filename
  is `<upload-id>-<slug>.<ext>`. No traversal, no overwrite of
  existing files.
- **Atomic library write**: same write-tmp-then-rename pattern as
  the rest of the per-screen state, so a crash mid-write can't
  corrupt `library.json`.
- **Permission-gated**: same `library.sync` permission as Drive
  Sync — Viewer / Brand-partner roles can't upload.

### What it does NOT do (yet)

- No transcoding. The file you upload is the file that plays. If
  it's a 4K HEVC, the tablet has to decode 4K HEVC.
- No thumbnail. Library rows show a generic icon for uploaded
  videos until the first poster image is wired.
- No duration probe. Defaults to 15 s; CMS or tablet can edit if
  needed.

Tablets are unaffected by this release — the upload path is
entirely server + web admin. Existing v0.1.19 (or earlier)
tablets pick up uploaded content the same way they pick up
Drive-synced content: through the next `/api/library` poll.

## v0.1.19

Three small fixes off the back of an event-day install on a
Sumvision Cyclone.

### Admin panel hydrates from cached playlist on cold boot

The player has rehydrated `lastPlaylist` from disk on launch since
v0.1.5 — so a cold boot offline still loops the last-known content
instead of dropping to splash. But the staff overlay's playlist
view read from a separate `intendedPlaylist` flow (the "what the
server says SHOULD be on this screen" signal), and that flow was
only populated by live polls. After a cold boot with no network,
the screen looped the right content but the admin showed "No
videos in the playlist" until the operator hit Refresh. v0.1.19
makes `rehydrateFromCache` populate `intendedPlaylist` from the
restored playlist too, so what you see on screen and what you see
in the admin always agree.

### Staff overlay contrast

Secondary text (timestamps, sub-labels, log entries) used a
mid-grey (#6E6B62) that read at ~5:1 against the Bone background
— passes WCAG AA but felt washed out on cheap HDMI displays viewed
from across a room. Dropped to #3A3832 (~11:1, comfortably AAA)
and bumped border colour from #E2DED3 to #B8B1A0 so card edges
read on TVs with poor colour reproduction. Primary text (Ink) is
unchanged so the secondary-vs-primary hierarchy still holds.

### Keyboard support — Enter, not Space

A USB keyboard plugged into a generic Android media box can now
unlock the staff overlay via **hold Enter (or NumpadEnter) for
~1.5 seconds** — same gesture as the TV-remote hold-OK. Enter was
already in the OK-like keycode list since v0.1.0; v0.1.19 adds
the explicit comment that this is the supported path. Space is
intentionally *not* a second unlock key — Enter is the standard
"OK" on a keyboard, and giving two keys for the same gesture
invites accidental unlocks. Once inside the overlay, arrow keys
and Tab navigate via Compose's default focus traversal; the amber
`TvFocusIndication` ring follows focus regardless of input device.

Tablet-only release.

## v0.1.18

Hotfix for v0.1.17. Videos were getting cut short on the way to the
next item — visible as "the video isn't playing fully."

### Why

v0.1.17's drift-correction nudged playback speed to 1.03× when a
tablet was behind, which over a 15 s item finished it ~440 ms
before the natural duration. When ExoPlayer transitioned early into
the next item, the math said "you should still be at the end of
the previous item" and `snapToGroupExpectedItem` seeked backward
to replay the tail. User-visible result: the last fraction of
each video looped weirdly before the next one started, or the
video appeared to skip its ending.

### Fix

Three changes, each contributing:

1. **Gentler rate-control range.** The nudge is now ±1% instead of
   ±3%. A 15 s item at 1.01× finishes ~150 ms early — well under
   the boundary-guard tolerance below, so transitions stay clean.
   Catching up a 300 ms drift takes ~30 s instead of ~10 s, which
   is fine: drift never grows fast enough for this to matter.

2. **Skip rate adjustment near boundaries.** No nudging in the last
   1.5 s of an item or the first 500 ms after a transition. The
   late zone protects against early-finish overshoot; the early
   zone protects against noisy `currentPosition` reads while
   `seekTo` is still settling the decoder.

3. **Never snap backward across an item boundary.** The on-transition
   snap (`snapToGroupExpectedItem`) and the in-item drift seek now
   both refuse to seek to a *previous* item. If wall-clock thinks
   we should still be on the prior video but we've already
   naturally transitioned, we wait for wall-clock to catch up
   rather than replay the tail. Force-mode (epoch re-anchor) still
   overrides this — that path is explicitly meant to jump anywhere.

Tablet-only change. Server is unchanged.

## v0.1.17

The piece the previous sync releases left on the table: closing the
drift that happens **inside** a video, not just between items.

### Why the clocks matched but the videos didn't

v0.1.13's NTP-style clock sync nailed the wall-clock — the
calibration overlay shows two tablets tick on the same fractional
second. v0.1.16 made the loop epoch stable across polls. But the
videos themselves still drifted. The reason: cheap H.264 decoders
on TX3-class boxes don't pace at exactly 1.000× real-time. They
sit somewhere in a 0.5–1% band, and the band differs between
two physically identical boxes. After 15 seconds of a video, two
tablets that started together end up 75–150 ms apart — and the
v0.1.12 "snap only at item transitions" model can't see that drift
because it only checks at boundaries.

### Rate-control drift correction

The tablet now samples its own playback position twice a second and
compares it to the math-expected position. The action depends on
the gap:

- **< 50 ms** — leave it alone. Inside the perceptual floor.
- **50 ms – 2 s** — nudge `setPlaybackParameters(speed=1.03)` or
  `0.97` until the gap closes. The 3% nudge invisibly closes a
  100 ms gap in about 3 seconds, a 1 s gap in about 33 seconds.
  No seek, no buffer flash, no audio pitch artefact for the
  muted-by-default case.
- **> 2 s** — last-resort seek. Rare; only fires when transition
  timing has diverged by more than the rate-control range can
  recover. Resets the speed back to 1.0× afterwards.

The rate also resets to 1.0× at every item transition, so a
nudge from the previous video doesn't carry over into the next
one mid-correction.

### Effect

Sustained drift between two screens in a group stays bounded
within ±50 ms, regardless of how long the loop has been running.
The visible result is that videos that pass through identical
frames at identical moments stay tracking — the side-by-side
comparison that v0.1.13's clocks already passed now passes for
playback too.

## v0.1.16

Hotfix for v0.1.15.

### Coordinated-start fired on every poll instead of just pushes

v0.1.15's `_group_loop_epoch` reset the loop anchor whenever the
current screen's revision didn't match the last revision recorded
on the group. The intent was "the playlist changed — start fresh."
The bug: the playlist-push fan-out increments each group member's
revision counter independently rather than syncing them to a
shared value, so two screens that joined the group at different
times stay out of step on their revision counters forever. Every
poll from screen A bumped the group's recorded `lastRevision` to
A's value; the next poll from screen B saw a mismatch and reset
the epoch to `now + 5 s`; the next poll from A saw the mismatch
flipped back, reset again. Each tablet hit the coordinated-start
pause on its own poll cadence — visible as both screens pausing
at staggered moments, exactly as reported.

Fix moves the reset into the playlist-push endpoint where it
actually belongs. `_group_loop_epoch` now only initialises a fresh
record on first sight of a group (anchored to `now`, no pause),
and the explicit `_reset_group_loop_epoch` call from the playlist
endpoint anchors at `now + COORDINATED_START_DELAY_SEC` so every
member's tablet pauses-and-resumes together on a real content
change.

Server-only change. Tablets running v0.1.13–v0.1.15 pick up the
fix the moment Cloud Run finishes redeploying — no APK update
required.

## v0.1.15

Two new ways to make sync trustworthy, plus a long-standing
Android-TV bug fixed.

### Calibrate button — visually verify clock sync

The fundamental sync question is: do all the tablets in a group
agree on what time it is right now? v0.1.13 fixed the maths
(latency-corrected NTP offset), but you couldn't actually *see*
whether two tablets agreed. The new **Calibrate screens** button on
the Sync group card lights up every group member with a giant
ticking server-corrected clock for 60 seconds. Stand in front of
two screens — if the digits match to the same fractional second,
clock sync is working and any remaining drift in real playback is a
content / queue issue, not a clock issue. Also works on a single
screen (eye-check against your watch).

Server-side it's a new `POST /api/sync-groups/<id>/calibrate`
endpoint that sets a per-screen `calibrateUntilMs` cutoff and
queues a refresh command so the tablet picks it up on the very next
poll instead of waiting up to the poll interval. The tablet's new
`CalibrationOverlay` composable renders the corrected wall-clock in
huge digits + a smaller ms tail; it auto-hides the moment the
corrected clock passes the cutoff.

### Coordinated start — playlists now actually start together

When a playlist was pushed to a sync group, the server reset the
loop epoch to `now`. The first tablet to poll saw the new epoch
and started immediately; the second tablet polled 10–60 seconds
later, saw the same epoch, and instantly snap-seeked to "wherever
in the loop the math says you should be by now." That's a
staircase, not a synchronised start.

v0.1.15 anchors the epoch 5 seconds in the future and the tablet
treats a future epoch as a coordinated-start signal: seek to
(item 0, position 0), pause, and resume at the exact wall-clock
instant. Every member of the group does the same thing, so when
wall-clock catches up to the epoch they all start frame-0
simultaneously. No staircase. Same flow whether the push originates
from the CMS or the tablet's staff overlay — the server-side reset
is the only thing that changed.

### Android TV — Recent activity is finally visible

The Recent activity log inside Device admin → right pane never
showed on Android TV / TX3-class boxes. The right pane is a
`LazyColumn`; on a TV remote you scroll it by D-padding focus to a
child below the fold, but the log entry rows were plain `Text`
with no focusable modifier, so D-pad couldn't traverse into them
and the panel was invisible. Two fixes:
- Hoisted the panel to the top of the right pane so it's above the
  fold on first render.
- Switched the entries to a bounded LazyColumn whose rows are
  `Modifier.clickable {}` (no-op), giving D-pad a focus target on
  every row. The amber `TvFocusIndication` border highlights which
  entry is being read; touch users see no change.

## v0.1.14

Two small features off the back of the v0.1.13 install on the TX3
Mini: see the running build at a glance, and override the HDMI
output when the box undershoots its capability.

### Build number on the on-tablet admin

The on-tablet "What's on this screen" page (the first stage after PIN
entry) now shows the running version as a small monospaced footer in
the dark left rail — e.g. `v0.1.14`. Comes straight from
`BuildConfig.VERSION_NAME`, which is driven by the top-level
`VERSION` file at build time, so the number bumps automatically on
every release without a manual edit. Lets staff confirm a
just-installed update without leaving the playlist screen ("did the
new resolution picker land?").

### CMS-side display resolution override

Cheap Android boxes ship with HDMI output fixed to a single mode at
boot — the TX3 Mini, for example, comes up at 720p even when the
panel and the box both support 1080p. The previous device-info
heuristic faithfully reported 720p (which was true: the active mode
was 720p) but with no way to flip it without plugging a keyboard
into the box.

New flow:
- **Tablet enumerates supported modes** on every heartbeat using
  `Display.getSupportedModes()` and reports them as
  `[{id, w, h, hz}, ...]`, plus the currently active mode id.
- **CMS Screen detail page** shows a new "Display resolution" card
  listing every supported mode + an "Auto" row that means "don't
  touch the box". Picking a row writes the chosen mode id to a new
  `displayMode` per-screen field via
  `POST /api/screens/<id>/display-mode`.
- **Server echoes** `displayMode` in `/api/state` so the tablet sees
  changes on its next poll. The endpoint accepts a self-edit from
  the tablet too, matching the pattern the other per-screen toggles
  use.
- **Tablet applies** the override by setting
  `Window.LayoutParams.preferredDisplayModeId` to the requested mode
  id. Android then asks the HDMI sink to switch to that mode at the
  next surface attach. Validation against `supportedModes` happens
  on the tablet itself — a stale id (e.g. cable swapped between
  CMS-push and tablet-apply) just clears the preference rather than
  locking the box into an unrenderable state.
- The card auto-hides on screens whose tablet hasn't reported
  supportedModes yet (older app, or a device whose Display API
  doesn't expose them).

### No state migration

`displayMode` defaults to `null` (auto) for every existing screen,
and `_ensure_screen_state` back-fills the field on read — so a
rolling deploy doesn't touch already-persisted records until the
operator picks a mode.

## v0.1.13

Sync groups, again — closing the last ~1 second of drift.

### The remaining drift was network latency, not clock skew

v0.1.12 moved sync math to the tablet but still measured server time
naively: take `serverNowMs` from the response, subtract
`System.currentTimeMillis()` once, call that the offset. The problem
is that the server stamps `serverNowMs` *before* the response is
sent — by the time the tablet reads its own clock to compare, the
response has already spent ~50–500 ms in transit. All of that
one-way latency was being charged to "clock offset," so two tablets
on different network paths (one on Wi-Fi, one on Ethernet; one
closer to the CDN edge; whatever) computed offsets that disagreed
by the difference of their one-way latencies. Result: tablets that
should have been frame-locked drifted apart by roughly RTT/2 — the
~1 second the user was seeing.

### NTP-style two-timestamp sync

The tablet now records `t1` immediately before the request goes out
and `t4` immediately after the response body is read, alongside
`t3 = serverNowMs` from the body. Round-trip time is `t4 - t1`, and
under the symmetric-latency assumption the server's actual clock at
the moment of `t4` was `t3 + RTT/2`. So:

```
offset = (t3 + RTT/2) - t4
```

That removes the one-way bias instead of pretending it doesn't
exist.

### Best-of-N smoothing

Single-sample offsets still vary with whatever the network was
doing at the instant of the poll — a Wi-Fi retransmit, a GC pause
on the server, a momentarily-congested AP. The tablet keeps a
rolling window of the last 8 samples and uses the one with the
**smallest RTT** as the canonical offset. That's the classic NTP
heuristic: a low-RTT sample tightens the symmetric-latency
assumption (worst-case offset error is ±RTT/2), so it's the most
trustworthy sample we have. Outliers are simply ignored until
something better arrives.

### Server didn't change

`/api/state` already returned `serverNowMs`. No new endpoints, no
new fields — the entire fix is on the tablet, in
`PlayerRepository.ClockSync`. Older tablets keep working with the
v0.1.12 single-sample math; only v0.1.13+ devices get the bias
removed.

### Diagnostics

One log line per offset change shows `offset`, `best-rtt`, and the
sample count. RTT under ~80 ms on Wi-Fi is healthy; consistently
higher RTT points at a network problem rather than a sync problem,
and the log makes that distinction visible without scraping packet
captures.

## v0.1.12

Sync groups, third time. Architectural simplification + a guard against
the most common way to break sync by accident.

### Sync math moved off the server

Up to v0.1.11, every poll the server computed "this tablet should be on
item X at position Y right now" and sent it. The tablet then seeked
ExoPlayer if its actual position diverged. The seek was visible (a
buffer flash on cheap Android TV boxes), and corrections only happened
at the poll cadence — once a minute in Normal mode, once every 10
minutes in Slow mode.

v0.1.12 inverts the flow:
- Server sends just the loop epoch (`loopStartedAtMs`) and the server
  clock (already in `serverNowMs`).
- Tablet computes locally on every `onMediaItemTransition`: "given the
  current wall-clock and the loop epoch, which item should I be on?
  Snap to it now, while ExoPlayer is changing items anyway."
- No more mid-item seeks. The corrective jump happens at item
  boundaries, where ExoPlayer is already swapping content — invisible.
- Sync quality is independent of poll frequency. Slow mode (10 min
  polls) syncs as tightly as Fast mode (10 s).

Net effect: visible sync improves dramatically. Two screens in the
same group transition between items within a frame or two of each
other. Mid-item drift is sub-second by the time the next transition
arrives.

### Content-match guard

Sync is meaningless when the two screens are playing different
playlists — they'd land on the same offset but show different videos.
The picker now compares item IDs in order before joining:
- **Match** (including both empty) → joins silently.
- **Mismatch** → opens a "Different content — replace it?" modal.
  Confirming pushes this screen's playlist to the candidate (replace
  mode), then joins the group. Cancelling is a no-op.

The push happens via the existing `/api/screens/<id>/playlist` endpoint
in replace mode, then the join happens via the existing
`/api/screens/<id>/sync-group` endpoint. No new server endpoints —
just better client-side gating.

### Cleanup

- Removed the now-unused `DRIFT_CORRECTION_MS` constant on the tablet
  (3 s threshold from v0.1.11). The new snap-at-transition model
  doesn't need a threshold — every transition corrects to perfect.
- `_compute_playback` on the server kept as a legacy fallback for any
  tablets still on v0.1.11 or earlier, but new tablets only read
  `loopStartedAtMs` and ignore the rest of the playback block.

## v0.1.11

Sync groups, properly. Both the mechanism and the UI.

### Why screens weren't actually syncing

Two real bugs underneath the v0.1.6 implementation:

- **Mix-splash broke the loop math.** When mix-splash was on, ExoPlayer
  played `[splash, item1, item2, …]` but the server's sync calculation
  only knew about `[item1, item2, …]`. Each loop the tablet ran ~5 s
  longer than the server thought, so the "you should be on item X at
  position Y" hint steadily diverged from where the tablet actually
  was — every poll triggered a mid-item seek (visible buffer flash).
  Fix: when a screen is in a sync group, the server now forces
  `mixSplash: false` in the `/api/state` response, regardless of the
  stored preference. The stored value is preserved so leaving the
  group restores splash behavior.
- **Drift threshold too aggressive.** ExoPlayer's `seekTo` shows a
  visible buffer flash on cheap Android TV boxes. The 1.5 s threshold
  fired on basically every poll for any healthy sync group, producing
  constant micro-glitches that looked worse than the drift itself.
  Bumped to 3 s — small drift gets ignored, group members still align
  at every item transition (which they naturally do without seeks).

### Why the UI made it easy to break

Sync group was a freeform text input. Typos = different groups. No
visualization of "these 4 screens are grouped together." You had to
manually set the same string on every screen.

- **Auto-grouping by store.** When a screen registers and has a
  `location.storeId` set, the server now defaults its sync group to
  `store:<storeId>`. So every screen at the same store falls into one
  group out of the box. Admins can still detach individual screens or
  use a custom label (e.g. "wall-A") to split a store across multiple
  walls.
- **New picker UI on the screen detail page.** The old text input is
  gone. The Sync group card now lists every other registered screen
  with a checkbox; ticking joins them to this screen's group,
  unticking removes them. If neither screen is in a group yet, the
  first tick creates a new one keyed off the store id. Screens in a
  different group show greyed out with a note ("currently in 'wall-B'
  — ticking will move it here"). A "Stop syncing this screen" button
  lives at the bottom of the card.

Pushing a playlist to one member of a sync group still fans out to
every member (unchanged from v0.1.6, but now the picker UI makes it
obvious which screens that includes).

## v0.1.10

Cleanup pass. Removes the staging copy that survived earlier
sweeps and fixes a layout flash when navigating between pages.

- **"Good morning, Alex"** is gone. The greeting now uses the
  signed-in user's actual first name from `/api/auth/me`, plus a
  time-of-day adjective based on the local clock. Falls back to
  "Good morning, there" when auth is still loading.
- **Empty-state flash on Dashboard / Screens list** fixed. The
  `useLiveScreens` hook now caches its last good response at the
  module level so re-mounting a page (or navigating between them)
  shows the previous state instantly while the next poll runs.
  Pages also distinguish "loading" from "actually empty" via a
  new `loading` flag, so a fresh load shows "Checking screens…"
  rather than "No screens registered yet" until the first fetch
  lands.
- **Hardcoded "2 regions"** removed from Dashboard stat band and
  Screens list subtitle. Region count is now computed from the
  actual taxonomy values present in the registered fleet
  (suppressed entirely when zero).
- **Hardcoded "1,284 videos total"** in Settings → Brands replaced
  with the live `MOCK_VIDEOS.length`.
- **Schedules preview** copy de-branded: "Razr 50 / Saks Fifth
  Avenue / 84 screens across 6 stores in UK/EU" replaced with
  generic placeholder text since the page is gated behind a
  "Coming soon" banner anyway.
- **Tablet preview** success-screen fallback "Arc Ultra — hero
  reveal" reduced to an empty string. (Only fired in the
  no-video-picked path, which never normally fires.)

## v0.1.9

Whole release focused on the CMS being usable from any screen size — phone,
tablet, laptop, desktop.

- **Mobile sidebar drawer.** On phones and tablets the sidebar is now a
  slide-in drawer rather than a fixed left rail. A hamburger button + page
  title appear in a sticky header at the top. Tapping anywhere outside the
  drawer dismisses it; navigating to a new page auto-closes it.
- **Responsive breakpoints** added to the design tokens: mobile (≤640 px),
  tablet (≤1024 px), laptop (≤1440 px), desktop (>1440 px). Components
  branch on `useViewport()` for markup changes and use new utility classes
  (`.scr-mobile-hide`, `.scr-mobile-only`, etc.) for visibility.
- **Dashboard** — stat band wraps to 2×2 on tablet / 1-col stack on mobile.
  Quick actions go from 4-up to 2-up to 1-up. Stores + Activity stack
  vertically on compact viewports. Per-store status chips hide on
  phones (the progress bar conveys the same info more compactly).
- **Content Library** — brand sidebar collapses to a slide-down panel
  controlled by a "Brands" chip in the toolbar on compact viewports.
  Video grid columns adapt (140 px min on phones for a 2-col layout,
  200 px on larger). Page size drops from 24 → 12 on mobile so paging
  feels less laggy.
- **Screens list** — store rows hide per-status chip clutter on mobile;
  the progress bar tightens to fit narrow phones.
- **Screen Detail** — 2-column main+rail layout collapses to a single
  stacked column on compact viewports. Padding tightens for phone screens.
- **Settings** — left tab nav becomes a horizontal scrolling strip across
  the top on compact viewports.
- **Users & permissions** — invite form stacks all four fields on mobile;
  user row grid simplifies to avatar + identity primary, with role / status
  / actions reflowing into a second row.
- **Activity log** — tighter padding on mobile.
- **PageHeader** — title + actions stack on compact viewports; actions
  flex-wrap so a row of buttons can't overflow.
- **Modals go full-screen on mobile** — PushPicker, AddContentModal,
  PreviewModal, UploadPanel, SyncPicker all opt into `.scr-modal-panel`
  which forces 100% width/height + zero border-radius at ≤640 px. No more
  modal-inside-tiny-modal on phones.
- **Touch hit-targets** — utility class `.scr-touch` enforces a 40 px floor
  on phones for components that opt in.

Behavior on laptop/desktop is unchanged unless a layout was actively
broken at narrower-but-still-desktop sizes — in which case the new
flex-wrap or auto-fill behavior fixes it gracefully without altering
the wide layout.

Out of scope for this release (queued for later if useful):
- PWA / install-to-homescreen
- Service-worker offline support
- Native touch gestures (swipe-to-dismiss drawer, etc.)

## v0.1.8

Three poll modes, a refresh-now button, and a properly-decoupled heartbeat.

- **Three poll cadences** replace the old binary low-data toggle:
  - **Fast** — 10 s. Install / debugging.
  - **Normal** — 60 s. The new default for most screens.
  - **Slow** — 10 min. Cellular / metered installs. Also skips the
    per-location splash download to save data.
  Default flips from 3 s to 60 s — the old 3 s polling was burning
  ~150 MB/day per tablet for almost no functional benefit. CMS pushes
  now feel marginally slower (up to a minute) in exchange — that's
  what the Refresh now button below is for. Existing screens with
  `lowDataMode: true` migrate to Slow; everything else to Normal.
- **Refresh now button**, both on the screen detail page in the CMS
  and in the tablet's staff overlay.
  - **Tablet button**: fires an immediate playlist re-poll. Instant —
    useful for on-site staff who just want to see a push land
    without waiting.
  - **CMS button**: queues a `refresh` command. The tablet picks it
    up on its next poll and re-fetches state. ETA in the toast tells
    you when to expect it ("~10 s" / "~60 s" / "~10 min" depending on
    the screen's poll mode). For truly-instant CMS pushes we'd need
    FCM push (queued for a future release).
- **Heartbeats no longer get blocked by downloads.** The heartbeat
  used to fire at the very end of the playlist-refresh function —
  *after* every video had finished downloading. A multi-video pull
  on a fresh tablet would stall heartbeats for the duration, and
  the CMS would flip the screen to Offline mid-install. The
  heartbeat now lives on its own 10 s coroutine, completely
  decoupled from playlist work. CMS shows the screen online even
  during a long download.
- **Poll Interval setting removed from Device admin** — finished
  the cleanup started in v0.1.7. It was a leftover from the legacy
  `/device/settings` flow and the live path ignores it entirely;
  Poll Mode is the real control now.

## v0.1.7

Hotfix for two issues spotted right after the v0.1.6 cut.

- **CMS playlist no longer disappears on deploy.** v0.1.5 and v0.1.6
  added state persistence to `_per_screen.json` / `screens.json` /
  `sync_groups.json`, but only the library file's Cloud Run env var
  was actually set, so the new state files were quietly writing to
  the ephemeral container filesystem and getting wiped on every
  redeploy. Defaults now derive from `LIBRARY_JSON`'s parent
  directory — one `SCREENS_LIBRARY_PATH=/data/library.json` env var
  pins all four files on the FUSE-mounted bucket. Explicit env vars
  still win when set.
- **Tablet staff overlay now shows the live playlist.** A stale
  empty response from the server briefly wiped the staff overlay's
  playlist view (even though the player kept looping the cached
  items from disk), which made it look like the playlist was gone
  and tempted users into pushing an empty list back to the server.
  The view now updates only when the server's response is actually
  trusted.
- **"Poll interval" setting removed** from Device admin → Config.
  It was a leftover from the legacy `/device/settings` flow; the
  live-server path uses a hardcoded 3 s (or 60 s when Low data mode
  is on), so editing the field did nothing.
- "Schedules" feature shifts to v0.1.8.

## v0.1.6

Multi-screen sync, low-data mode, playback watchdog, and a UI cleanup pass.

- **Sync groups: multi-screen synchronised playback.** Tag two or more
  screens with the same **Sync group** label (Screen detail → Display
  card), and the server hands every group member identical playback
  hints on each poll — same item, same position. Tablets seek
  ExoPlayer to correct drift when it exceeds 1.5 s, so screens in the
  group stay aligned. Pushing a playlist to one screen in a group
  fans out to every member automatically — opt out per request with
  `fanOutToGroup: false`.
- **Low data mode.** Per-screen toggle on the screen detail page and
  in the tablet's staff overlay. When on, the tablet polls
  `/api/state` every 60 s instead of every 3 s and skips the
  per-location splash download. Cached videos are unaffected. Trade
  near-real-time CMS responsiveness for ~95% less idle bandwidth —
  great for cellular-tethered or metered installs.
- **Playback watchdog.** Background loop on the tablet that samples
  ExoPlayer every 30 s. If playback stalls (frozen position, stuck
  buffering for 2 min, unhandled player error), it escalates through
  `prepare()` → playlist refresh → in-place activity restart. Zero
  cost when healthy; auto-recovers the rare case where the player
  would otherwise silently freeze and need a manual reboot.
- **Content Library: ~450 kB → 0 kB per poll on the idle path.**
  Server returns ETag on `/api/library`; client sends
  `If-None-Match` and skips on 304. Poll cadence dropped from 10 s
  to 60 s. Net: ~150 MB/h per open CMS tab → kilobytes.
- **Content Library play button** no longer renders white-on-white
  in dark mode.
- **Settings → Users** removed in favour of the sidebar's Users page
  (the Settings tab was a mocked-up local list; sidebar is the real
  one talking to `/api/users`).
- **UI cleanup pass** — placeholders that didn't do anything now
  either say so or are gone:
    - Schedule card on the screen detail page → "Coming soon" badge.
    - Schedules sidebar page → "Preview only" banner above the
      existing mock.
    - "Filters" button on the library → removed.
    - "Add to schedule" in the selection action bar → removed.
    - Upload panel → "Coming soon" explainer pointing at the Drive
      Sync workflow that actually works today.
- **On-tablet preview** page now mirrors the live brand list from
  the library and drops the hardcoded "Saks Fifth Avenue" / fake
  helpline copy. A "Preview only" badge on the back-to-admin chip
  makes it clear nothing pushes to real screens from this page.
- **Admin overlay** "Cancel" button renamed to "Return to splash"
  with a proper bordered button style — it always meant "exit the
  staff overlay back to the player loop," not "undo my changes."

## v0.1.5

State that actually survives the things that used to wipe it.

- **CMS no longer forgets every screen's playlist on deploy.** Per-screen
  state (playlist, audio toggle, splash-mix toggle, pending commands)
  and the device registry now persist to `/data/per_screen.json` and
  `/data/screens.json` on the FUSE-mounted bucket. Cloud Run redeploys
  on every merge to `main`; previously each redeploy cleared the
  in-memory dict and every tablet polled back as "no content."
- **Tablets remember their last playlist across reboots and updates.**
  Every successful playlist publish saves a copy to DataStore. On cold
  boot the player rehydrates immediately from local cache — no more
  splash flicker between launch and the first `/api/state` response,
  and a tablet that boots offline keeps playing what was last on it.
- **Empty server responses no longer drop the tablet to splash.** If
  the server returns an empty playlist at a revision ≤ what the tablet
  last applied (the "the server lost its state" signature), the tablet
  keeps playing what it has. Genuine "clear this screen" actions from
  the CMS arrive with a fresh forward-going revision and still take
  effect.
- **"Screen ID — demo (no backend)"** in the staff overlay was always
  misleading on live-mode tablets — the legacy field it referenced
  never got populated. Now shows the human-readable **screen code**
  from onboarding; the device ID row above still shows the
  server-side identifier.
- **Network test now checks the CMS first.** A new "CMS reachability"
  panel at the top of the diagnostics view confirms the tablet can
  actually reach `screens.smartechworld.com` (or your configured URL),
  with HTTP status + round-trip time. Cloudflare throughput remains
  below — useful but secondary, since corporate networks routinely
  allow general internet while firewalling Cloud Run.

## v0.1.4

Sound on the screens, plus the splash finally lands on 4K.

- **Mute by default, unmute per-video or per-screen.** New videos play
  silently — that's the safe default for tablets dropped into a store.
  Two ways to turn sound on:
    1. **Per video, in the Content Library.** Open any video preview and
       flip **Default to unmute** on. That video now plays with sound
       wherever it appears, regardless of the screen's audio setting.
       The flag is sticky across Drive rescans.
    2. **Per screen, on the screen detail page or the tablet's staff
       overlay.** Flipping **Audio** to On unmutes every video the
       screen plays, overriding the per-video default.
- **4K splash downloads work now.** The Smartech splash is ~70 MB, which
  was sailing past Cloud Run's 32 MB Content-Length response buffer and
  failing with HTTP 500. The splash endpoint now streams chunked, same
  pattern we used for the video proxy in v0.1.3.

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
