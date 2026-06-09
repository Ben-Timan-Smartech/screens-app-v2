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
