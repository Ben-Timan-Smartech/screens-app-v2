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

## v0.1.99

- **Fixed: clicking "Experiences" in the content library did nothing.** It
  selected, then instantly snapped back to All brands, so the section was
  unreachable. It now opens and stays open. Also fixed the same section being a
  dead end on phones and tablets, where the button to get back to the brand list
  had disappeared.

## v0.1.98

- **Let customers skip to the next video.** A screen can now show a **"Next ›"**
  button so a shopper or Specialist can move the loop on instead of waiting.
  Turn it on per screen under **Display & playback → Next-video button** — it's
  off by default, so nothing changes on your screens unless you switch it on.
  It's unavailable on screens in a sync group, because those play in step with
  each other and skipping just one would knock it out of line.

## v0.1.97

- **Experiences now have their own section in the content library.** Open
  **Content library → Experiences** to see every guided experience, preview any
  of them, and (admins) upload a new one or delete one you added. Built-in
  experiences are marked and can't be deleted. If an upload is refused, the
  reason is shown right there so you know what to fix.

## v0.1.96

- **"Back to video" button on every guided experience.** A customer who opens an
  experience can now close it themselves with a button at the top of the screen,
  instead of waiting for it to time out. It's added by the app, so every
  experience gets one automatically — brands don't have to build it in.
- **Upload guided experiences from the CMS.** Admins can now upload an
  experience as an HTML file instead of it needing a developer and a release.
  The upload is **checked before it's accepted**: if the page pulls anything from
  the internet (scripts, fonts, images) it's refused with an explanation, because
  that's what would leave a screen blank when the shop wifi drops. Uploaded
  experiences appear alongside the built-in ones.

## v0.1.95

- **Choose where the "Tap to explore" prompt sits.** On a screen running a guided
  experience you can now put the prompt at the **top or the bottom** — pick it on
  the screen's page under **Display & playback → Guided experience**. It stays
  centred left-to-right either way, because the screen corners are reserved for
  the staff unlock gesture.

## v0.1.94

- **Smaller "Tap to explore" prompt, moved to the top.** On screens running a
  guided experience, the prompt was a big block in the middle of the screen,
  covering the video. It's now a small pill at the top — the attract video is
  almost fully visible, and it's still an easy target to hit.

## v0.1.93

- **Hotfix: guided experiences now actually load in production.** v0.1.92 shipped
  the feature but the container image wasn't packaging the experience files, so
  the screen couldn't fetch them. Fixed the build to include them. Also fixed the
  server reporting its version as "dev" in the CMS sidebar (the version file was
  likewise missing from the image) — it now shows the real release.

## v0.1.92

- **Guided brand experiences — interactive content on a screen.** A screen can
  now host a full **touch-interactive** brand experience (first up: the WHOOP
  demo) instead of only playing video. The screen's video plays as an attract
  loop with a **"tap to explore"** prompt; a customer taps to open the
  interactive experience full-screen, and it returns to the loop on its own
  after a minute or so of no touch — so it resets for the next person with no
  staff needed. It **runs offline**: the tablet downloads the experience once
  and caches it, so a wifi drop doesn't blank the screen. Turn it on per screen
  from **Display & playback → Guided experience** on the screen's page. Staff
  still open the menu with the four-corner tap.

## v0.1.91

- **Fixed (for real this time): tapping the product card now expands it.** The
  earlier fix didn't work — the invisible layer that watches for the staff-unlock
  corner taps was sitting over the whole screen and blocking every tap from
  reaching the card underneath it. The card now sits in front of that layer, so a
  tap opens the description as intended, while the staff four-corner unlock still
  works. (Touch screens only; TV-style screens keep auto-cycling the card.)

## v0.1.90

- **Product card now closes itself.** When a shopper taps a product card open to
  read the description, it automatically collapses back to the compact price view
  after 15 seconds — so if someone taps and walks away, the card doesn't stay open
  over the video. Tapping it again still closes it instantly, and a new product
  starts compact.

## v0.1.89

- **Turn the product card on/off from the screen itself.** The **Pricing &
  description** toggle now lives on the tablet's own staff screen (tap through the
  PIN → "What's on this screen"), right above **Mix splash** — so a store person can
  switch the on-screen price/description card on or off without opening the CMS. It
  stays in sync with the same toggle on the screen's page in the web admin.
- **Fixed: "tap for details" on the product card did nothing.** The hidden
  staff-unlock layer (the four-corner tap) was sitting over the whole screen and
  eating every tap, so tapping the product card never expanded it. Taps now pass
  through to the card unless they're part of the staff-unlock corner sequence, so
  tap-to-expand works on touch screens again.

## v0.1.88

- **Product info card on screen.** Screens can now show a product's **price and
  description** over its video — a card the customer can **tap to see the full
  details** (on touch screens; TV displays cycle it automatically). Prices show in
  the store's own currency (£/$/€), pulled from the tm:rw catalogue, and the card's
  image falls back product image → brand logo → nothing. Turn it on per screen from
  **Display & playback** on the screen's page in the CMS; it only appears for
  products that actually have price/description data in the catalogue. Off by
  default.

## v0.1.87

- **Fixed: legacy screens stuck unable to update.** Some tablets (mostly the
  older/legacy boxes) could get wedged repeatedly failing to download a player
  update — the server was answering their resume request with an error it kept
  retrying forever. The download proxy now detects that case and cleanly serves
  the whole file instead, so the tablet restarts the download and updates
  normally. Server-only fix; screens self-heal on their next update attempt.

## v0.1.86

- **Staff can upload content again.** The "Upload content" button in the Content
  Library was only ever going to work for owners and admins — everyone else
  (managers, standard users, brand partners) saw the button but got a silent
  "forbidden" error when they tried to upload. Uploading a video is now treated
  as a normal content edit, so anyone with content-editing access can do it.
  Read-only viewers no longer see the button at all (it never worked for them).
  Kicking off a full Google Drive re-sync stays admin-only, as before.

## v0.1.85

- **Offline-screen alerts.** The server now watches the fleet and, when a screen
  that was online drops offline for more than a few minutes, records it in the
  activity feed and — if an alert webhook is configured — pushes a notification
  to Slack/Teams/Discord (and a "back online" notice when it recovers). Set the
  webhook URL in `SCREENS_ALERT_WEBHOOK` to turn on push notifications; without
  it you still get the activity-log entries. Tunable grace via
  `SCREENS_ALERT_OFFLINE_AFTER_SEC` (default 5 min). No more finding out a store
  screen has been dark for hours.

## v0.1.84

Security + a round of polish.

- **Screens can only be controlled by signed-in staff (with the right role) or
  by the screen itself.** Previously the tablet's own no-login controls
  (playlist/audio/sync/etc.) could be driven by anyone who knew a screen's id —
  including a read-only viewer. Now every signed-in action is permission-checked,
  and each tablet authenticates its own changes with a per-device key issued at
  setup. Rolls out safely: existing screens keep working until they're on this
  version, then the check is enforced fleet-wide.
- **Staff PINs are no longer shown.** The CMS used to display every staff PIN to
  any admin; PINs are now write-only — you set/change them but never see them
  back (like resetting a password).
- **CMS polish.** The store, stores-index, and content-library search boxes now
  actually filter; the command palette's dark-mode / video-preview / Drive-sync /
  upload actions all work; dead buttons that did nothing were removed; and you
  can finally drag a playlist item to the last position.
- **Fewer wasted downloads + snappier splash toggle.** A store with a big
  playlist no longer churns re-downloading its own in-rotation videos, and the
  "mix splash" toggle now takes effect immediately instead of on the next poll.

## v0.1.83

Reliability + hardening pass (from a full-project review).

- **Security hardening (server).** Tightened static-file serving so a crafted
  URL can't reach anything outside the app folder; user management now blocks
  editing an equal-or-higher-ranked account; hardened the Splashes API against a
  rare crash under concurrent edits; media range-requests past end-of-file now
  return a proper 416 so screens self-correct.
- **CMS bug-fix sweep.** A dropped/expired session no longer blanks the whole
  fleet to "No screens registered"; opening a screen from search or Sync groups
  now lands on the right screen (not a store page); screens on added/custom
  stores (incl. Test) are now listed and included in "push to all"; the Content
  library no longer white-screens if a brand disappears mid-sync; the Users page
  and Add-content preview recover cleanly from errors/Esc; and a renamed screen
  can't get stuck showing a stale name.
- **Player reliability sweep (self-healing).** The watchdog's deeper recovery
  step no longer silently failed on a background thread, and "Refresh playlist
  now" works again; a screen stuck on a single video it can't decode now
  recovers instead of freezing forever, and a known-bad clip is skipped every
  loop instead of re-freezing for minutes each pass; the updater can't cache a
  corrupt APK from a double-tap; a dismissed "Installing…" screen clears itself;
  the "Restart app" path is used for remote reboots; and splash files no longer
  pile up on disk.

## v0.1.82

- **"Restart app" on the main staff overlay.** The Restart app button — relaunch
  the player on the spot, without a full device reboot (cached videos +
  registration are kept) — now sits right under "Refresh now" on the main staff
  playlist screen, not just in Device admin → Actions.

## v0.1.81

Rename screens from the CMS, plus a simple install page for the player.

- **Cleaner screen names.** A screen's name is now just its **screen code** (the
  concept is no longer glued onto it). Click the pencil next to the name to give
  it any custom name you like — that **overrides** the auto name and shows
  everywhere (screens list, activity log, pushes), and the tablet can't
  overwrite it on its next check-in. The rename box starts from the screen code
  so you're editing that directly. Works even while the screen is offline.
- **Friendlier store screens view.** The list of screens in a store can now be
  **sorted** by name, recently added, or recently updated (your choice is
  remembered); **filtered by concept**; and shows the **concept** in its own
  column plus the **app version** each screen is running — so you can group by
  concept and spot which boxes are behind without opening each one.
- **Edit a screen's location & concept from the CMS.** A screen's detail page
  now has a **Location** card to change its region, city, store, **concept**,
  and screen code without touching the tablet. This overrides what the tablet
  reports (it can't undo your change on its next check-in). Because **concept
  picks the splash**, this is how you fix a screen showing the wrong splash —
  e.g. set a New York screen's concept to **tmrw** to get the tm:rw splash.
- **New "tmrw" concept.** Added as a selectable concept (pinned to the top of
  the picker) wired to the tm:rw splash, so tm:rw screens can be set explicitly
  rather than relying on the city default.
- **"Restart app" button on the tablet (staff overlay).** Under Device admin →
  Actions, right below "Refresh playlist now": relaunches the player on the spot
  (no full device reboot; cached videos + registration are kept) — the quickest
  way to recover a screen stuck on the splash or wrong content.
- **A shareable install page.** Visit **screens.smartechworld.com/download** for a
  one-tap install page: it shows the current version and download buttons for
  the standard and legacy builds, with step-by-step install help. Send it to
  whoever's setting up a screen, or open it on the tablet itself if the in-app
  update stalls — re-tapping a button restarts the download from scratch.

## v0.1.80

A screen no longer freezes on a video it can't play — and the logs say why.

- **One bad video can't freeze the screen.** If a clip is too demanding for
  a player to decode (common on older "legacy" boxes — 4K, very high bitrate,
  or an unusual codec), the screen now detects it's stuck, **skips past it,
  and keeps playing the rest** instead of freezing on it.
- **The playlist tells you which one, and why.** The skipped video is flagged
  on its row in plain English — e.g. *"Won't play on this screen — too
  high-res/bitrate or an unsupported codec. Format: hevc 3840×2160"* — so you
  know exactly what to re-encode.
- **Richer diagnostic logs.** Every watchdog action now records the video's
  name **and its format** (codec / resolution / bitrate), so a stall is
  obvious at a glance instead of needing a deep dive — for both staff and
  support.

## v0.1.79

Clearer status for each video in the playlist (on the tablet).

- **See where each video is.** When you add content on the tablet, every
  playlist row now shows its live status — "Syncing to server…", a download
  progress bar, or a green tick once it's ready.
- **Failures explain themselves.** If a video can't download, the row now
  shows the actual reason (e.g. "Couldn't download: timeout") instead of
  just a red ✕ — so you can tell whether it's the Wi-Fi, a missing file, or
  something else.

## v0.1.78

Steadier updates on weak store Wi-Fi, and far less log noise.

- **Updates resume instead of restarting.** If a screen's Wi-Fi drops
  mid-download, the app now picks the update back up where it left off
  rather than restarting the ~10 MB download from scratch — so updates
  actually finish on flaky in-store connections.
- **Cleaner activity logs.** Patchy Wi-Fi was flooding the logs with
  repeated "timeout" lines, and a stuck screen logged the same message
  every few seconds — burying anything that mattered. Those are now
  collapsed to one line with a count, so real issues stand out.

## v0.1.77

Product packshots in the content library.

- **See the product, not just the file name.** The content library — in both
  the CMS and the on-tablet picker — now shows each product's packshot image
  next to it, pulled automatically from the tm:rw asset manager. It makes
  picking the right clip much faster when several share similar names.
- Videos that aren't tied to a specific product (brand-wide clips, or files
  the asset manager doesn't recognise yet) simply keep the existing
  thumbnail — nothing to set up, images appear as the catalogue fills in.

## v0.1.76

Screens that get stuck on the splash now fix themselves.

- **Auto-recovery for "stuck on the splash."** Sometimes — usually right after
  the first video is added to a brand-new screen — the video downloads fine but
  the screen keeps showing the splash instead of switching to it. The player now
  notices this and recovers on its own: first it quietly restarts the video
  player, and if that doesn't do it within a minute or so, it reboots the screen
  (the fix that's always worked). No more walking over to power-cycle it.
- **New "Restart player" button.** On a screen's page in the CMS there's now a
  *Restart player* button next to *Reboot*. It restarts just the video playback
  without rebooting the whole device — the gentlest way to nudge a screen that's
  showing the wrong thing. If it doesn't take, the screen will reboot itself.

## v0.1.75

Smoother splash screens, especially on weak in-store Wi-Fi.

- **No more splash-download spam.** If a screen can't fetch its splash, it
  now waits and retries gently — and resumes a half-finished download —
  instead of re-trying every few seconds and clogging the connection.
- **Right splash on a cold start.** The Smartech and tm:rw splashes are now
  baked into the app (landscape), so a freshly-installed or just-rebooted
  screen shows the correct on-brand splash straight away, before it's even
  online. The splash you set in the CMS still takes over once it downloads —
  including the portrait version on portrait screens.

No setup needed — just update the screen.

---

## v0.1.74

Pull a screen's latest logs on demand, right from the CMS.

Open a screen, find the **Device logs** card, and hit **View logs** — you'll
see the recent log entries the screen has sent. Tap **Request latest** and
the screen uploads its full current log buffer (all levels, not just
warnings), which appears in the viewer a few seconds later. Filter by level
(Errors / Warnings / Info / Debug) to zero in on a problem. Handy for
troubleshooting a misbehaving screen without being on-site or plugging in
a cable.

(Screens need to be on v0.1.74 for "Request latest" to pull a fresh buffer;
the viewer shows already-collected logs from any version.)

---

## v0.1.73

Screens now self-heal a corrupted video instead of looping an error on it.

If a cached video file gets truncated or corrupted (usually a download cut
short by flaky in-store wifi), ExoPlayer would fail to play it — *"read
position out of range"* — and the tablet's watchdog could only retry the
same bad file over and over. Now, when a screen hits that error it
**automatically deletes the bad copy, drops the video from the loop so it
stops erroring, and re-downloads a clean copy** — which rejoins the
rotation once it lands. It gives up after two attempts on a given file
(so a video that's genuinely broken at the source can't loop forever) and
logs it for follow-up.

No setup needed — it just makes playback more resilient on top of the
existing resumable-download protection.

**Updates no longer download twice.** If a tablet already pulled the new
APK but the install didn't finish (e.g. the permission prompt got in the
way), tapping Update again now **reuses the downloaded file** instead of
re-downloading the whole thing.

**The install permission is asked up front.** Before downloading, the
tablet checks whether "Install unknown apps" is allowed for Screens — if
not, it opens that setting straightaway with a clear message, so you grant
it once and the update goes through on the next tap (no wasted download).

**The update dialog now shows more:** which version you're on vs the one
being installed, a live **download speed and time-remaining** readout, and
a "You're on the latest version" confirmation when you check and there's
nothing new.

**Tablet brand picker shows real logos.** The brand grid now renders each
brand's logo (from the asset manager), falling back to the lettered tile
when a logo isn't available — matching the CMS.

---

## v0.1.71

A cleaner columned list for picking videos — in the CMS **and** on the tablet.

When you pick a video for a brand, you now get four lists: **All**,
**Products** (with a count of how many products the brand has), **Brand
videos**, and **Orphans (Unassigned)**. Each opens the same list view,
with proper columns: **Product Name**, **Size**, **Length**,
**Orientation**, **Resolution**, and **SKU Name**. Orientation, resolution
and the SKU come straight from the asset manager, so they show even for
videos that haven't synced to the Drive folder yet. The tablet's Add-content
picker gets the same four lists and columns (replacing the old card grid).

---

## v0.1.70

Hotfix: product-scope assigned videos now show under the right brand.

A product-scope video (e.g. a TCL Tab A1 Plus clip) wasn't appearing in
the CMS. tm:rw's `/videos` rows for product/family-scope videos carry
the SKU/familyId in `scopeKey` and have no `brand` field, so the v0.1.69
grouping filed the video under the SKU ("9445X1-2ALCA111-3") instead of
its brand ("TCL Global") — and it vanished. The server now resolves
each non-brand-scope video to its real brand via `/product?sku=`
(cached per refresh), with familyId-prefix and scopeKey fallbacks.
Brand-scope videos are unchanged. Affects the CMS and the tablet (both
read the same `/api/library`).

---

## v0.1.69

Brand content sectioned into Brand global / Products / Orphans / All (CMS + tablet).

When you open a brand's content, the video set is now grouped exactly
how the asset manager scopes it:

- **All** — flat list of everything in the brand's set.
- **Brand global videos** — brand-scope videos that apply to the whole
  brand.
- **Products** — a section per product line, each showing that
  product's own + family-scope videos.
- **Orphans** — files in `Screens/Brand Content/{brand}` that aren't
  matched (by filename) to any assigned video in the asset manager.

Same structure in the CMS Content Library rail and the on-tablet "Add
content" picker. Driven by a new `scope` tag the server reads from the
tm:rw `/videos` feed and merges into each video.

---

## v0.1.68

CMS: refresh a single brand folder.

Settings → Drive sync now has a **Refresh one folder** control — pick a
brand and re-scan just its folder instead of the whole Drive tree. It
merges the result into the library (every other brand untouched), so an
operator who dropped a new file into one brand gets it live in seconds
rather than waiting on a full sync.

- New endpoint `POST /api/library/refresh-folder { brand }`.
- `scan-videos.py` gains a `SCREENS_SCAN_ONLY_BRAND` scoped mode that
  re-scans one brand and merges into `library.json` (skips the
  change-token / full-tree logic). Works in both cloud + local modes.
- Also refreshes the tm:rw video cache for that brand at the same time.

---

## v0.1.67

Tablet content picker: product lines + orphan / pending videos.

The on-tablet "Add content" video picker now matches the CMS Content
Library's tm:rw view:

- **Product-line filter pills** above the video grid — tap a line to
  narrow to it, or "All". Driven by the asset-manager tags the server
  merges into the library.
- **Orphans pill** for videos that are in the Drive folder but not
  registered in the asset manager (badged "Orphan" on the card).
- **Pending videos** — registered in the asset manager but not yet in
  the Drive folder — show dimmed with a "Pending" badge and can't be
  selected, since there's no playable file to push yet. Brands that
  only exist in the asset manager (e.g. a brand-wide video) now appear
  in the tablet brand list too.

Brand logos on the tablet brand grid are coming in a follow-up (they
need an image-loading library; keeping it out of this build to stay
safe).

---

## v0.1.66

Tablet: refresh the content library on demand + on every launch.

- **New "Refresh content library now" button** in the staff overlay
  (Admin → actions, next to "Refresh playlist now"). Re-pulls the brand
  + video list from the server immediately, without restarting the app.
- **Auto-pull on every launch.** The tablet now refreshes the content
  library once on startup — so a normal relaunch, and especially the
  relaunch after an APK update, always lands the current library
  instead of waiting for the periodic tick.
- **Bug fix:** the periodic library refresh used `% interval == 1`,
  which never fired in Slow poll mode (the legacy build's default) — so
  those tablets only ever had whatever library they booted with. Now it
  fires correctly in every poll mode.

---

## v0.1.65

Content Library now surfaces tm:rw assigned videos directly + refreshes fast.

### Assigned videos show up even without a Drive folder

v0.1.64 only *annotated* videos already in the Drive scan, so a
brand-wide video registered in the asset manager for a brand with no
Brand Content folder (e.g. **Moods**) was invisible. Now the server
pulls the whole assigned-video set from tm:rw (`GET /videos`) and
surfaces each one in the Content Library — the brand appears in the
rail and its videos appear under their product line, even with nothing
on disk yet.

Such videos are flagged **Pending** (registered in the asset manager
but not yet in the Drive folder the player scans). They're visible but
can't be selected or pushed, because there's no streamable file until
the video lands in the scanned folder. Videos that *are* in the folder
play as normal.

### Much faster refresh

The tm:rw cache dropped from **6 hours to 5 minutes**, and **Sync now**
(Settings → Drive sync) force-refreshes it — so a video you add in the
asset manager shows in the CMS within ~5 min automatically, or
immediately if you hit Sync now. (The browser then picks it up on its
next 60-second library poll.)

### Still pending: playback of asset-manager videos

tm:rw files assigned videos to `Smartech/Brands/{Brand}/Videos/Compressed`,
but the player scans `Smartech/Screens/Brand Content/{Brand}`. Until those
converge (or the API returns a streamable Drive file id), asset-manager
videos show as Pending rather than pushable.

---

## v0.1.64

Content Library product selector + active/orphan videos (CMS), new tm:rw URL.

### Products selector + orphans

Inside a brand, the Content Library left rail now lists **product lines
pulled from the tm:rw asset manager** instead of the old hardcoded list.
Each line shows its video count and an "N active" hint. Clicking a line
filters the grid to it **and auto-selects its active videos** — ready to
push. An **Orphans** entry collects videos that are in the Drive folder
but not registered in the asset manager; list rows tag those with a small
"Orphan" badge.

How it works: the server fetches `GET /videos?brand=` from the tm:rw
index (using the Brand Asset Manager API key), caches it 6h, and tags
each library video with `tmrwAssigned` / `tmrwActive` / `productLine` by
matching on filename. A video is "active" when tm:rw returns it as an
assigned video for a live product/brand; anything the asset manager
doesn't know is an orphan.

Note: a brand only shows active videos once its assigned videos (by exact
filename) are present in the Drive folder the player scans. Brands the
asset manager hasn't processed yet show everything as orphans with a
one-line explanation — that's expected, not an error.

### New tm:rw API URL

The tm:rw index moved to a new Cloud Run URL
(`tmrw-index-api-izdr7go5hq-…`). Updated the default; override with
`SCREENS_BRAND_API_BASE` if it moves again.

The player APK gets the same product selector / orphan view + brand-logo
rendering in the next release.

---

## v0.1.63

Brand logos from the tm:rw index, in the CMS.

The brand list now shows real brand logos instead of generated letter
marks. The server pulls the `logoUrl` for each brand from the tm:rw
index `/brands` feed (using the Brand Asset Manager API key from
Settings → Integrations), caches it for 6 hours, and merges it into
the content library. You'll see logos in the Content Library brand
rail, Settings → Brands, and the on-tablet preview.

Details:
- Brands with no tm:rw logo (or if no API key is configured) keep the
  generated letter mark — nothing breaks without a key.
- tm:rw has duplicate brand rows by casing ("Anker" vs "ANKER"); the
  server keeps the row that actually has a logo, preferring the one
  with more live products.
- A broken logo URL falls back to the letter mark rather than showing
  a broken-image glyph.

The player APK now receives `logoUrl` per brand too, but rendering
logos on the tablet brand grid ships with the next release alongside
the product selector / orphan-video work.

---

## v0.1.62

Fix screens registered under Test / Events stores.

- **CMS:** `MOCK_STORES` (drives the Stores index + per-store views)
  was missing `events` and `test`, even though the cascade dropdowns
  in `LOCATION_TAXONOMY.stores` and the tablet's `LocationTaxonomy`
  already knew about them. Tablets registered with `storeId: "test"`
  succeeded on the server but had nowhere to land on the CMS — the
  Stores index didn't list a Test store, so the screen looked like
  it had failed to register. Added both with city/region "Global".
- **Tablet onboarding:** the "Ready: …" breadcrumb printed the
  literal "null" when no concept was picked. Concept isn't required
  outside multi-concept cities (NYC / LDN), so Test / Events / BER /
  ROM all hit this. Now the concept segment drops out when null, the
  same way floor and table already do. The matching log line was
  fixed too.

---

## v0.1.61

Hotfix: Brand API key connection test now uses a real endpoint.

v0.1.59 pinged `/me`, which doesn't exist on the tm:rw index API —
valid keys got past auth and then hit the router's 404. (The probe
that informed the original code returned 403 because the test key
was invalid; auth fired first and masked the router 404.) The test
now hits `/counts`, which is auth-required and returns the catalogue
roll-up. The success pill now shows the live brand / product / asset
counts so you can see you're talking to the right catalogue at a
glance.

---

## v0.1.60

Online / Live status now scales with each screen's poll rate.

### What changed

The old rule "online if last heartbeat was within 15 seconds" was
right for the default 60-second poll mode and wrong for everything
else. A tablet in **slow** mode (5-minute poll, used on shaky wifi
and on the legacy build by default) was marked **offline** every
time it finished a poll cycle, even though it was working fine.

Two thresholds now derive from each screen's own pollMode:

| Mode   | Poll interval | Live (currently polling) | Online (still reachable) |
|--------|---------------|--------------------------|--------------------------|
| Fast   | 10 s          | ≤ 15 s                   | ≤ 30 s                   |
| Normal | 60 s          | ≤ 75 s                   | ≤ 150 s                  |
| Slow   | 300 s         | ≤ 6 min 15 s             | ≤ 12 min 30 s            |

- **Live** = the tablet just checked in; the next poll is imminent.
  Commands sent now will land on the next cycle without queueing.
- **Online** = still reachable; missing one poll is tolerated.
- **Offline** = past the online threshold.

### Where you see it

- Screens list and Dashboard stay green for slow-poll tablets that
  are operating normally.
- Screen detail shows three states in the "Now playing" header:
  *Now playing* / *Connected, idle* / *Online* / *Offline*. The dot
  pulses only while the screen is **live**.
- Sync group member dots use the per-member rule too, so a slow
  group member doesn't briefly flash offline between polls.

The `/api/screens` payload now includes `live`, `pollIntervalSec`,
`onlineThresholdSec`, and `liveThresholdSec` per screen so future UI
can show the same nuance without re-deriving it client-side.

---

## v0.1.59

Test connection button for the Brand Asset Manager API key.

### What's new

Settings → Integrations now has a **Test connection** button next to
the Brand API key. Clicking it asks the CMS server to hit the tm:rw
index API with the stored key — the value never reaches the browser —
and shows the result inline:

- **● Connected · N ms** when `/me` returns 200, with any identity
  fields the API echoes (name, tenant, etc.).
- **● Key rejected** when the API returns 401/403 — usually means the
  key was pasted wrong, isn't activated, or belongs to a different
  environment.
- **● Unreachable** for network errors / timeouts (8 s server-side,
  12 s client-side as a backstop).
- **● Server error** for 5xx responses from the tm:rw index server.

Editing or saving a new key clears the previous result so a stale
"Connected" pill can't sit next to a key you haven't tested yet.

### Configuration

The endpoint is hardcoded to the production tm:rw index API. Override
with `SCREENS_BRAND_API_BASE` if you need to point at staging.

---

## v0.1.58

Owner-only Brand Asset Manager API key in Settings → Integrations.

### What's new

The Brand Asset Manager API key now has a home in the CMS. Settings →
**Integrations** (visible only when you're signed in as the Owner) shows
the current key — masked by default, with a Reveal button — and lets you
edit, copy, or clear it. Server-side, the key lives in a JSON file on
the persistent state volume, so a Cloud Run redeploy doesn't reset it.

### Why this matters

Until now there was nowhere in the CMS to put external service
credentials; pasting them in chat or threading them through env vars was
the only option, and env vars get wiped on every continuous deploy.

### Security shape

- Read and write both require the Owner role; the server returns 403
  for anyone else, even if they hit `/api/integrations/brandApiKey`
  directly. Hiding the tab from non-owners is a UX detail, not the
  enforcement.
- Activity log records who changed a key and when, but never the value.
- The `SCREENS_BRAND_API_KEY` env var seeds the value on first boot;
  once an Owner saves a key from the CMS, the on-disk JSON wins
  forever, so a stale env var on a later revision can't quietly
  replace a rotated key.

---

## v0.1.57

CMS UX cleanup — unregister, sync group, fit-to-page, mobile critical path.

### Unregister now removes the screen immediately

Hitting "Unregister device" on a screen used to queue a command that
only ran when the tablet next reconnected — so an offline screen
hung around on the CMS list forever and the toast said it was
"queued for next reconnect", which made no sense. Unregister is now
a server-side delete: the screen disappears from `/api/screens`
immediately and you're bounced back to the store view. If the
tablet later reconnects, it gets "unknown screen" on its next poll
and falls back to onboarding.

### Sync group → tap to expand

The Sync group card on the screen detail page was a permanent
member-grid + buttons ~250 px tall, even when the screen wasn't in
a group. It's now a one-line summary pill ("● Syncing with N other
screens" or "● Independent playback") with a Manage / Set up
button that opens the full picker in a modal. Same controls, far
less vertical space on the common case.

### Screen detail fits one viewport

The Schedule "Coming soon" card is gone (Schedules is hidden until
the feature actually exists, so the tease was just noise). The
Danger zone — Update / Reboot / Clear cache / Unregister — is now
collapsed behind a "More actions ▾" toggle. On a 13" laptop the
page now fits without scrolling for typical configurations.

### Mobile critical path

The screens-list, screen-detail, and "Add content" picker were
mobile-broken in subtle ways: the in-store grid hard-coded 4
columns (≈80 px tiles on a phone), the selection bar offset itself
by the desktop sidebar width even when the sidebar was a drawer,
and the Add-content modal's 220 px brand rail ate most of a 380 px
viewport. The grid is now auto-fill with a 150 px minimum, the
selection bar centres on the full viewport when the sidebar is in
drawer mode, and the brand rail collapses to a dropdown above the
video grid on phones. The "Add 3 to {screen name}" button trims to
"Add (3)" on mobile so it doesn't overflow.

---

## v0.1.56

CMS cleanup batch.

### Schedules hidden from the sidebar

The Schedules entry was a dead link — the MOCK_SCHEDULES list never
populated and the page just showed an empty state. Sidebar item is
now gated behind `false &&` so it stays out of the nav until the
feature actually does something. The page still exists at
`/schedules` for anyone who knows the URL.

### Screens — list view by default

Inside a store, the screens grid is now a list view by default. The
grid / list toggle buttons (previously decorative — no `onClick`)
now actually toggle, and the choice persists to `localStorage`. The
list shows status dot, name + currently-playing, device tier, and
brand in a scannable dense layout.

### Content Library — list view by default, real columns

Same story for the library. List view is now the default and the
toggle persists. List rows use a proper column grid with a header
strip (Title · Resolution · Length · Size). Click a row to open the
detail panel.

### Video preview → detail panel + Drive link

The `<video>` element in the preview modal kept hitting the Drive
proxy on slow links and rendered black more often than not. Replaced
with a metadata sheet showing filename, resolution, length, file
size, source (Drive / direct upload). Drive-sourced videos get an
**Open in Google Drive** button that links directly to
`drive.google.com/file/d/<id>/view` so the operator can view the
file natively.

The hover-`<video preload=metadata>` thumbnail in the grid view is
also gone — replaced with the generated brand thumbnail. No more
N video elements all racing the Drive proxy when you open the page.

### Splashes — saves persist across deploys

The `_city_brand` dict (which controls which brand splash plays in
which city) was mutated in memory but never written to disk. Every
Cloud Run redeploy silently reset operator choices back to the
DEFAULT_CITY_BRAND constants. Now persisted to
`/data/city_brand.json` next to the other state files and re-loaded
on boot.

### Users — inline PIN editing + real seed users

- `auth.public_user` includes `pin` in the public payload so the
  CMS Users page can read it.
- `PATCH /api/users/<id>` accepts a `pin` field (4 digits or empty
  to clear).
- Users page renders a new **PIN** column. Click the masked
  `••••` to reveal + edit; commits on blur or Enter.
- Tablet's hardcoded `UserDirectory` seed trimmed to the real
  three accounts: Ben Timan (9999, super admin), Store Team (1111,
  in-store user), Chris (no PIN yet, admin). The tablet still uses
  the hardcoded list as its offline fallback; a follow-up will wire
  it to fetch from `/api/users` on launch.

### On-tablet preview — light copy refresh

Rail subtitles updated to reflect the current real tablet UI —
mentions the 4-digit PIN defaults, the multi-select picker
("Tap several before Add"), and the 10-second auto-return after
success. The 4-stage preview layout itself wasn't restructured.

---

## v0.1.55

Hotfix — Drive sync was crashing with a 403.

```
HttpError 403 ... 'The attempted action requires shared drive
membership.' ... teamDriveMembershipRequired
```

The v0.1.46 broad-query path uses `corpora='drive'`, which Drive
only honours when the service account is a **member** of the
shared drive — not just granted Viewer on the brand-content folder.
On this install the SA had folder-level access only, so every sync
threw at `list_drive_inventory` and never produced a library.

`collect_videos_drive_v2` now catches the 403 (and any other
HttpError) and returns `(None, None)`. The existing caller fallback
kicks in and uses the v1 recursive walker, which works with
folder-level permissions — slower (multiple round-trips) but
correct. A clear log line explains how to restore the fast path:
add the service-account email as a member of the shared drive
(Drive → shared drive → Manage members → Add member).

The Phase 2 change-token short-circuit and Phase 3 incremental
apply also use `corpora='drive'` and would 403 in the same install,
but those paths already had try/except around them — they just
silently fall through to the broad-query path, which now correctly
falls through to v1. End result: a non-member service account
still gets a working sync, just on the slower v1 walker.

---

## v0.1.54

In-app updater works on more ROMs, and the manual-install fallback
is finally actionable.

### Why this matters

v0.1.51's resumable APK download fixed the *download* timeout, but
on certain Amlogic boxes the *install* still failed with
`This device has no package installer registered`. The v0.1.48
fallback chain tried `ACTION_VIEW + content://` then
`ACTION_INSTALL_PACKAGE + content://` and bailed if neither
resolved. Some custom ROMs:

- Ship the installer activity without the `DEFAULT` intent
  category — `pm.resolveActivity(intent, 0)` returns null, but
  `startActivity` would still dispatch.
- Only accept `file://` URIs (Android 6/7 era).
- Save the APK in `<filesDir>/updates/`, which is internal app
  storage that a file manager can't read without root — so the
  "install manually from this path" error message was useless.

### What's new

- **`file://` URI fallback** added to the candidate list when
  running on API 23 (Android 6 / Marshmallow). Skipped on API 24+
  because FileUriExposedException would throw.
- **Blind-`startActivity` second pass.** After the
  `resolveActivity`-guarded pass fails on every candidate, the
  updater retries each by calling `startActivity` directly and
  catching `ActivityNotFoundException`. This catches installers
  that don't declare the `DEFAULT` category — `resolveActivity`
  filters them out but `startActivity` can dispatch to them.
- **APK now lives in `getExternalFilesDir("updates")`.** Same
  app-scoped lifecycle (wiped on uninstall) but reachable from
  any file manager + `adb pull` without root. The manual-install
  fallback message now gives a path the operator can actually
  navigate to.
- **`FileProvider` config** updated to expose
  `<external-files-path>` alongside the legacy `<files-path>` so
  the FileProvider URI keeps working.
- **Clearer error copy** when all paths fail — numbered steps and
  an `adb install` example.

### Side effect

Any APK or `.part` file left in the old `<filesDir>/updates/`
directory from v0.1.53-or-earlier installs becomes an orphan. They
don't get auto-cleaned, but they're ~4 MB and harmless — wiped on
the next app uninstall or device factory reset.

---

## v0.1.53

Two fixes that hit the same source — silently-truncated downloads.

### Premature-EOF detection on every download

The "video isn't playing" report came back as ExoPlayer
`SOURCE_IO — Read position out of range`. That means a cached MP4
got truncated: the file's atoms reference offsets past the actual
EOF, so ExoPlayer crashes mid-playback.

The root cause is in the v0.1.39 resumable-download code (and the
v0.1.51 Updater clone). OkHttp's input-stream `read()` returns
`-1` cleanly when a stalled connection cuts mid-body — no
exception, no caller signal. The retry loop catches IOException;
clean EOF wasn't one. We rolled the (truncated) `.part` straight
into the final `.mp4`. ExoPlayer then walked the MP4 box tree and
tried to read past where the bytes actually ended.

`VideoCache.streamBodyToPart` and `Updater.streamBodyToPart` now
record bytes-read-from-body and compare against
`Response.body.contentLength()` after the loop. If short, throw
`IOException("Premature EOF: got N of M body bytes")`. The outer
retry loop catches that, issues a fresh `Range: bytes=<existing>-`
request, and fills the missing tail. The `.part` is never renamed
into place unless the full body was received.

Only fires when the server gave us a `Content-Length` — chunked
transfers can't be verified this way, but the modern Drive-stream
proxy + GitHub release CDN both set Content-Length.

**For an already-corrupt cached video on a tablet right now:** the
fix doesn't auto-heal it — the bad file sits on disk and we never
re-download. Push **Clear cache** from the CMS for that screen and
the next playlist fetch re-downloads from scratch with the new
guard in place.

### Add-content commit button was offscreen on long video lists

v0.1.49's multi-select picker added a "Cancel" + "Add N videos"
pair to the bottom row. The grid above wasn't height-bounded, so
on brands with 20+ videos it pushed the row off the bottom of the
viewport. You could tick a video (it got the green border + ✓) but
no Add button was visible to commit.

`LazyVerticalGrid` in both `VideoPickerScreen` and `BrandPickerScreen`
now uses `Modifier.weight(1f)` so it claims exactly the height
left over for it, scrolling internally when the list is long.
The footer Row stays anchored at the bottom regardless.

---

## v0.1.52

Auto-updates only run overnight.

Pre-v0.1.52, the background updater polled every 6 hours regardless
of clock. Two of those six ticks landed during business hours, so a
new release could trigger an APK download + install prompt at e.g.
14:30 in the middle of a shopper-facing playback loop. Annoying at
best, customer-facing at worst.

The background loop now gates each tick on the local clock — only
checks for an update when the hour-of-day falls inside **22:00 –
05:59** (the device's local time). Outside that window the loop just
logs "Skipping auto-update — outside window" and goes back to sleep.

**Manual triggers are unaffected.** All three explicit paths —

- CMS Screen-detail "Update" command
- Staff overlay → Device admin → Update
- Tablet command palette `/update`

— call `checkAndUpdate` directly and bypass the gate. If you want a
screen updated right now, that still works any time of day.

Polling cadence dropped from 6h to 2h so the loop catches more of
the night window in case a tick falls just outside it.

The window's hardcoded for now (`AUTO_UPDATE_START_HOUR=22`,
`AUTO_UPDATE_END_HOUR=6` in [Updater.kt][1]); if a store needs a
different schedule we can promote it to a server-pushed per-screen
setting later.

[1]: player/app/src/main/java/com/smartech/screens/update/Updater.kt

---

## v0.1.51

Resumable APK download in the in-app updater.

Same root cause v0.1.39 fixed for the video cache, but the updater
was on its own path: shared OkHttp client with a 60 s callTimeout, a
single attempt, and a `partial.delete()` on any IOException. On a
1 MB/s event-wifi link that's just inside the timeout for a 4 MB
APK — drop into 100 KB/s and the update fails.

The updater now mirrors the VideoCache pattern:

- **Dedicated downloader client** — same connection pool + auth
  interceptors as the shared client, but no callTimeout and a longer
  read timeout. The big payload is no longer racing a clock built
  for tiny JSON.
- **`.part` files survive across attempts AND across process
  restarts** — the cleanup pass now keeps the partial for the
  version you're upgrading TO and only deletes APKs / partials
  belonging to *other* versions.
- **Range-resume on retry.** Each attempt sends
  `Range: bytes=<existing>-`. 206 appends; 200 means the server
  ignored Range so we restart cleanly; 416 wipes + retries.
- **Capped exponential backoff** — 6 attempts at 1.5 s → 3 s → 6 s →
  12 s → 24 s → 30 s. Each attempt picks up exactly where the
  previous one stopped, so a connection that drops every 30 s
  eventually finishes the download instead of looping byte-zero.

Translation: a legacy box on flapping wifi that previously failed
the in-app update now resumes through the drop and completes. If
the user reboots the tablet mid-download (or it crashes), the next
launch picks up the .part and continues.

---

## v0.1.50

Auto-group-by-storeId is gone.

The v0.1.11 default was: tablet registers with `location.storeId` →
server drops it into `store:<storeId>`. Helpful in the original
"every screen in this store needs to be in sync" use case, but it
kept biting in the opposite case — tablets ending up grouped after
an APK update or fresh registration just because they happened to
share a storeId. v0.1.40 carved out events/test; v0.1.50 just kills
the whole behaviour.

Sync groups are now strictly **opt-in**. The two ways a screen joins:

- The on-tablet **Join** picker (staff overlay → content / device
  admin → Sync group card, from v0.1.36).
- The CMS Screen-detail Sync-group input.

The register handler no longer touches `syncGroup`. A migration on
server boot clears every existing `store:*` group from `_per_screen`
— custom group names the operator explicitly typed (e.g. `wall-A`)
don't start with `store:` and stay intact.

If you genuinely want every screen at a store to sync, group them
yourself once via the Join picker — the new sync-group UI from v0.1.36
makes that one tap per tablet — and they'll stay grouped across
updates because nothing auto-clears explicit choices.

---

## v0.1.49

### Multi-select Add content on the tablet

Brand → Video flow was one-tap-per-video before: pick a video → wait
on the Success screen → Back → pick another. With four or five
videos to add at the start of a shift, that's a slow ritual.

The video picker is now tap-to-toggle. Selected cards get a green
border + ✓ badge; the left-rail subtitle counts what's selected; a
prominent **Add N videos** pill at the bottom commits the whole batch
in one append. Single-add still works — pick one card, tap Add.

`StaffOverlay` pushes the full batch via the existing
`pushPlaylistToServer(..., mode="append")` call site, which already
got the v0.1.48 optimistic-insert treatment — so all picks appear in
the playlist within one frame, downloads start immediately, and the
Success screen reads "Added 3 videos" + the first title with "and 2
more" suffix.

### Playback watchdog names the offending video

When the watchdog tripped before, log lines just said
`Position stalled at 12345ms (tick 1/2)`. With a 30-video loop that
gave you no idea *which* video was causing the kick — you'd have to
correlate timestamps against /api/state's `playback.itemId` field.

`PlaybackWatchdog` now takes a `currentItemLabel` provider. Every
stall log, every escalation reason, and the ExoPlayer error listener
now read e.g.:

```
Position stalled at 12450ms on 'SONOS Era 300 (sonos-3)' (tick 2/2)
Watchdog KICK — position stuck on 'SONOS Era 300 (sonos-3)'
ExoPlayer error on 'SONOS Era 300 (sonos-3)': SOURCE_IO — Connection reset
```

Plus a per-item kick counter: when the same `mediaId` racks up 3
watchdog kicks across loops, the watchdog logs a distinctive
**Repeat-offender video** line so the operator can grep the JSONL
log (or skim Recent activity in the CMS) and find the bad clip in
seconds.

No automated removal — a "repeatedly stalling" clip might just be a
flaky CDN edge that fixes itself — but you now have the breadcrumb
you need to spot it.

---

## v0.1.48

Three small but visible staff-flow fixes.

### Visible Back / Cancel buttons on Add content

The Brand and Video picker screens had "Back" and "Cancel" rendered
as **muted-gray text**. On a TV across the room that read like a
label, not a button — staff couldn't see how to back out of the Add
content flow (other than via the remote's Back key, which not every
TV remote even has). The remote's Back still works; the screen
itself just didn't say so.

Both now render as proper bordered pills, matching the **Done** /
**Refresh now** styling everywhere else in the staff overlay. Back
gets a `← ` glyph so it's unmistakable.

### Picked video appears on the playlist immediately

Picking a video used to show the Success screen, then the playlist
re-fetched on the next /api/state poll (60 s on Normal, 5 min on
Slow) before the new item was visible. Staff who clicked back to
the playlist before that lost the visual confirmation.

`pushPlaylistToServer(mode="append")` now mutates the local
`intendedPlaylist` flow optimistically. The row appears in the
playlist within one frame, picking up the existing download-progress
badge. The next server poll reconciles silently. If the server push
failed, the next poll just snaps back to the server's view.

The optimistic insert also kicks off the video's download
immediately via `cache.ensure`, so on Slow-mode tablets staff don't
watch a static "queued" badge for 5 minutes — bytes start flowing
the moment they pick.

### Update on legacy tablets — installer fallback

> `Couldn't launch installer: No activity found to handle Intent
> { act=android.intent.action.VIEW dat=content://…/screens-v0.1.47.apk
> typ=application/vnd.android.package-archive flg=0x10000001 }`

Some Amlogic / cheap-TV-box ROMs (Sumvision Cyclone, TX3 Mini)
don't register the system PackageInstaller against the modern
`ACTION_VIEW + content://` intent shape. `Updater.launchInstaller`
now tries a fallback chain and picks the first one that resolves:

1. `ACTION_VIEW + content://` — current Android default.
2. `ACTION_INSTALL_PACKAGE + content://` — deprecated in API 29
   but still wired on older / off-brand ROMs.

We call `PackageManager.resolveActivity` before each attempt so we
can pick a known-good intent rather than relying on `startActivity`
to throw and recover from the exception.

If both candidates fail, the failure message now tells the operator
*where* the APK landed on disk so they can install it manually via
a file manager or `adb install`.

(Note: legacy tablets currently on v0.1.45 or earlier still need
one manual sideload of v0.1.48 to pick up the fix, since the
in-app updater fix has to be in the *currently-installed* build.)

---

## v0.1.47

Drive sync Phase 3 — apply change diffs to a cached inventory.

### What v0.1.46 still spent time on

After Phase 1+2 landed:
- **Idle sync**: ~1 s (changes.list only).
- **Sync with N changes since last cursor**: ~1-3 s (broad query
  re-fetches the whole drive inventory just to learn that one new
  video appeared).

The waste in the second case is obvious — we have a `changes.list`
result telling us exactly which file IDs moved; we don't need to
re-fetch everything.

### What v0.1.47 does

After every successful scan we persist the full broad-query inventory
to `drive_inventory_snapshot.json` (~100-300 KB).

On the next scan, if `changes.list` returns a manageable number of
changes (≤ 50):
1. Concurrently `files.get` metadata for the changed IDs only
   (8-thread pool — Drive's per-file metadata endpoint is rate-limited
   per *file*, so parallel fan-out is safe and fast).
2. Drop removed / trashed / inaccessible IDs from the cached snapshot.
3. Upsert the rest.
4. Re-classify the patched snapshot via the same brand-by-parent-chain
   logic the broad query uses.

Cost scales with `num_changes`, not `inventory_size`. A typical
"someone uploaded a new video" sync becomes:
- 1 `changes.list` call (~300 ms)
- 1 parallel `files.get` (~300 ms)
- 0 broad-query time
- Total ~600-800 ms.

Anything that breaks along the way — missing snapshot, drive-id
mismatch, > 50 changes, fetch error — falls through cleanly to the
v0.1.46 broad-query rebuild. The fast path is purely additive; the
v0.1.46 baseline is still the correctness floor.

### Side effects worth knowing

- `changes_since` now returns the list of `{fileId, removed}`
  records, not just a count. Internal API only.
- `drive_inventory_snapshot.json` is auto-created next to `library.json`
  (under `SCREENS_LIBRARY_PATH`); override with
  `SCREENS_DRIVE_INVENTORY_PATH` if you want it elsewhere.

---

## v0.1.46

Drive sync is dramatically faster.

### Phase 1 — single-sweep whole-drive query

The cloud Drive walker used to make ~one API call per brand folder
(`list_subfolders`) plus ~one per nested subfolder of each brand
(`list_videos_recursive`). On a seven-brand fleet that's 70+ sequential
calls × ~300 ms latency each — minutes of wall-clock for what's mostly
"are there any new mp4s here?".

The new path resolves the shared-drive ID from the brand-content
folder, then makes a single broad query (`corpora='drive'`,
`driveId=<id>`) for every folder and video in the entire drive.
Brand classification happens client-side from the `parents` field.

Same `library.json` output, ~5-10× fewer Drive round-trips. Falls
back to the legacy recursive walker if the content isn't in a shared
drive (My Drive content has no `driveId`).

### Phase 2 — change-token short-circuit

Most syncs find nothing new. The scanner now persists a Drive cursor
(`drive_change_token.json`, next to `library.json`) after each
successful run. On the next scan we call `changes.list` first; if
Drive reports **zero** changes since the cursor, the scanner exits in
~1 second without re-fetching the inventory. `library.json` stays
exactly as it was.

When changes *are* reported, we fall through to the Phase 1 broad
query and persist a fresh cursor.

The daily auto-sync uses the short-circuit. The manual **Sync now**
button always forces a full scan — that's what an operator means
when they click it.

### Net effect

- Idle daily auto-sync: minutes → ~1 second.
- Manual Sync now on an unchanged drive: same as before.
- Manual Sync now after uploads: minutes → seconds.

(Phase 3, parallel within a single scan, will come in v0.1.47 — it's
mostly redundant after Phase 1+2 but worth a small extra tick on
big content drops.)

---

## v0.1.45

**Drive Sync card → Refresh directory button.**

Sits next to **Sync now** on Settings → Drive sync. Hits
`/api/library` to re-pull the current directory tree the server
already has on disk, then fires a `library-refresh` event so the
Content Library page (and anything else listening) drops its cache
and re-renders.

Crucially it does **not** trigger a new Drive scan — that's still
what **Sync now** does, and it can take minutes on a full
seven-brand walk. Refresh directory is sub-second.

Use it when:
- Someone else just kicked off a sync and you want to see the result
  without spawning another scan.
- The Content Library shows stale entries (cache out of step with
  the server's library.json).
- You're debugging a sync and want to see the server-side state
  without re-running the slow Drive walk.

---

## v0.1.44

Third hotfix in the v0.1.39 cascade. Each release shipped fine
locally but failed on a CI runner with a newer Kotlin Gradle Plugin
that promoted warnings to hard errors.

This one was in `VideoCache.reconcile()` — the v0.1.39 orphan
.part-file cleanup used `id !in inflight` where `inflight` is a
`ConcurrentHashMap<String, Boolean>`. The `in` operator on a
ConcurrentHashMap calls `Map.contains()`, which Kotlin flags as
ambiguous because for that one type it could mean `containsKey` OR
`containsValue`. Older KGP emitted a warning; newer KGP turns it
into a compile error.

Switched to the explicit `!inflight.containsKey(id)`. Same
behaviour, no ambiguity.

---

## v0.1.43

Second hotfix after v0.1.42 — release builds were still failing.

After fixing the deprecated `kotlinOptions` DSL in v0.1.42, the next
build still hit:

```
ERROR: AAPT: Cannot filter assets for multiple densities using SDK
build tools 21 or later. Consider using apk splits instead.
```

Same v0.1.41 mistake: the `resourceConfigurations` list had multiple
density tokens (`en`, `xhdpi`, `xxhdpi`, plus per-flavor `xxxhdpi` /
`mdpi` / `hdpi`). AAPT2 only allows multi-density filtering when
you're using APK splits, which we don't.

Dropped the density tokens. Kept the locale filter (`"en"` only) —
that's where the real APK-size win was anyway: stripping the
localised string bundles AndroidX libs pull in. The legacy build
still gets the other v0.1.41 wins (vector drawable support library,
Kotlin null-check stripping via the v0.1.42 compilerOptions DSL).

---

## v0.1.42

Hotfix — release builds were failing in CI from v0.1.39 onward.

v0.1.41 added a `tasks.withType<KotlinCompile>().configureEach`
block that used the deprecated `kotlinOptions { freeCompilerArgs +=
... }` DSL to strip Kotlin's null-check intrinsics. The newer Kotlin
Gradle Plugin promoted that call to a hard error rather than a
deprecation warning, so the Android compile step failed and no APKs
were published for v0.1.39 / v0.1.40 / v0.1.41 even though their
git tags exist.

Switched to the supported `compilerOptions.freeCompilerArgs.addAll(...)`
API. Same intrinsic-stripping behaviour, same release-build-only
scope, just the current DSL.

(The other deprecation warnings on `resourceConfigurations` and the
`android { kotlinOptions { ... } }` block are still just warnings;
they don't fail the build and stay for now.)

---

## v0.1.41

Legacy-build polish + a first-boot fix that hit legacy hardest.

### "Not configured" no longer lingers on first boot

A fresh first boot used to sit on "Not configured" until the first
poll tick landed — on the legacy flavor that's now 5 minutes (Slow
default), so the screen looked broken for a long stretch.

Root cause: a race between the URL pre-seed coroutine in
`ScreensApp.onCreate` and the polling loop reading the URL. The
polling loop now pre-seeds the URL inline before its first read, and
flips connection state to **Connecting…** straight away instead of
**Not configured**. First-boot wall-clock to "Online" drops from
~5 min to ~5 s.

### Legacy APK size + perf

- **`resourceConfigurations`** filter: legacy now only ships
  `en + mdpi/hdpi/xhdpi/xxhdpi` mipmap/strings. xxxhdpi is dropped
  on legacy (those boxes don't have the screen density for it),
  Material3's localised strings drop to English only. Modern keeps
  xxxhdpi.
- **`vectorDrawables.useSupportLibrary = true`** for both flavors so
  vector assets render via the support library codepath on the older
  Android Graphics stack.
- **Kotlin null-check intrinsics stripped from release builds.**
  `-Xno-call-assertions / -Xno-receiver-assertions /
  -Xno-param-assertions` skip the auto-generated null-check method
  prologues Kotlin inserts on every public-API call. We control both
  ends of every type that crosses these boundaries in this app;
  modern hardware doesn't notice the removed instructions, but the
  legacy Cyclone's slow CPU does. Debug builds keep the assertions
  for tooling friendliness.

---

## v0.1.40

Two event-day fixes.

### Stop auto-grouping Events + Test screens

When a tablet registered with `storeId = "events"` or `"test"` the
server was helpfully dropping it into `store:events` / `store:test`
sync groups — the same default that's correct for real retail stores
(every screen on the same wall should be in step) but very wrong for
catch-all stores where each tablet is usually showing different
content.

The auto-group logic now exempts those two store ids. Tablets can
still **opt INTO** a group from the staff-overlay Join picker if they
do want one. A one-shot migration runs at server boot to clear the
auto-set group from any previously-affected screen.

### Legacy update installs the legacy APK

The in-app updater always grabbed `modernUrl` from the release
endpoint, regardless of which flavor the tablet itself was running.
Legacy tablets that hit Update wound up downloading the modern APK,
which won't install on Android 6/7.

The updater now picks `legacyUrl` when `BuildConfig.FLAVOR == "legacy"`
and falls back to a clear "no legacy APK in this release — sideload
required" message when a release was built modern-only via
`workflow_dispatch`. Modern tablets are unaffected.

---

## v0.1.39

Resumable video downloads — spotty in-store wifi no longer means
re-downloading the same 300 MB clip from byte zero.

### What changed

- **`.part` files survive across attempts and restarts.** The init
  block in `VideoCache` used to reap every `.part` file on startup
  (so the app didn't ship a half-written video). It now leaves them
  alone — they're the resume marker.
- **Range-resume on retry.** When a download attempt finds existing
  bytes in `<videoId>.mp4.part`, the next request sends
  `Range: bytes=<existing>-`. A `206 Partial Content` appends to the
  file; a `200 OK` (server ignored Range, e.g. older CDN) truncates
  and restarts from zero; `416 Range Not Satisfiable` wipes the
  partial and retries.
- **Capped exponential backoff.** Up to 6 attempts per download with
  delays 1.5s → 3s → 6s → 12s → 24s → 30s. Each attempt picks up
  where the previous one stopped — so a connection that drops every
  20 seconds still eventually finishes, instead of looping forever
  re-fetching the first few MB.
- **Dedicated downloader HTTP client.** The shared OkHttp client has
  a 60-second call timeout (correct for API requests). For a 300 MB
  video on 1 Mbps wifi that's hopelessly tight. `VideoCache` now
  spins its own client off the shared one — same connection pool +
  interceptors — with no call timeout and a longer read timeout.
- **`reconcile` reaps orphan `.part` files.** Custom stores aside,
  the cleanup pass on playlist refresh deletes `.part` files whose
  video id is no longer in the playlist *and* not currently inflight.

### What this means in practice

A tablet that loses wifi mid-download will resume from the next
attempt instead of starting over. If the tablet is restarted, the
next launch picks up the existing `.part` and continues. The CMS
"Recent activity" warnings about repeated download failures should
drop noticeably at events with weak coverage.

---

## v0.1.38

Add new stores from the CMS — no APK rebuild needed.

### CMS Settings → Locations → Stores

The Stores list is now editable. **+ Add store** opens an inline form
with name, address, city (dropdown from the existing taxonomy), and a
kebab-case id that auto-suggests itself from the name as you type.
Save commits via the new `POST /api/stores` endpoint and the row
appears in the list immediately.

Custom stores get a **Remove** button. Built-in stores (Times Square,
Selfridges, KaDeWe, Rinascente, Events, Test) are tagged **built-in**
and can't be deleted from here — they live in source and ship with
every APK.

### Server: `/api/stores` endpoints

- `GET /api/stores` — public read, returns the custom additions only
  (built-ins are baked into both clients).
- `POST /api/stores` — gated on `settings.edit`. Validates kebab-case
  id (2-63 chars), rejects collisions with built-ins and existing
  customs, persists to `custom_stores.json` on the FUSE-mounted
  bucket so deploys don't wipe additions.
- `DELETE /api/stores/<id>` — gated on `settings.edit`. 404s on
  built-in ids since they aren't in the dynamic dict.

### Tablet pulls custom stores on launch

`PlayerRepository.startLiveSync()` now fires a one-shot fetch to
`/api/stores` after registration and feeds the result into
`LocationTaxonomy.setCustomStores()`. The on-tablet store picker
(Device admin → Location → Store) reflects the additions on next
app launch without needing a new APK. Built-ins always win on id
collision.

---

## v0.1.37

Easier admin access, faster legacy poll cadence, two new stores.

### Bigger corner-tap area for admin unlock

The four-corner tap sequence (TL → TR → BR → BL) that opens the staff
overlay now accepts taps anywhere within **180 dp** of each corner,
up from 96 dp. On a 1080p TV that's roughly **45 mm** of landing pad
vs. 24 mm previously — staff stop missing the corner and resetting
the sequence. Small enough that a stray tap on the lower-third
doesn't trigger it.

### Slow poll mode is now 5 minutes (was 10)

10 minutes meant a pushed playlist could lag visibly behind for staff
watching the wall. 5 minutes is still cellular-friendly but cuts the
worst-case "I just pushed and it hasn't landed" wait in half. The
wire value is unchanged (`slow`), so older servers + tablets keep
working with the new label.

### Legacy APK defaults to Slow

The legacy flavor (Android 6/7 hardware, usually on stretched event
wifi) now starts in Slow poll mode by default — both locally on the
tablet AND server-side on first registration. Modern still defaults
to Normal. Operators can change either from the CMS or the staff
overlay; the default only applies to brand-new screens.

Heartbeat + register now ship `appFlavor` so the server knows which
APK each screen is running. Useful for crash triage too.

### Two new stores: Events + Test

Added to the location taxonomy under a new **GLOBAL** region (with a
**GLB** city code):
- **Events** — pop-up + event installations.
- **Test** — development + QA fixtures.

A CMS-side "add new store" form is still pending — for now, more
stores need an APK + data.jsx update.

---

## v0.1.36

Join + leave sync groups from the tablet without going back to the CMS.

### Tablet content page → Sync group card

The Sync group card (added in v0.1.35) now also lives on the content
("What's on this screen") page, between the playlist and the footer
actions. Same card the Device admin shows — staff editing a playlist
can glance down and see who they're locked to without paging through
admin first.

### Join + Leave from the card

The card now has two states:

- **In a group** — header gets a red **Leave** button next to
  Calibrate. Tapping Leave confirms, then POSTs `syncGroup: null` for
  this tablet's deviceId. The screen falls back to independent
  playback on the next poll (instant via `refreshNow()`).
- **Not in a group** — instead of being hidden, the card shows a row
  per existing sync group on the fleet with a member count, an
  online count, and a **Join** button. Tapping Join POSTs that
  group ID for this tablet. No typing — every row is a focus
  target so a remote works.

Creating a brand-new group stays a CMS task (Screens → pick a screen
→ Sync group → type a new name). The tablet picker only lists groups
that already exist on the fleet, which matches how the CMS UI works.

### Server

`/api/state` now ships `availableSyncGroups: [{id, memberCount,
onlineCount}]` — a cheap O(N) projection of distinct group values
across every registered screen. That powers the tablet's Join
picker and keeps the wire format obvious for anyone debugging.

---

## v0.1.35

Sync groups visible everywhere a sync group should be visible.

### CMS command palette

`/` → "go to sync groups" (or any of `sync` / `groups` / `stores`)
now navigates to the new v0.1.34 Sync groups page. One-line nav
entry; same `screens.view` permission as the sidebar item.

### Tablet Device admin → Sync group card

The Device admin overlay used to show sync-group membership as
one line in the Info card: `Sync group: store:NYC1`. That tells
you you're in *a* group but not *who else* is in it.

v0.1.35 adds a dedicated **Sync group** card below Device info
(rendered only when the screen IS in a group — the Info row
above still reads "(independent)" otherwise). Header carries a
Calibrate button that fires the 60-second synchronised clock on
every member, same as the in-CMS one. The card body lists each
member with:

- A green/grey dot — online if the server saw a heartbeat in the
  last 15 s, muted otherwise
- Name + store/screen code
- A small `this screen` tag on the local row so the operator can
  find themselves in the list

### Server: one new field on `/api/state`

The handler for a screen-id'd state response now includes:

```json
"syncGroupMembers": [
  {"deviceId": "...", "name": "...", "online": true,
   "screenCode": "...", "storeId": "...", "isSelf": true}
]
```

Self is sorted first so the local row always renders at the top
of the card. Empty list when the screen isn't grouped. Added to
`/api/state` rather than a new endpoint so the tablet doesn't
need a second network round-trip per poll.

CMS + tablet release.

## v0.1.34

New top-level **Sync groups** view in the CMS. Up to now you could
only see which screens belonged to which group by opening a single
screen's detail page and reading its sync card. That answers "what
group is *this* screen in" but not "what groups exist across my
fleet" — the question every store manager actually asks.

### What the page shows

For each sync group:

- The group id + current playlist revision
- Member count + how many are online (green when 100 % healthy)
- Number of videos in the shared playlist
- A **Calibrate** button — fires the 60-second synchronised clock
  overlay on every member, same as the in-card button on Screen
  detail
- An **Open first member** link as a quick jump to Screen detail
  for inspecting / leaving the group

Below each summary, a row per member showing online dot,
name + store/screen code, and current revision — clicking any row
opens that screen's detail page.

### Plus the orphan list

Underneath the groups, a separate card lists every screen that's
**not in any sync group**. Useful for "why isn't the front-shop
tablet syncing?" — if it's in the orphan list, it's playing
independently. Click through to its detail page to join it.

### No new server endpoint

Derived client-side from `/api/screens`, which already returns
each screen's `syncGroup` field. `useLiveScreens` was already
polling that endpoint, so the page reuses the existing flow with
no new network surface and no new permissions to manage. Same
`screens.view` gate as the main Screens list.

### Sidebar entry

Between Screens and Schedules, with the brand sync icon. Gated
on `screens.view` (anyone who can see the fleet can see the
groupings).

CMS-only release.

## v0.1.33

"Mix splash keeps turning itself off when I select it" — fixing
the UX, not the underlying behaviour.

### The behaviour you were seeing

Since v0.1.11, the server forces `mixSplash: false` in `/api/state`
for any screen with a `syncGroup`. The splash's extra duration
breaks the loop math the tablet uses to stay frame-locked with
the rest of the group — letting it play would steadily drift the
group apart. The stored preference is preserved, so leaving the
group restores splash behaviour, but while you're in a group the
tablet always sees `false` on every poll.

The toggle, meanwhile, was always enabled. You tapped it ON, the
server stored ON, the next poll arrived with `false`, the flow
flipped back, and the toggle popped OFF. Looked broken.

### What changed

The toggle is now visibly disabled when the screen is in a sync
group, in three places:

- **Tablet playlist view** — the toggle dims to ~35% opacity and
  the sub-label reads "Disabled — screen is in sync group 'X'.
  Leave the group to mix splash." `DarkToggle` now respects its
  `enabled` flag visually instead of just blocking clicks.
- **CMS Screen detail → Display card** — same treatment. Toggle
  shows OFF + greyed, sub-label reads "Disabled — screen is in
  sync group 'X'. Mix splash breaks the group's loop math; leave
  the group to enable."
- **Tablet `/` command palette** — the Mix splash command label
  becomes `Mix splash (locked — in sync group)` and pressing
  Enter on it logs a warning instead of firing a request that
  would just be overridden on the next poll.

### Behaviour summary

| Screen state | Mix splash UX |
|---|---|
| Solo (no sync group) | Toggle works normally, value persists |
| In a sync group | Toggle visibly locked OFF with explanation |
| Leaves the group | Stored preference restores instantly |

### No server change

The server's override stays — sync still relies on it. v0.1.33
is pure client UX so the locking is transparent instead of
silent. Tablet + CMS.

## v0.1.32

Four more commands in the tablet `/` palette. The catalogue now
covers most things an operator wants without having to walk
through the PIN-gated admin overlay.

### What's new

| Command | PIN? | What it does | Useful filters |
|---|---|---|---|
| **Update player APK** | No | Triggers the in-app updater immediately — same flow as the CMS "Update player APK" button + the 6h auto-check. | `/update` · `/upgrade` |
| **Mute / Unmute screen audio** | No | Toggles the screen-wide audio flag. Label tracks live state — shows "Mute" when audio is on, "Unmute" when it's off. | `/audio` · `/sound` |
| **Mix splash / Stop mixing splash** | No | Toggles the splash-between-videos behaviour. Same dynamic-label treatment. | `/splash` · `/branding` |
| **Reboot screen** | No | Restarts the player activity in place (cache + registration survive). For when ExoPlayer gets visibly wedged. | `/reboot` · `/restart` |

Plus existing commands (unchanged): Refresh playlist now, Show
calibration clock (60 s), Open device admin (PIN-gated).

### State-aware labels

`Mute screen audio` and `Unmute screen audio` swap based on
`repository.audioOnFlow` so you don't see "Mute" when audio is
already muted. Same for `Mix splash` / `Stop mixing splash`. The
`commands` list re-keys on those flows, which is cheap (~6 items)
and means the palette is always honest about what Enter will do.

### Filter keyword tags

Each command's `hint` field now embeds the common search terms an
operator might type — `/update`, `/upgrade`, `/audio`, `/sound`,
`/splash`, `/branding`, `/reboot`, `/restart`. The existing
word-AND filter picks them up automatically, so typing `/audio`
from the palette finds the toggle even if you don't remember the
exact label.

### Reboot intentionally not PIN-gated

The reboot path just relaunches the activity; cache + registration
survive. Anyone with physical access to the box can power-cycle
it anyway. Hiding the in-app version behind PIN would have made
the palette feel coy without adding real security. The `Open
device admin` command stays PIN-gated for the truly destructive
actions (Clear cache, Reinitialise, Location reset).

Tablet-only.

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
