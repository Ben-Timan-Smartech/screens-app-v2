"""
Demo server for the Screens CMS.

Serves two trees:
    /            → app/                              (the CMS)
    /media/...   → G:\\Shared drives\\Smartech\\Screens\\Brand Content\\... (videos)

Why custom: the standard `python -m http.server` doesn't support HTTP Range
requests, and HTML5 `<video>` issues range fetches when seeking. Without
range support, seek bars don't work and large files re-download from byte 0
on every interaction. The `serve_media` path below handles 200/206 properly.

Run from the project root:
    python serve.py
"""

from __future__ import annotations

import http.server
import json
import os
import re
import shutil
import socket
import socketserver
import sys
import threading
import time
import urllib.parse
from pathlib import Path

# Drive integration is import-guarded — local dev without google libs
# installed still runs. drive_client.is_configured() returns false when
# the env doesn't ask for cloud mode, in which case nothing in this
# module's code paths actually calls Drive.
try:
    import drive_client
except ImportError:
    drive_client = None  # type: ignore

# Persistence + auth. Both modules side-effect on import: db.init() applies
# schema migrations, auth.ensure_owner_seeded() inserts the Owner row if
# no Owner exists yet. Keep them imported here so a fresh boot is ready
# to authenticate first request.
import db          # noqa: E402  (after drive_client import-guard above)
import auth        # noqa: E402

PROJECT = Path(__file__).resolve().parent
APP_DIR = PROJECT / "app"
BRAND_DIR = PROJECT / "brand"           # logos, favicons, wordmark

# App version, read once at module load from the VERSION file at the repo
# root. Surfaced via /api/auth/me so the CMS sidebar can show "v0.1.2"
# and admins can tell at a glance which release is running. Falls back
# to "dev" when the file isn't present (e.g. running outside a checkout).
def _read_version() -> str:
    try:
        return (PROJECT / "VERSION").read_text(encoding="utf-8").strip() or "dev"
    except OSError:
        return "dev"

APP_VERSION = _read_version()

# ── Latest-release lookup ────────────────────────────────────────────
# Both the CMS login screen and the Android player ask "what's the
# newest APK URL?". We answer by hitting GitHub's REST API for the
# latest release on this repo, picking out the modern + legacy APK
# assets, and exposing a server-side proxy URL for the actual binary
# download.
#
# Why proxy instead of returning github.com URLs directly: the source
# repo is private, so anonymous downloads (the login page button) and
# unauthenticated devices (tablets without GitHub credentials) can't
# fetch directly. The proxy uses SCREENS_GITHUB_TOKEN (a fine-grained
# PAT with read access to releases) to fetch from GitHub and stream
# the bytes back. With a public repo it'd still work but the token
# would be unnecessary.
#
# The repo is hard-coded here because it's where serve.py itself lives
# — overridable for forks via SCREENS_RELEASES_REPO.
GITHUB_RELEASES_REPO = os.environ.get(
    "SCREENS_RELEASES_REPO",
    "Ben-Timan-Smartech/screens-app-v2",
)

import urllib.request  # noqa: E402

_release_cache: dict = {"data": None, "fetched_at": 0.0}
_release_cache_lock = threading.Lock()
RELEASE_CACHE_TTL = 300  # 5 minutes


def _github_headers(*, accept: str = "application/vnd.github+json") -> dict:
    """Common headers for any GitHub API call. Adds the bearer token
    when SCREENS_GITHUB_TOKEN is set — without it, private repos 404."""
    h = {
        "Accept": accept,
        "User-Agent": "screens-app-v2-server",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("SCREENS_GITHUB_TOKEN")
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def _fetch_latest_release() -> dict | None:
    """Hit GitHub's API for the latest release. Returns the parsed JSON
    or None on any error. Errors are swallowed because the consumers
    (login page, player) both have graceful "release info unavailable"
    paths — they should never crash because GitHub is briefly slow."""
    url = f"https://api.github.com/repos/{GITHUB_RELEASES_REPO}/releases/latest"
    req = urllib.request.Request(url, headers=_github_headers())
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[release] fetch failed: {e}", file=sys.stderr)
        return None


def _release_info() -> dict:
    """Return {tagName, versionName, versionCode, modernUrl, legacyUrl,
    publishedAt, releaseUrl} for the latest release, or {} if we can't
    reach GitHub. Cached for RELEASE_CACHE_TTL seconds.

    `modernUrl`/`legacyUrl` are *proxy* URLs pointing back at this
    server (so private-repo assets still resolve for anonymous + device
    clients). Asset IDs are kept on the cached dict with a leading
    underscore so the download handler can look them up without
    re-fetching from GitHub."""
    now = time.time()
    with _release_cache_lock:
        if _release_cache["data"] is not None and (now - _release_cache["fetched_at"]) < RELEASE_CACHE_TTL:
            return _release_cache["data"]
    raw = _fetch_latest_release()
    if not raw:
        # Don't poison the cache with empty data — let the next request retry.
        return {}
    tag = raw.get("tag_name") or ""
    version_name = tag[1:] if tag.startswith("v") else tag
    # Match: MAJOR.MINOR.PATCH → MAJOR*10000 + MINOR*100 + PATCH (mirrors
    # the formula in player/app/build.gradle.kts so the player can
    # compare server-reported versionCode to its own BuildConfig).
    try:
        major, minor, patch = (int(p) for p in version_name.split(".")[:3])
        version_code = major * 10_000 + minor * 100 + patch
    except (ValueError, TypeError):
        version_code = 0
    assets = raw.get("assets") or []
    def _find_asset(flavor: str) -> dict | None:
        for a in assets:
            if flavor in (a.get("name") or "").lower():
                return a
        return None
    modern_asset = _find_asset("modern")
    legacy_asset = _find_asset("legacy")

    # All download URLs point at our own /apk routes. The bytes still
    # come from GitHub but they're proxied through Cloud Run, which:
    #   • Bypasses corporate networks that allow `github.com` but
    #     block `release-assets.githubusercontent.com` (a common
    #     restriction we've seen in the wild).
    #   • Gives a stable filename + same-origin URL so the browser
    #     starts the download instantly instead of loading a GitHub
    #     page in between.
    # The cost is some Cloud Run egress per download (~5 MB per APK,
    # rare), which is well inside the free tier.
    public = (auth.PUBLIC_URL or "").rstrip("/")
    def _apk_url(flavor: str) -> str:
        # /apk is the modern build; legacy gets its own subpath.
        path = "/apk" if flavor == "modern" else f"/apk/{flavor}"
        return f"{public}{path}" if public else path

    info = {
        "tagName":      tag,
        "versionName":  version_name,
        "versionCode":  version_code,
        "modernUrl":    _apk_url("modern") if modern_asset else None,
        "legacyUrl":    _apk_url("legacy") if legacy_asset else None,
        "publishedAt":  raw.get("published_at"),
        "releaseUrl":   raw.get("html_url"),
        "notes":        raw.get("body") or "",
        # Internal: asset IDs for the proxy endpoint. Leading underscore
        # is purely a "don't ship this to public consumers" hint — we
        # return the same dict to /api/auth/me, but no UI surfaces it.
        "_modernAssetId": modern_asset.get("id") if modern_asset else None,
        "_legacyAssetId": legacy_asset.get("id") if legacy_asset else None,
        "_modernSize":    modern_asset.get("size") if modern_asset else None,
        "_legacySize":    legacy_asset.get("size") if legacy_asset else None,
    }
    with _release_cache_lock:
        _release_cache["data"] = info
        _release_cache["fetched_at"] = now
    return info

# MEDIA_DIR / SPLASH_DIR are overridable via environment so the same code
# runs on a dev laptop (default Windows G:\ path) and inside a container
# where the Drive mount doesn't exist (e.g. Cloud Run). The defaults work
# locally; override SCREENS_MEDIA_DIR and SCREENS_SPLASH_DIR in deploy
# environments. If the path doesn't exist at startup, the server still
# boots — splash registry will just be empty until media is mounted.
MEDIA_DIR = Path(os.environ.get(
    "SCREENS_MEDIA_DIR",
    r"G:\Shared drives\Smartech\Screens\Brand Content",
))
SPLASH_DIR = Path(os.environ.get(
    "SCREENS_SPLASH_DIR",
    r"G:\Shared drives\Smartech\Screens",
))
# Library JSON path. Defaults to a sibling of the CMS source, which is
# fine for local dev (writes land in the checkout). On Cloud Run point
# this at `/data/library.json` so the Drive-Sync output survives
# container restarts — the FUSE-mounted bucket is the only writable
# place outside the ephemeral container filesystem.
LIBRARY_JSON = Path(os.environ.get(
    "SCREENS_LIBRARY_PATH",
    str(APP_DIR / "components" / "library.json"),
))

# Per-screen playlist state, tablet registry, and sync-group epochs
# default to siblings of LIBRARY_JSON so a single existing
# `SCREENS_LIBRARY_PATH=/data/library.json` env var pins all four
# files on the FUSE-mounted bucket. Without this auto-derivation the
# v0.1.5/v0.1.6 state files were silently writing to the ephemeral
# container filesystem on Cloud Run — wiped on every redeploy, even
# though the persistence code was in place. Explicit env vars still
# win when set (useful for local-dev split-paths or staging swaps).
def _state_path_default(filename: str) -> str:
    """Sibling of LIBRARY_JSON with the given filename."""
    return str(LIBRARY_JSON.parent / filename)

# v0.1.20: where uploaded videos go. Sibling of LIBRARY_JSON so a
# single env var pins everything to /data on Cloud Run, including
# the uploads dir — files live across container redeploys.
UPLOADS_DIR = Path(os.environ.get(
    "SCREENS_UPLOADS_DIR",
    _state_path_default("uploads"),
))

# v0.1.21: where tablet crash reports land. One JSON file per crash,
# named `<deviceId>-<crashTimeMs>.json` so listings sort by tablet
# then by time. Same FUSE-mount lineage so we don't lose evidence
# on a Cloud Run redeploy.
CRASHES_DIR = Path(os.environ.get(
    "SCREENS_CRASHES_DIR",
    _state_path_default("crashes"),
))

# v0.1.25: tablet warning/error log stream. One .jsonl file per
# deviceId, appended to as the heartbeat ships new entries. Keeps
# pre-crash + non-fatal evidence so the engineer can read it via
# /api/logs without waiting for the next uncaught exception.
LOGS_DIR = Path(os.environ.get(
    "SCREENS_LOGS_DIR",
    _state_path_default("logs"),
))

PER_SCREEN_JSON = Path(os.environ.get(
    "SCREENS_PER_SCREEN_PATH",
    _state_path_default("per_screen.json"),
))
SCREENS_JSON = Path(os.environ.get(
    "SCREENS_REGISTRY_PATH",
    _state_path_default("screens.json"),
))
SYNC_GROUPS_JSON = Path(os.environ.get(
    "SCREENS_SYNC_GROUPS_PATH",
    _state_path_default("sync_groups.json"),
))

# Cloud Run injects $PORT (defaults to 8080); on a laptop we keep 8765.
PORT = int(os.environ.get("PORT", "8765"))
BIND = "0.0.0.0"   # Listen on all interfaces so the tablet can reach us on LAN.

RANGE_RE = re.compile(r"bytes=(\d+)-(\d*)")

# ── In-memory live state ────────────────────────────────────────────
# Per-screen playlist + commands. Each registered tablet (keyed by deviceId)
# has its own state so the CMS can push to one or many independently.
#
# State model (per device):
#   revision, items, pushedAt, mixSplash, pendingCommands

_STATE_LOCK = threading.RLock()
_per_screen: dict[str, dict] = {}   # deviceId -> {revision, items, pushedAt, mixSplash, pendingCommands}
_screens: dict[str, dict] = {}      # deviceId -> registry (last heartbeat, device info, etc.)


# ── State persistence ────────────────────────────────────────────────
# Both dicts get atomically written to disk on every mutation. On Cloud
# Run the env-var-overridden paths land on the FUSE-mounted bucket so a
# redeploy (container restart) doesn't wipe playlists, registrations,
# or per-screen audio/splash flags. For ~50 tablets the files are <100kB
# each so writing on every heartbeat is fine. Atomic write = write to
# .tmp then rename; readers always see a complete file.

def _slug(s: str) -> str:
    """ASCII slug for filenames + library ids. Strips punctuation, joins
    runs of non-alnum to a single hyphen, lowercases, caps length. Used
    by the v0.1.20 upload endpoint to turn a user-provided title into
    something safe for disk + URL paths."""
    import re as _re
    out = _re.sub(r"[^a-zA-Z0-9]+", "-", (s or "").strip()).strip("-").lower()
    return out[:60] or ""


def _fmt_duration(sec: int) -> str:
    """Render seconds as `M:SS` for the library `duration` display
    field. Matches the shape scan-videos.py produces so the CMS doesn't
    have to special-case uploaded vs synced rows."""
    sec = max(0, int(sec))
    return f"{sec // 60}:{sec % 60:02d}"


def _parse_multipart(body: bytes, boundary: bytes) -> list[dict]:
    """Minimal multipart/form-data parser. Returns one dict per part:
        { name, filename?, content_type, data (bytes) }

    Used only by the upload endpoint. Holds the whole body in memory —
    upstream caller is responsible for size-capping via Content-Length
    before calling. No streaming because the v0.1.20 upload flow caps
    at 1 GiB and Cloud Run instances are 4 GiB.

    Trades robustness for clarity: handles the standard boundary-
    delimited shape produced by browsers + curl. Does NOT handle
    nested multipart (`multipart/mixed` inside a part) — irrelevant
    for a plain file upload."""
    delim = b"--" + boundary
    # Split on the delimiter. First chunk is the (empty) preamble; the
    # last chunk after the trailing `--` is junk we discard.
    chunks = body.split(delim)
    out: list[dict] = []
    for chunk in chunks[1:]:
        # Trailing boundary is "--\r\n" — sentinel for end-of-parts.
        if chunk.startswith(b"--"):
            break
        chunk = chunk.lstrip(b"\r\n")
        # Strip the trailing CRLF that separates this part from the
        # next delimiter; without this the file bytes pick up a stray
        # \r\n which corrupts binary content.
        if chunk.endswith(b"\r\n"):
            chunk = chunk[:-2]
        # Header / body split — first blank line.
        hdr_end = chunk.find(b"\r\n\r\n")
        if hdr_end < 0:
            continue
        headers_blob = chunk[:hdr_end].decode("utf-8", errors="replace")
        data = chunk[hdr_end + 4:]
        # Parse Content-Disposition + Content-Type from the headers.
        name: str | None = None
        filename: str | None = None
        ctype = "application/octet-stream"
        for raw_line in headers_blob.split("\r\n"):
            line = raw_line.strip()
            low = line.lower()
            if low.startswith("content-disposition:"):
                for piece in line.split(";"):
                    piece = piece.strip()
                    pl = piece.lower()
                    if pl.startswith("name="):
                        name = piece.split("=", 1)[1].strip().strip('"')
                    elif pl.startswith("filename="):
                        filename = piece.split("=", 1)[1].strip().strip('"')
            elif low.startswith("content-type:"):
                ctype = line.split(":", 1)[1].strip()
        if not name:
            continue
        out.append({
            "name":         name,
            "filename":     filename,
            "content_type": ctype,
            "data":         data,
        })
    return out


def _atomic_write_json(path: Path, data: object) -> None:
    """Atomic JSON write. Caller does NOT need to hold _STATE_LOCK —
    callers in this module already do, but this helper doesn't assume."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        tmp.replace(path)
    except Exception as e:
        # We don't want a disk hiccup to crash an API request — log and
        # continue. Worst case the next mutation re-tries the write.
        print(f"[state] atomic write to {path} failed: {e}", file=sys.stderr)


def _save_per_screen() -> None:
    """Persist _per_screen. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(PER_SCREEN_JSON, _per_screen)


def _save_screens() -> None:
    """Persist _screens. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(SCREENS_JSON, _screens)


def _load_state_from_disk() -> None:
    """One-shot loader, called once on module import. Best-effort —
    a missing or corrupt file just means we start with empty state,
    same as fresh boot before persistence existed."""
    global _per_screen, _screens, _sync_groups
    with _STATE_LOCK:
        for path, target_name in [
            (PER_SCREEN_JSON, "_per_screen"),
            (SCREENS_JSON, "_screens"),
            (SYNC_GROUPS_JSON, "_sync_groups"),
        ]:
            if not path.is_file():
                continue
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(raw, dict):
                    print(f"[state] {path}: top-level isn't a dict, skipping", file=sys.stderr)
                    continue
                if target_name == "_per_screen":
                    _per_screen = raw
                elif target_name == "_screens":
                    _screens = raw
                else:
                    _sync_groups = raw
                print(f"[state] loaded {len(raw)} entries from {path}", file=sys.stderr)
            except Exception as e:
                print(f"[state] load {path} failed: {e}", file=sys.stderr)


# ── Activity log ─────────────────────────────────────────────────────
# In-memory ring buffer of API events for the CMS Activity page and the
# Dashboard's recent-activity panel. Keeps the last N entries; oldest fall
# off the back when the deque hits its cap. State is non-persistent — a
# server restart clears the log. Acceptable for a demo; replace with a
# proper store when persistence lands.
import collections  # local-scope to keep the imports section above untouched

_ACTIVITY_LOG: collections.deque = collections.deque(maxlen=200)


def _log_activity(
    kind: str,
    text: str,
    *,
    who: str | None = None,
    icon: str | None = None,
    tone: str | None = None,
    target: str | None = None,
) -> None:
    """Append a single event to the in-memory activity log.

    `kind` is the canonical event type the frontend keys icon/tone off
    (push, register, command, sync, splash, settings, etc.). `text` is
    the human-readable line shown in the UI. `who` defaults to "system"
    for events not associated with a CMS user — registration, heartbeat
    timeouts, scheduled syncs.
    """
    entry = {
        "id":     f"act-{int(time.time() * 1000)}-{len(_ACTIVITY_LOG)}",
        "kind":   kind,
        "text":   text,
        "who":    who or "system",
        "icon":   icon or kind,
        "tone":   tone,
        "target": target,
        "at":     time.time(),
    }
    _ACTIVITY_LOG.append(entry)


POLL_MODES = ("fast", "normal", "slow")
DEFAULT_POLL_MODE = "normal"


def _ensure_screen_state(device_id: str) -> dict:
    """Lazily create a per-screen state record. Caller must hold _STATE_LOCK."""
    s = _per_screen.get(device_id)
    if s is None:
        s = {
            "revision": 0,
            "items": [],
            "pushedAt": None,
            "mixSplash": True,                    # bundled splash mixed in by default
            "audioOn": False,                     # screen-wide audio is muted by default — see /api/screens/<id>/audio
            "pollMode": DEFAULT_POLL_MODE,        # "fast" | "normal" | "slow" — see /api/screens/<id>/poll-mode
            "syncGroup": None,                    # see _compute_playback / /api/screens/<id>/sync-group
            # v0.1.14: per-screen display mode override. When set, the
            # tablet calls Window.LayoutParams.preferredDisplayModeId =
            # <int> so the system picks the matching HDMI mode at the
            # next surface attach. Value is the Display.Mode.modeId
            # reported by the device's own heartbeat (see
            # supportedModes there). None = auto, let the box keep its
            # current mode. Boxes like the TX3 Mini boot in 720p but
            # support 1080p — this override is how the CMS flips them.
            "displayMode": None,
            # v0.1.15: wall-clock ms at which the calibration overlay
            # (giant synchronised clock) should stop showing. Null or
            # past = no overlay. Set by POST /api/sync-groups/<id>/
            # calibrate to (now + duration). The tablet renders the
            # overlay until `correctedNow()` passes this value.
            "calibrateUntilMs": None,
            "pendingCommands": [],                 # list of pending commands for this screen
        }
        _per_screen[device_id] = s
        _save_per_screen()
    # Back-fill any fields persisted before they shipped so older records
    # get sane defaults without a migration script.
    #
    # Migration v0.1.8: the old `lowDataMode: bool` collapses into one
    # of two pollMode values — slow when it was on, normal otherwise.
    # Newer code reads pollMode; the old field stays in the dict so
    # rollbacks don't lose information.
    if "pollMode" not in s:
        legacy_low_data = bool(s.get("lowDataMode"))
        s["pollMode"] = "slow" if legacy_low_data else DEFAULT_POLL_MODE
    if "lowDataMode" not in s:
        s["lowDataMode"] = (s.get("pollMode") == "slow")
    if "syncGroup" not in s:
        s["syncGroup"] = None
    if "displayMode" not in s:
        s["displayMode"] = None
    if "calibrateUntilMs" not in s:
        s["calibrateUntilMs"] = None
    return s


# ── Sync groups ──────────────────────────────────────────────────────
# When two or more screens share a `syncGroup` value, the server hands
# them an identical "playback" block on every /api/state poll — same
# item, same position-within-item, computed from a fixed group epoch
# and the playlist's per-item durations. The tablet seeks ExoPlayer to
# that position on every poll if it drifts past a threshold.
#
# `_sync_groups[groupId]` = {
#   "loopStartedAt": float (epoch seconds),
#   "lastRevision":  int,
# }
#
# We reset `loopStartedAt` to `now` whenever the group's playlist
# revision moves forward (so a new push restarts the loop in lockstep
# across every screen in the group). Persisted alongside _per_screen so
# the alignment survives Cloud Run redeploys.

_sync_groups: dict[str, dict] = {}

# v0.1.15: coordinated-start lookahead. When the loop epoch resets
# (new revision, fresh group), we anchor `loopStartedAt` to a moment
# in the near future rather than the current instant. That gives
# every screen in the group time to:
#   • Receive the next /api/state poll (cadence depends on pollMode —
#     fast=10 s, normal=60 s).
#   • See the bumped revision and the future epoch.
#   • Prepare ExoPlayer (queue media items, prepare(), seek to 0).
# When wall-clock reaches `loopStartedAt`, every prepared tablet is
# already sitting on frame 0 of item 0 and starts together.
#
# Previously the epoch was `now`. The first tablet to poll saw the
# new epoch and started immediately; the second tablet, polling a few
# seconds later, saw the same epoch but with N seconds already
# elapsed, so its first snap-on-transition was already mid-item. The
# new behaviour eliminates that staircase.
#
# 5 s is a balance: long enough for one slow-poll-mode tick (Fast is
# 10 s — but the staff "Calibrate / push" flow forces a refresh
# command so tablets re-poll immediately), short enough that a push
# still feels responsive in the CMS.
COORDINATED_START_DELAY_SEC = 5.0


def _save_sync_groups() -> None:
    """Persist _sync_groups. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(SYNC_GROUPS_JSON, _sync_groups)


def _group_loop_epoch(items: list, group_id: str, current_revision: int, now: float) -> dict | None:
    """Return the group's loop anchor as `{groupId, loopStartedAtMs}` —
    nothing more. The tablet computes "which item am I on?" locally
    using this epoch + the item durations it already has.

    Lazily initialises the group record on first sight (loop anchored
    to `now` so the group starts immediately, no pause).

    **Does NOT reset the epoch based on `current_revision` changes.**
    Different members of the same group can carry different revision
    counters (the fan-out endpoint INCREMENTS each member rather than
    syncing them to a shared value, so two screens that joined the
    group at different times stay out of step forever). The old
    revision-based reset interpreted that as "the playlist just
    changed" on every poll and re-anchored the epoch to the future,
    so each tablet hit the coordinated-start pause on its own poll
    cadence — visible as both screens pausing at slightly different
    moments. Resets now happen explicitly via [_reset_group_loop_epoch]
    from the playlist push endpoint, which is the actual "the content
    for this group changed" signal.

    Caller must hold _STATE_LOCK."""
    if not items:
        return None
    group = _sync_groups.get(group_id)
    if group is None:
        # First time we've seen this group at all — start the loop
        # running from `now`. No coordinated-start delay: this branch
        # fires on lazy initialisation (e.g. first poll after a Cloud
        # Run cold start re-read state from disk and the group record
        # didn't make it through), not on a real content change.
        # Anchoring in the future here would cause an unnecessary
        # pause for tablets that have already been playing the loop.
        group = {
            "loopStartedAt": now,
            "lastRevision": current_revision,
        }
        _sync_groups[group_id] = group
        _save_sync_groups()
    return {
        "groupId":          group_id,
        "loopStartedAtMs":  int(float(group["loopStartedAt"]) * 1000),
    }


def _reset_group_loop_epoch(group_id: str, now: float) -> None:
    """Anchor the group's loop epoch at `now + COORDINATED_START_DELAY_SEC`
    so every member's tablet does the coordinated-start pause-and-resume
    dance. Called from the playlist push endpoint when the content
    for the group changes — the only legitimate trigger for a reset.

    Caller must hold _STATE_LOCK."""
    _sync_groups[group_id] = {
        "loopStartedAt": now + COORDINATED_START_DELAY_SEC,
        # lastRevision kept for back-compat with on-disk records but
        # no longer used by the lookup path. Stored as 0 to make it
        # obvious in the JSON that revision tracking is dead code.
        "lastRevision": 0,
    }
    _save_sync_groups()


def _compute_playback(items: list, group_id: str, current_revision: int, now: float) -> dict | None:
    """Legacy: returns the same epoch info plus a server-computed
    `(item, position)` block for older tablets that haven't picked up
    the v0.1.12 client-side sync logic. Newer tablets read
    `loopStartedAtMs` directly and ignore everything else here."""
    epoch = _group_loop_epoch(items, group_id, current_revision, now)
    if epoch is None:
        return None
    # Coerce durations to a sensible default — 15 s for any item that's
    # missing durationSec keeps the loop math from blowing up.
    durations = [max(1.0, float(item.get("durationSec") or 15)) for item in items]
    total = sum(durations)
    if total <= 0:
        return None
    loop_started_at = epoch["loopStartedAtMs"] / 1000.0
    elapsed = max(0.0, now - loop_started_at)
    offset = elapsed % total
    cumulative = 0.0
    for i, dur in enumerate(durations):
        if offset < cumulative + dur:
            position_in_item = offset - cumulative
            return {
                **epoch,
                "itemId":           items[i].get("id"),
                "itemIndex":        i,
                "positionMs":       int(position_in_item * 1000),
                "itemStartedAtMs":  int((now - position_in_item) * 1000),
                "loopDurationSec":  total,
            }
        cumulative += dur
    return {
        **epoch,
        "itemId":           items[-1].get("id"),
        "itemIndex":        len(items) - 1,
        "positionMs":       int(durations[-1] * 1000) - 1,
        "itemStartedAtMs":  int((now - durations[-1]) * 1000),
        "loopDurationSec":  total,
    }


# Library cache. scan-videos.py writes app/components/library.json; we read
# it on demand and serve via /api/library so the tablet's staff overlay can
# list the same brands and videos as the CMS.
# Also indexed by Drive file ID so /media/<id> can answer size/mimetype
# requests without a redundant Drive API call (Drive throttles those
# aggressively when tablets + CMS all stream concurrently after a scan).
_LIBRARY_CACHE: dict = {"mtime": 0.0, "data": None, "by_drive_id": None}


def _load_library() -> dict:
    """Cached library read. Re-loads only when the file's mtime bumps,
    and rebuilds the drive-id → entry index alongside the videos list."""
    if not LIBRARY_JSON.is_file():
        return {"brands": [], "videos": []}
    mtime = LIBRARY_JSON.stat().st_mtime
    if _LIBRARY_CACHE["mtime"] != mtime or _LIBRARY_CACHE["data"] is None:
        with open(LIBRARY_JSON, "r", encoding="utf-8") as f:
            data = json.load(f)
        # Build the drive-id index from `mediaUrl` (cloud-mode shape is
        # "/media/<drive_file_id>"). Skip filesystem-mode entries where
        # mediaUrl points at "/media/<brand>/<file.mp4>".
        index: dict[str, dict] = {}
        for v in data.get("videos") or []:
            url = v.get("mediaUrl") or ""
            if not url.startswith("/media/"):
                continue
            tail = url[len("/media/"):].strip("/")
            if "/" in tail:
                continue   # filesystem-shape URL, not a Drive ID
            if not tail:
                continue
            index[tail] = v
        _LIBRARY_CACHE["data"] = data
        _LIBRARY_CACHE["by_drive_id"] = index
        _LIBRARY_CACHE["mtime"] = mtime
    return _LIBRARY_CACHE["data"]


def _library_lookup_by_drive_id(drive_file_id: str) -> dict | None:
    """Return the cached library entry for a Drive file ID, or None.
    Side-effect: ensures the library cache is warm."""
    _load_library()
    idx = _LIBRARY_CACHE.get("by_drive_id") or {}
    return idx.get(drive_file_id)


def _library_lookup_by_id(video_id: str) -> dict | None:
    """Return the cached library entry for the synthetic video ID
    (e.g. "sonos-1"). Used to merge per-video flags (defaultUnmute,
    etc.) into per-screen pushed playlist items at /api/state time."""
    _load_library()
    data = _LIBRARY_CACHE.get("data") or {}
    for v in (data.get("videos") or []):
        if v.get("id") == video_id:
            return v
    return None


def _update_video_in_library(video_id: str, patch: dict) -> dict | None:
    """Mutate library.json in-place: find the video with matching id,
    apply patch (skipping None values), atomic-rewrite the file. Bumps
    the in-memory cache mtime sentinel so the next /api/library read
    re-loads from disk. Returns the updated entry, or None if id missing."""
    _load_library()
    data = _LIBRARY_CACHE.get("data") or {}
    target = None
    for v in (data.get("videos") or []):
        if v.get("id") == video_id:
            for key, value in patch.items():
                if value is None:
                    continue
                v[key] = value
            target = v
            break
    if target is None:
        return None
    LIBRARY_JSON.parent.mkdir(parents=True, exist_ok=True)
    tmp = LIBRARY_JSON.with_suffix(LIBRARY_JSON.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(LIBRARY_JSON)
    # Force cache reload on next access.
    _LIBRARY_CACHE["mtime"] = 0.0
    return dict(target)


# ── Splash registry ──────────────────────────────────────────────────
# Folders on Drive named `Splash - <Brand>` or `Splash - <Concept>`. The
# server scans for the first MP4 in each on startup and exposes them via
# `/api/splashes`. Resolution: concept overrides brand; brand picked by
# city (NYC/ROM → tm:rw, LDN/BER → Smartech).
SPLASH_FOLDERS = [
    # (kind, name, folder_name)
    ("brand",   "tmrw",       "Splash - tmrw"),
    ("brand",   "smartech",   "Splash - Smartech"),
    ("concept", "Smartech",   "Splash - Smartech"),
    ("concept", "Bikeshop",   "Splash - Bike Shop"),
    ("concept", "7EVN",       "Splash - 7EVN"),
    ("concept", "Playhouse",  "Splash - Playhouse"),
    ("concept", "Sanctuary",  "Splash - Sanctuary"),
    ("concept", "The Track",  "Splash - The Track"),
    ("concept", "Cornershop", "Splash - Cornershop"),
    ("concept", "tm:rw Cafe", "Splash - tm-rw Cafe"),
]

# Default city → brand mapping. NYC and ROM run tm:rw; LDN and BER run
# Smartech. Editable at runtime via /api/splashes/mapping.
DEFAULT_CITY_BRAND: dict = {
    "NYC": "tmrw",
    "ROM": "tmrw",
    "LDN": "smartech",
    "BER": "smartech",
}

_splash_registry: dict = {}     # key "brand:tmrw" / "concept:7EVN" -> meta
_city_brand: dict = {}          # mutable copy of DEFAULT_CITY_BRAND

# ── Library sync (scan-videos.py runner) ────────────────────────────
# The CMS doesn't keep its own copy of any video — `/media/...` streams
# them in place from the local Drive Desktop sync at
# `G:\Shared drives\Smartech\Screens\Brand Content`. The "Drive sync"
# UI re-runs scan-videos.py to refresh `library.json` (titles, brands,
# durations, dimensions). Auto-runs once a day in a background thread.

import subprocess

_SYNC_LOCK = threading.RLock()
_sync_state: dict = {
    "lastRunAt":       None,    # epoch seconds of the last scan completion
    "lastSuccess":     None,    # bool
    "lastCount":       None,    # int videos
    "lastError":       None,    # error string when lastSuccess=False
    "running":         False,
    "progressCurrent": None,    # int — folders processed so far this run
    "progressTotal":   None,    # int — total folders in this run
    "progressLabel":   None,    # str — current brand folder name
}

_PROGRESS_RE = re.compile(r"^PROGRESS:\s+(\d+)/(\d+)\s+(.*)$")


def run_library_scan() -> dict:
    """Synchronously re-run scan-videos.py. Streams progress lines into
    _sync_state so the Drive Sync UI can render a live count."""
    with _SYNC_LOCK:
        if _sync_state["running"]:
            print("[sync] run_library_scan: already running — skip", file=sys.stderr, flush=True)
            return {"ok": False, "error": "already running"}
        _sync_state["running"] = True
        _sync_state["progressCurrent"] = None
        _sync_state["progressTotal"] = None
        _sync_state["progressLabel"] = None
    tail: list[str] = []        # last few lines for error reporting
    try:
        scan_script = PROJECT / "scan-videos.py"
        print(f"[sync] launching {scan_script}", file=sys.stderr, flush=True)
        proc = subprocess.Popen(
            [sys.executable, "-u", str(scan_script)],   # -u = unbuffered stdout
            cwd=str(PROJECT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,                                  # line-buffered
            encoding="utf-8",
            errors="replace",
        )
        print(f"[sync] scan-videos.py subprocess started (pid={proc.pid})", file=sys.stderr, flush=True)
        assert proc.stdout is not None

        # Watchdog: forcibly kill the subprocess if it goes too long
        # without emitting *any* line. Drive API hangs do happen
        # (network blip, paged listing on a huge folder), and without
        # this the for-loop below would block forever and the
        # _sync_state["running"] flag would stay True indefinitely.
        def _watchdog():
            deadline = time.time() + 900   # 15 min total budget
            while proc.poll() is None and time.time() < deadline:
                time.sleep(15)
            if proc.poll() is None:
                print(f"[sync] watchdog: subprocess pid={proc.pid} exceeded 15m budget — killing", file=sys.stderr, flush=True)
                try: proc.kill()
                except Exception: pass
        threading.Thread(target=_watchdog, daemon=True).start()

        last_progress_at = time.time()
        for raw in proc.stdout:
            line = raw.rstrip()
            if not line:
                continue
            m = _PROGRESS_RE.match(line)
            if m:
                with _SYNC_LOCK:
                    _sync_state["progressCurrent"] = int(m.group(1))
                    _sync_state["progressTotal"] = int(m.group(2))
                    _sync_state["progressLabel"] = m.group(3)
                # Mirror PROGRESS lines too — but only every 20 brands
                # so the log stream doesn't get spammed. Lets us see
                # forward motion in Cloud Run during a long scan.
                cur = int(m.group(1))
                if cur == 1 or cur % 20 == 0 or cur == int(m.group(2)):
                    print(f"[sync] progress: {cur}/{m.group(2)} ({m.group(3)})", file=sys.stderr, flush=True)
                last_progress_at = time.time()
                continue
            # Keep a rolling tail of regular log lines so we can surface
            # the script's stderr if something goes wrong AND echo each
            # line to our own stderr so it shows up in Cloud Run logs
            # while the scan is running.
            tail.append(line)
            if len(tail) > 30:
                tail.pop(0)
            print(f"[sync] {line}", file=sys.stderr, flush=True)
        proc.wait(timeout=60)   # subprocess.stdout already exhausted; this is just the reap
        ok = proc.returncode == 0
        print(f"[sync] subprocess exited rc={proc.returncode}", file=sys.stderr, flush=True)

        count = None
        if ok:
            try:
                with open(LIBRARY_JSON, "r", encoding="utf-8") as f:
                    count = len(json.load(f).get("videos", []))
            except Exception:
                count = None
            _LIBRARY_CACHE["mtime"] = 0.0    # force reload on next /api/library

        err = None if ok else "\n".join(tail[-15:]) or f"scan failed (exit {proc.returncode})"

        with _SYNC_LOCK:
            _sync_state["lastRunAt"] = time.time()
            _sync_state["lastSuccess"] = ok
            _sync_state["lastCount"] = count
            _sync_state["lastError"] = err
            _sync_state["running"] = False
            _sync_state["progressCurrent"] = None
            _sync_state["progressTotal"] = None
            _sync_state["progressLabel"] = None
        if ok:
            print(f"[sync] scan-videos.py OK - {count} videos", file=sys.stderr)
            _log_activity(
                kind="sync",
                text=f"Drive sync complete · {count} video{'' if count == 1 else 's'}",
                icon="sync",
                tone="ok",
            )
        else:
            print(f"[sync] scan-videos.py FAILED - exit {proc.returncode}", file=sys.stderr)
            _log_activity(
                kind="sync",
                text=f"Drive sync failed (exit {proc.returncode})",
                icon="warning",
                tone="err",
            )
        return {"ok": ok, "count": count, "error": err}
    except Exception as e:
        with _SYNC_LOCK:
            _sync_state["running"] = False
            _sync_state["lastSuccess"] = False
            _sync_state["lastError"] = str(e)
            _sync_state["lastRunAt"] = time.time()
            _sync_state["progressCurrent"] = None
            _sync_state["progressTotal"] = None
            _sync_state["progressLabel"] = None
        _log_activity(
            kind="sync",
            text=f"Drive sync errored: {e}",
            icon="warning",
            tone="err",
        )
        return {"ok": False, "error": str(e)}


def _trigger_initial_sync_if_needed() -> None:
    """Fire one library scan in the background if the cached file is
    missing or empty. The CMS shows "no content" until library.json
    has videos in it — without this, a fresh container shows nothing
    for up to 24h while the daily loop sleeps.

    Cheap to call: a few stat() calls + maybe a JSON parse.
    """
    needs_sync = False
    try:
        if not LIBRARY_JSON.is_file():
            needs_sync = True
        else:
            data = json.loads(LIBRARY_JSON.read_text(encoding="utf-8"))
            if not data.get("videos"):
                needs_sync = True
    except Exception as e:
        print(f"[sync] initial-check failed, will sync: {e}", file=sys.stderr)
        needs_sync = True
    if not needs_sync:
        print(f"[sync] library already populated ({LIBRARY_JSON}) — skipping initial sync", file=sys.stderr)
        return
    print(f"[sync] library empty at boot — kicking off initial sync", file=sys.stderr)
    threading.Thread(target=run_library_scan, daemon=True).start()


def daily_sync_loop() -> None:
    """Re-run the library scan every 24 hours. Designed to run in a daemon
    thread so it dies with the server."""
    while True:
        time.sleep(24 * 60 * 60)
        try:
            run_library_scan()
        except Exception as e:
            print(f"[sync] daily failure: {e}", file=sys.stderr)


def _build_splash_registry() -> None:
    """Scan SPLASH_DIR for the configured folders. Pick the largest MP4
    that isn't tucked under an "Old" / "Compressed" / etc subfolder."""
    skip_segments = ("/old/", "/compressed/", "/nologo/", "/portrait/")
    out: dict = {}
    for kind, name, folder_name in SPLASH_FOLDERS:
        folder = SPLASH_DIR / folder_name
        if not folder.is_dir():
            continue
        candidates = []
        for ext in ("*.mp4", "*.mov"):
            for f in folder.rglob(ext):
                rel = str(f).lower().replace("\\", "/")
                if any(seg in rel for seg in skip_segments):
                    continue
                candidates.append(f)
        if not candidates:
            continue
        # Prefer files at the top level of the folder; fall back to deepest
        # filesystem find. Then largest by size.
        top = [c for c in candidates if c.parent == folder]
        chosen = (sorted(top, key=lambda p: p.stat().st_size, reverse=True)
                  or sorted(candidates, key=lambda p: p.stat().st_size, reverse=True))[0]
        rel_path = chosen.relative_to(SPLASH_DIR)
        url = "/splash/" + "/".join(urllib.parse.quote(p) for p in rel_path.parts)
        meta = {
            "kind":     kind,
            "name":     name,
            "filename": chosen.name,
            "url":      url,
            "sizeMb":   round(chosen.stat().st_size / (1024 * 1024), 1),
        }
        out[f"{kind}:{name}"] = meta
    _splash_registry.clear()
    _splash_registry.update(out)
    _city_brand.clear()
    _city_brand.update(DEFAULT_CITY_BRAND)


# Cached Drive folder name lookup. The /api/library/info endpoint shows
# the brand-content folder's display name in the Drive Sync settings
# card, but Drive's files().get() call costs a quota unit per request —
# the UI polls library/info every 2-30s, so we cache the resolved name
# in module state and re-fetch only on miss / failure.
_drive_folder_name_cache: dict[str, str | None] = {}


def _drive_folder_name_cached(folder_id: str) -> str | None:
    """Resolve a Drive folder ID to its human-readable name, with cache.

    Returns the cached name if known, otherwise hits the Drive API and
    stores the result. Failures cache `None` so we don't keep retrying
    a broken lookup on every poll — set _drive_folder_name_cache to
    {} explicitly to force a refresh.
    """
    if folder_id in _drive_folder_name_cache:
        return _drive_folder_name_cache[folder_id]
    if drive_client is None:
        _drive_folder_name_cache[folder_id] = None
        return None
    try:
        meta = drive_client.get_metadata(folder_id)
        name = meta.get("name")
        _drive_folder_name_cache[folder_id] = name
        return name
    except Exception as e:
        print(f"[drive] folder-name lookup failed for {folder_id}: {e}", file=sys.stderr)
        _drive_folder_name_cache[folder_id] = None
        return None


def _hydrate_splashes_from_drive() -> Path | None:
    """If we're in Drive cloud mode, download splash files into a local
    cache once at boot. Returns the cache directory, which the caller
    points SPLASH_DIR at so the existing filesystem-based registry-build
    logic walks the cache as if it were the local Drive mount.

    Why bake them in via cache rather than streaming on demand:
      • Splashes are small (1–80 MB total across all brands/concepts).
      • They're hit on every screen, every loop — streaming through
        Drive on every request would be wasteful and rate-limited.
      • Once cached they "live in the system" — `/splash/<file>` is
        served from local disk with full Range support, identical to
        baked-into-image performance.

    Cache layout mirrors the upstream Drive structure:
        <cache_dir>/Splash - tmrw/tmrw_logoanimation.mp4
        <cache_dir>/Splash - Smartech/Smartech_vid4web.mp4
        ...

    Errors here don't crash the server — splashes will just be missing
    until the next restart.
    """
    if drive_client is None or not drive_client.is_configured():
        return None
    splash_root_id = os.environ.get("SCREENS_DRIVE_SPLASHES_ID")
    if not splash_root_id:
        return None

    # /tmp on Cloud Run is a tmpfs (in-memory). For a few dozen MB of
    # splash MP4s that's fine and survives the lifetime of the instance.
    cache_dir = Path("/tmp/screens-splash-cache")
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        print(f"[splash] couldn't create cache dir: {e}", file=sys.stderr)
        return None

    # Names of the splash subfolders we care about, from SPLASH_FOLDERS.
    wanted = {f[2] for f in SPLASH_FOLDERS}

    try:
        subfolders = drive_client.list_subfolders(splash_root_id)
    except Exception as e:
        print(f"[splash] Drive listSubfolders failed: {e}", file=sys.stderr)
        return None

    cached_count = 0
    for folder in subfolders:
        if folder["name"] not in wanted:
            continue
        local_subdir = cache_dir / folder["name"]
        local_subdir.mkdir(exist_ok=True)
        try:
            files = drive_client.list_files_in(folder["id"])
        except Exception as e:
            print(f"[splash] list {folder['name']} failed: {e}", file=sys.stderr)
            continue
        for f in files:
            if f.get("mimeType") == "application/vnd.google-apps.folder":
                continue
            name = f.get("name", "")
            if not (name.lower().endswith(".mp4") or name.lower().endswith(".mov")):
                continue
            local_path = local_subdir / name
            # Skip if already cached this run (idempotent restart).
            expected_size = int(f.get("size", "0") or 0)
            if local_path.is_file() and (
                expected_size == 0 or local_path.stat().st_size == expected_size
            ):
                cached_count += 1
                continue
            # Download. Buffered through drive_client.stream_file —
            # acceptable for splash-sized files; brand content goes
            # through a different streaming path.
            try:
                with open(local_path, "wb") as out:
                    for status, _, _, chunk in drive_client.stream_file(f["id"]):
                        if status >= 400:
                            raise RuntimeError(f"HTTP {status}")
                        out.write(chunk)
                cached_count += 1
                print(f"[splash] cached {folder['name']}/{name}", flush=True)
            except Exception as e:
                print(f"[splash] download {name} failed: {e}", file=sys.stderr)
                # Leave a partial file alone — next boot will retry.

    if cached_count == 0:
        print("[splash] no splashes cached from Drive", file=sys.stderr)
        return None
    return cache_dir


def resolve_splash_for(city: str | None, concept: str | None) -> dict | None:
    """Return splash meta for a screen at (city, concept). Concept overrides brand."""
    if concept:
        m = _splash_registry.get(f"concept:{concept}")
        if m:
            return m
    if city:
        brand = _city_brand.get(city)
        if brand:
            m = _splash_registry.get(f"brand:{brand}")
            if m:
                return m
    return None


def lan_ip() -> str:
    """Best-guess LAN IP — the address that would reach 8.8.8.8."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(http.server.SimpleHTTPRequestHandler):
    """Routes / under app/ and /media/ under the Drive's Brand Content folder."""

    # `directory=` on the constructor is honoured by Python's stdlib but we want
    # a per-request decision, so we override translate_path entirely.
    def translate_path(self, path: str) -> str:
        # Strip query / fragment, then percent-decode.
        path = path.split("?", 1)[0].split("#", 1)[0]
        path = urllib.parse.unquote(path)
        path = path.lstrip("/")

        if path.startswith("media/"):
            sub = path[len("media/") :]
            full = (MEDIA_DIR / sub).resolve()
            try:
                full.relative_to(MEDIA_DIR)
            except ValueError:
                return str(MEDIA_DIR)
            return str(full)

        if path.startswith("splash/"):
            sub = path[len("splash/"):]
            full = (SPLASH_DIR / sub).resolve()
            try:
                full.relative_to(SPLASH_DIR)
            except ValueError:
                return str(SPLASH_DIR)
            return str(full)

        if path.startswith("brand/"):
            sub = path[len("brand/"):]
            full = (BRAND_DIR / sub).resolve()
            try:
                full.relative_to(BRAND_DIR)
            except ValueError:
                return str(BRAND_DIR)
            return str(full)

        # v0.1.20: serve CMS-uploaded videos. Same range-streaming
        # path as /media; just a different root because UPLOADS_DIR
        # lives on the writable FUSE mount whereas MEDIA_DIR points
        # at the read-only Drive folder. Library entries created by
        # the upload endpoint set `mediaUrl: "/uploaded/<file>"`, so
        # the player follows that path to here.
        if path.startswith("uploaded/"):
            sub = path[len("uploaded/"):]
            full = (UPLOADS_DIR / sub).resolve()
            try:
                full.relative_to(UPLOADS_DIR)
            except ValueError:
                return str(UPLOADS_DIR)
            return str(full)

        if not path:
            return str(APP_DIR / "index.html")
        return str((APP_DIR / path).resolve())

    # ── /media/ gets range support; /api/ goes to JSON handlers ──

    def do_GET(self) -> None:
        if self.path.startswith("/api/"):
            self._serve_api_get()
            return
        if (self.path.startswith("/media/")
                or self.path.startswith("/splash/")
                or self.path.startswith("/uploaded/")):
            self._serve_media(head_only=False)
            return
        # /apk      — modern build (the everyday one)
        # /apk/legacy — legacy build for Android 6/7 boxes
        # Convenience routes so the user can give someone the URL
        # "https://screens.smartechworld.com/apk" and the download
        # starts immediately. No CMS page renders first; we go
        # straight to the proxy. Works on networks that block
        # GitHub's CDN host directly.
        raw_path = self.path.split("?", 1)[0].rstrip("/")
        if raw_path == "/apk":
            self._serve_release_download("modern"); return
        if raw_path == "/apk/legacy":
            self._serve_release_download("legacy"); return
        if raw_path == "/apk/modern":
            self._serve_release_download("modern"); return
        super().do_GET()

    def do_HEAD(self) -> None:
        if (self.path.startswith("/media/")
                or self.path.startswith("/splash/")
                or self.path.startswith("/uploaded/")):
            self._serve_media(head_only=True)
            return
        super().do_HEAD()

    def do_POST(self) -> None:
        # v0.1.20: multipart upload bypasses _serve_api_post because that
        # path eagerly reads the whole body as JSON. Routed here as the
        # only multipart endpoint so we don't have to teach the JSON
        # reader to peek at Content-Type.
        if self.path.split("?", 1)[0] == "/api/library/upload":
            self._serve_api_library_upload()
            return
        if self.path.startswith("/api/"):
            self._serve_api_post()
            return
        self.send_error(405)

    def do_PATCH(self) -> None:
        if self.path.startswith("/api/"):
            self._serve_api_patch()
            return
        self.send_error(405)

    def do_DELETE(self) -> None:
        if self.path.startswith("/api/"):
            self._serve_api_delete()
            return
        self.send_error(405)

    def do_OPTIONS(self) -> None:
        # Permissive CORS — browser preflights for /api/push from the CMS,
        # and the tablet cares less but it doesn't hurt.
        self.send_response(204)
        self._cors_headers()
        self.end_headers()

    # ── API handlers ──────────────────────────────────────────────

    def _cors_headers(self) -> None:
        # Allow-Credentials must be set for cookie-bearing fetches from the
        # CMS to work, and that requires a specific origin (not "*").
        # Same-origin requests (CMS served from this server) hit the
        # fallback "*" path and don't need credentials anyway.
        origin = self.headers.get("Origin")
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Credentials", "true")
        else:
            self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _send_json(
        self,
        payload: dict,
        status: int = 200,
        extra_headers: list[tuple[str, str]] | None = None,
    ) -> None:
        body = json.dumps(payload).encode("utf-8")
        # v0.1.20: gzip JSON responses over ~4 KB when the client
        # accepts it. The /api/library response is the biggest by far
        # (~450 kB raw on a typical fleet) and was the slow path the
        # CMS noticed after a Drive sync or upload — every poll
        # downloaded the full payload over the wire. gzip cuts that
        # to ~80 kB. Smaller responses skip compression because the
        # CPU + header overhead isn't worth it.
        accept_enc = (self.headers.get("Accept-Encoding") or "").lower()
        gzipped = False
        if len(body) > 4096 and "gzip" in accept_enc:
            import gzip as _gzip
            body = _gzip.compress(body, compresslevel=5)
            gzipped = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if gzipped:
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Vary", "Accept-Encoding")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, value in (extra_headers or []):
            self.send_header(name, value)
        self._cors_headers()
        self.end_headers()
        self.wfile.write(body)

    # ── Auth helpers ────────────────────────────────────────────────
    # Cached on the handler instance so multiple route checks per request
    # don't re-hit the DB. Set on first call to _current_user().

    def _current_user(self) -> dict | None:
        cached = getattr(self, "_user_cache", "MISS")
        if cached != "MISS":
            return cached
        token = auth.session_token_from_cookie_header(self.headers.get("Cookie"))
        user = auth.current_user_for_token(token)
        self._user_cache = user
        return user

    def _require_perm(self, perm: str) -> dict | None:
        """Return the current user if they have `perm`. Otherwise send the
        appropriate error and return None — the caller must early-return."""
        user = self._current_user()
        if user is None:
            self._send_json({"error": "unauthenticated"}, status=401)
            return None
        if not auth.has_permission(user, perm):
            self._send_json({"error": "forbidden", "need": perm}, status=403)
            return None
        return user

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    # ── v0.1.20: in-CMS video upload ─────────────────────────────────
    # Cap on the multipart body size. 1 GiB is enough for any single
    # video any tablet can usefully play (15 s loops aren't 1 GiB).
    # Above this we 413 instead of trying to read the body into RAM —
    # Cloud Run's per-instance memory ceiling is 4 GiB and we share
    # it with library cache + state JSON + Python overhead.
    _UPLOAD_MAX_BYTES = 1024 * 1024 * 1024

    def _serve_api_library_upload(self) -> None:
        """POST /api/library/upload — multipart/form-data with fields:
          file        the video bytes (required)
          brand       brand id, e.g. "sonos" (required)
          title       human-readable name (optional, defaults to filename)
          product     sub-category within the brand (optional)
          durationSec int — caller can pass it if known (optional;
                      defaults to 15 s as a sensible signage loop)

        Writes the file to UPLOADS_DIR/<safe>.<ext>, appends an entry
        to library.json, returns the new entry. No transcoding — the
        uploaded file is what plays back.
        """
        if self._require_perm("library.sync") is None:
            return

        ctype = self.headers.get("Content-Type", "")
        if not ctype.lower().startswith("multipart/form-data"):
            self.send_error(415, "Expected multipart/form-data"); return
        # Extract boundary. RFC 2046 allows boundary= with or without quotes.
        boundary = None
        for piece in ctype.split(";"):
            piece = piece.strip()
            if piece.lower().startswith("boundary="):
                boundary = piece[len("boundary="):].strip().strip('"')
                break
        if not boundary:
            self.send_error(400, "Missing multipart boundary"); return

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.send_error(411, "Content-Length required"); return
        if length <= 0:
            self.send_error(411, "Content-Length required"); return
        if length > self._UPLOAD_MAX_BYTES:
            self.send_error(413, f"Body too large (cap {self._UPLOAD_MAX_BYTES} bytes)"); return

        # Read the full body. Multipart parsing on a stream is doable
        # but the boundary-detection state machine adds 80+ lines for
        # marginal benefit — we already cap at 1 GiB and Cloud Run has
        # 4 GiB. Keep it simple.
        raw = self.rfile.read(length)
        try:
            parts = _parse_multipart(raw, boundary.encode("ascii"))
        except Exception as exc:
            self.send_error(400, f"Bad multipart body: {exc}"); return

        # Pull out the fields we care about.
        file_part = next((p for p in parts if p["name"] == "file" and p.get("filename")), None)
        brand = next((p["data"].decode("utf-8", "replace").strip()
                      for p in parts if p["name"] == "brand"), "")
        title = next((p["data"].decode("utf-8", "replace").strip()
                      for p in parts if p["name"] == "title"), "")
        product = next((p["data"].decode("utf-8", "replace").strip()
                        for p in parts if p["name"] == "product"), "")
        duration_raw = next((p["data"].decode("utf-8", "replace").strip()
                             for p in parts if p["name"] == "durationSec"), "")

        if file_part is None:
            self.send_error(400, "Missing 'file' field"); return
        if not brand:
            self.send_error(400, "Missing 'brand' field"); return
        # Light video-mime sniff — Content-Type from the form is taken
        # as best-effort. If absent or wrong, fall back to extension.
        part_ctype = (file_part.get("content_type") or "").lower()
        orig_name = file_part.get("filename") or "upload.mp4"
        ext = os.path.splitext(orig_name)[1].lower().lstrip(".") or "mp4"
        if ext not in {"mp4", "mov", "m4v", "webm", "mkv"}:
            self.send_error(415, f"Unsupported file extension: .{ext}"); return
        # Don't let a stray text/* or application/* through — they
        # won't play on the tablet and only waste storage.
        if part_ctype and not (
            part_ctype.startswith("video/")
            or part_ctype == "application/octet-stream"
        ):
            self.send_error(415, f"Unsupported content type: {part_ctype}"); return

        duration_sec = 15
        if duration_raw:
            try:
                duration_sec = max(1, min(600, int(float(duration_raw))))
            except ValueError:
                pass

        # Default title from filename stem if blank.
        if not title:
            title = os.path.splitext(orig_name)[0]
        # Slug for the on-disk filename — predictable, URL-safe, no
        # spaces. The library `id` is just `upload-<timestamp>` so
        # IDs never collide with Drive-synced or seeded entries.
        ts_ms = int(time.time() * 1000)
        slug = _slug(title) or "video"
        video_id = f"upload-{ts_ms}"
        safe_filename = f"{video_id}-{slug}.{ext}"

        UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
        target = UPLOADS_DIR / safe_filename
        with open(target, "wb") as fh:
            fh.write(file_part["data"])
        size_bytes = target.stat().st_size

        # Append to library.json. We re-read fresh from disk rather
        # than trust the in-memory cache so a concurrent Drive sync
        # doesn't get clobbered.
        if LIBRARY_JSON.is_file():
            with open(LIBRARY_JSON, "r", encoding="utf-8") as f:
                lib = json.load(f)
        else:
            lib = {"brands": [], "videos": []}
        videos = lib.setdefault("videos", [])
        brands = lib.setdefault("brands", [])

        # Auto-register a brand record if this is the first video for
        # an unknown brand. Keeps the brand picker self-healing — the
        # CMS doesn't need a "create brand" flow for casual uploads.
        if brand and not any((b.get("id") or "").lower() == brand.lower() for b in brands):
            brands.append({"id": brand, "name": brand.replace("-", " ").title(), "videos": 0})

        entry = {
            "id":            video_id,
            "title":         title,
            "brand":         brand,
            "product":       product or None,
            "durationSec":   duration_sec,
            "duration":      _fmt_duration(duration_sec),
            "sizeMb":        round(size_bytes / 1_000_000.0, 1),
            "filename":      safe_filename,
            "mediaUrl":      f"/uploaded/{safe_filename}",
            "uploadedAt":    int(time.time() * 1000),
            "screens":       0,
            "defaultUnmute": False,
        }
        videos.append(entry)

        LIBRARY_JSON.parent.mkdir(parents=True, exist_ok=True)
        tmp = LIBRARY_JSON.with_suffix(LIBRARY_JSON.suffix + ".tmp")
        tmp.write_text(json.dumps(lib, indent=2), encoding="utf-8")
        tmp.replace(LIBRARY_JSON)
        _LIBRARY_CACHE["mtime"] = 0.0   # force re-read on next GET

        _log_activity(
            kind="upload",
            text=f"Uploaded \"{title}\" to {brand}",
            icon="upload",
        )
        self._send_json({"ok": True, "video": entry})

    def _serve_api_get(self) -> None:
        url = urllib.parse.urlparse(self.path)
        path = url.path
        params = urllib.parse.parse_qs(url.query)

        # ── Auth: who am I? ──────────────────────────────────────
        # Returns the current user (if a valid session cookie is present)
        # or {user: null}. Used by the frontend AuthGate on first paint
        # and after every login/logout to decide what to render.
        if path == "/api/auth/me":
            user = self._current_user()
            self._send_json({
                "user":             auth.public_user(user) if user else None,
                "googleClientId":   auth.GOOGLE_CLIENT_ID or None,
                "allowedDomains":   sorted(auth.ALLOWED_DOMAINS),
                "appVersion":       APP_VERSION,
                "latestRelease":    _release_info(),
            })
            return

        # ── Latest release ──────────────────────────────────────
        # Public — the login screen hits this to render a "Download
        # Player APK" button without sign-in, and the Android player
        # polls it to decide whether to self-update.
        if path == "/api/release/latest":
            info = _release_info() or {"error": "release_lookup_failed"}
            # Strip the internal-only keys (asset IDs) before responding.
            public_info = {k: v for k, v in info.items() if not k.startswith("_")}
            self._send_json(public_info)
            return

        # ── APK download proxy ──────────────────────────────────
        # /api/release/download/{modern|legacy}
        # The repo is private so anonymous browsers and tablets without
        # GitHub creds can't pull the asset directly. This handler
        # fetches the binary from GitHub using SCREENS_GITHUB_TOKEN and
        # streams it back to the client.
        m_dl = re.match(r"^/api/release/download/(modern|legacy)$", path)
        if m_dl:
            flavor = m_dl.group(1)
            self._serve_release_download(flavor)
            return

        # ── Auth: list users ──────────────────────────────────────
        if path == "/api/users":
            actor = self._require_perm("users.view")
            if actor is None:
                return
            role_order = {r: i for i, r in enumerate(auth.ROLES)}
            users = sorted(
                db.list_users(),
                key=lambda u: (
                    role_order.get(u.get("role"), 99),
                    (u.get("display_name") or "").lower(),
                ),
            )
            self._send_json({
                "users": [auth.public_user(u) for u in users],
                "roles": list(auth.ROLES),
            })
            return

        if path == "/api/state":
            # Per-screen state when ?screenId=<deviceId>; otherwise return a
            # synthetic "default" state (used by the CMS to show "current
            # global push" and by tablets that haven't picked their id yet).
            screen_id = (params.get("screenId") or [None])[0]
            with _STATE_LOCK:
                if screen_id:
                    s = _ensure_screen_state(screen_id)
                    # Drain pending commands as part of the response so the
                    # tablet sees them in one round-trip.
                    commands = list(s["pendingCommands"])
                    s["pendingCommands"] = []
                    if commands:
                        # Drained queue is a state change worth persisting —
                        # otherwise a redeploy after a command was emitted
                        # but before it was drained could re-fire it.
                        _save_per_screen()
                    # Per-screen splash resolution from the device's location.
                    screen_meta = _screens.get(screen_id, {})
                    location = screen_meta.get("location") or {}
                    splash = resolve_splash_for(location.get("city"), location.get("concept"))
                    # Enrich each pushed item with library-side flags
                    # (defaultUnmute) so the player can apply per-video
                    # audio at playback time. The items list is small
                    # per screen (a handful at most) so the lookup is
                    # cheap.
                    enriched_items = []
                    for it in s["items"]:
                        lib = _library_lookup_by_id(it.get("id") or "")
                        merged = dict(it)
                        merged["defaultUnmute"] = bool((lib or {}).get("defaultUnmute"))
                        enriched_items.append(merged)
                    # Sync-group playback hint. When the screen has a
                    # syncGroup set, compute "what every screen in this
                    # group should be playing right now" from the loop's
                    # epoch + per-item durations. Tablet seeks ExoPlayer
                    # to the returned itemId + positionMs on every poll
                    # if it has drifted past a threshold.
                    sync_group_id = s.get("syncGroup")
                    playback = None
                    if sync_group_id:
                        playback = _compute_playback(
                            items=enriched_items,
                            group_id=sync_group_id,
                            current_revision=s["revision"],
                            now=time.time(),
                        )
                    # v0.1.35: list the group's other members so the
                    # tablet's Device admin can render "you're in
                    # group X with Y other screens" without a
                    # second round-trip. Lightweight projection —
                    # just the bits the admin UI displays.
                    group_members: list[dict] = []
                    if sync_group_id:
                        now_ts = time.time()
                        for d, st in _per_screen.items():
                            if st.get("syncGroup") != sync_group_id:
                                continue
                            meta = _screens.get(d) or {}
                            last_hb = meta.get("lastHeartbeat") or 0
                            group_members.append({
                                "deviceId":   d,
                                "name":       meta.get("name") or d,
                                "online":     (now_ts - last_hb) < 15,
                                "screenCode": (meta.get("location") or {}).get("screenCode"),
                                "storeId":    (meta.get("location") or {}).get("storeId"),
                                "isSelf":     d == screen_id,
                            })
                        # Sort: self first, then alphabetical for stability.
                        group_members.sort(key=lambda m: (not m["isSelf"], (m.get("name") or "").lower()))
                    poll_mode = s.get("pollMode", DEFAULT_POLL_MODE)
                    payload = {
                        "screenId":    screen_id,
                        "revision":    s["revision"],
                        "items":       enriched_items,
                        "pushedAt":    s["pushedAt"],
                        # mixSplash forced off in sync groups: the
                        # server's loop math sees [items] but the
                        # tablet's queue would be [splash, items...] —
                        # the extra splash duration per loop drifts the
                        # tablet steadily out of sync and triggers a
                        # visible mid-item seek on every poll. Stored
                        # value is preserved; we just override what we
                        # tell the tablet so it doesn't mix splash
                        # while it's a group member.
                        "mixSplash":   False if sync_group_id else s["mixSplash"],
                        "audioOn":     s.get("audioOn", False),
                        "pollMode":    poll_mode,
                        # lowDataMode kept in the payload for old tablets
                        # that still read this field; new tablets read
                        # pollMode instead.
                        "lowDataMode": (poll_mode == "slow"),
                        "syncGroup":   sync_group_id,
                        "syncGroupMembers": group_members,
                        "playback":    playback,
                        "serverNowMs": int(time.time() * 1000),
                        # Display mode override (v0.1.14). null means
                        # "leave it alone." Non-null is a modeId the
                        # tablet previously reported in its heartbeat
                        # under supportedModes — the tablet sets
                        # Window.LayoutParams.preferredDisplayModeId to
                        # this value, which makes the system switch
                        # HDMI output to the matching mode.
                        "displayMode": s.get("displayMode"),
                        # v0.1.15: wall-clock cutoff for the giant-clock
                        # calibration overlay. Tablet renders the overlay
                        # until correctedNow() passes this; null = no
                        # overlay. Cleared automatically the moment the
                        # value falls into the past — we don't bother
                        # writing it back to null until the next time
                        # the field is set.
                        "calibrateUntilMs": s.get("calibrateUntilMs"),
                        "commands":    commands,
                        "splashUrl":   splash["url"] if splash else None,
                        "splashName":  splash["name"] if splash else None,
                    }
                else:
                    # Aggregate view for the CMS: sum across all screens.
                    revisions = [s["revision"] for s in _per_screen.values()]
                    items_any = next((s["items"] for s in _per_screen.values() if s["items"]), [])
                    payload = {
                        "revision": max(revisions) if revisions else 0,
                        "items":    items_any,
                        "screensWithContent": sum(1 for s in _per_screen.values() if s["items"]),
                    }
            self._send_json(payload)
            return

        if path == "/api/screens":
            if self._require_perm("screens.view") is None:
                return
            now = time.time()
            with _STATE_LOCK:
                screens = []
                for device_id, s in _screens.items():
                    state = _per_screen.get(device_id, {})
                    last = s.get("lastHeartbeat") or 0
                    record = {
                        **s,
                        "online":                (now - last) < 15,
                        "secondsSinceHeartbeat": round(now - last, 1) if last else None,
                        "currentRevision":       state.get("revision", 0),
                        "currentItems":          state.get("items", []),
                        "mixSplash":             state.get("mixSplash", True),
                        "audioOn":               state.get("audioOn", False),
                        "pollMode":              state.get("pollMode", DEFAULT_POLL_MODE),
                        "lowDataMode":           state.get("pollMode", DEFAULT_POLL_MODE) == "slow",
                        "syncGroup":             state.get("syncGroup"),
                        # The override the CMS most recently chose (or
                        # null for auto). The heartbeat carries the
                        # current ACTUAL active mode separately
                        # (activeDisplayMode in the **s spread above)
                        # — useful when the override hasn't taken
                        # effect yet because the tablet hasn't seen
                        # the new /api/state poll.
                        "displayMode":           state.get("displayMode"),
                    }
                    screens.append(record)
            self._send_json({"screens": screens})
            return

        # v0.1.25: per-device warning/error log stream. Same auth
        # gate as /api/crashes. Default returns the most recent
        # entries across all devices; ?deviceId=X narrows. ?limit=N
        # caps the result (default 200, max 2000).
        if path == "/api/logs":
            if self._require_perm("activity.view") is None:
                return
            LOGS_DIR.mkdir(parents=True, exist_ok=True)
            want_dev = (params.get("deviceId") or [None])[0]
            try:
                limit = int((params.get("limit") or ["200"])[0])
            except ValueError:
                limit = 200
            limit = max(1, min(2000, limit))
            files = (
                [LOGS_DIR / f"{want_dev}.jsonl"] if want_dev
                else sorted(LOGS_DIR.glob("*.jsonl"))
            )
            collected: list[dict] = []
            for f in files:
                if not f.is_file():
                    continue
                # Read last ~limit*200 bytes; way more than enough
                # for `limit` entries even with long messages.
                try:
                    size = f.stat().st_size
                    read_bytes = min(size, limit * 400)
                    with open(f, "rb") as fh:
                        fh.seek(size - read_bytes)
                        chunk = fh.read()
                except Exception:
                    continue
                for line in chunk.splitlines():
                    if not line.strip():
                        continue
                    try:
                        collected.append(json.loads(line))
                    except Exception:
                        continue
            # Newest first.
            collected.sort(key=lambda r: r.get("time") or 0, reverse=True)
            collected = collected[:limit]
            self._send_json({"entries": collected, "total": len(collected)})
            return

        # v0.1.21: crash reports collected from the tablets.
        # GET /api/crashes              → { crashes: [{file, ...summary}], total }
        # GET /api/crashes?file=<name>  → full crash record (stack + log)
        # Gated on the activity-view permission since crashes contain
        # the device + screen-code, same sensitivity as a heartbeat.
        if path == "/api/crashes":
            if self._require_perm("activity.view") is None:
                return
            CRASHES_DIR.mkdir(parents=True, exist_ok=True)
            want_file = (params.get("file") or [None])[0]
            if want_file:
                # Single-file read. Validate the filename can't escape
                # the directory.
                safe = re.sub(r"[^a-zA-Z0-9_.-]+", "", want_file)
                if not safe or safe != want_file or not safe.endswith(".json"):
                    self.send_error(400, "Bad crash file name"); return
                target = CRASHES_DIR / safe
                if not target.is_file():
                    self.send_error(404, "No such crash file"); return
                try:
                    payload = json.loads(target.read_text(encoding="utf-8"))
                except Exception:
                    self.send_error(500, "Crash file unreadable"); return
                self._send_json({"file": safe, "crash": payload})
                return
            # List view — newest first, summary only.
            files = sorted(
                (f for f in CRASHES_DIR.glob("*.json") if f.is_file()),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )[:200]
            out: list[dict] = []
            for f in files:
                try:
                    rec = json.loads(f.read_text(encoding="utf-8"))
                except Exception:
                    continue
                out.append({
                    "file":           f.name,
                    "timeMs":         rec.get("timeMs"),
                    "appVersion":     rec.get("appVersion"),
                    "deviceModel":    rec.get("deviceModel"),
                    "deviceId":       rec.get("deviceId"),
                    "screenCode":     rec.get("screenCode"),
                    "exceptionClass": rec.get("exceptionClass"),
                    "exceptionMessage": rec.get("exceptionMessage"),
                    "threadName":     rec.get("threadName"),
                })
            self._send_json({"crashes": out, "total": len(out)})
            return

        if path == "/api/library":
            # Library responses are ~450 kB on a typical fleet
            # (1,300+ videos). The CMS polls this every 30 s; without
            # an ETag every poll re-downloads the full payload even
            # when nothing's changed. ETag keyed on the library file's
            # mtime — Drive Sync bumps that on every scan, which is
            # exactly when the response actually changes.
            data = _load_library()
            etag = f'"lib-{int(_LIBRARY_CACHE.get("mtime") or 0)}"'
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", "no-store")
                self._cors_headers()
                self.end_headers()
                return
            self._send_json(data, extra_headers=[("ETag", etag)])
            return

        if path == "/api/library/info":
            if self._require_perm("library.sync") is None:
                return
            # Sync metadata for the Drive Sync settings tab.
            with _SYNC_LOCK:
                state = dict(_sync_state)
            # Library file mtime as a fallback "last refreshed" indicator —
            # useful when the server has just started and lastRunAt is None.
            try:
                state["fileMtime"] = LIBRARY_JSON.stat().st_mtime if LIBRARY_JSON.is_file() else None
            except Exception:
                state["fileMtime"] = None

            # Source identity differs by mode. The Drive Sync UI keys its
            # copy off `mode`:
            #   • drive-api  — cloud deploy, content fetched via Drive API
            #     using a service account. driveFolderId is the configured
            #     Brand Content folder; driveFolderName is fetched once on
            #     demand from the API for nicer display, falls back to id.
            #   • filesystem — dev laptop with a Drive-for-Desktop mount.
            #     `folder` is the local path serve.py walks.
            cloud = drive_client is not None and drive_client.is_configured()
            brands_id = os.environ.get("SCREENS_DRIVE_BRANDS_ID") if cloud else None
            if cloud and brands_id:
                state["mode"] = "drive-api"
                state["driveFolderId"] = brands_id
                # Best-effort folder name. Cached to avoid hitting Drive on
                # every poll (the UI hits /api/library/info every 2-30s).
                state["driveFolderName"] = _drive_folder_name_cached(brands_id)
                # Keep `folder` populated as a human-readable label so any
                # caller that just shows "folder" gets something sane.
                state["folder"] = state["driveFolderName"] or brands_id
            else:
                state["mode"] = "filesystem"
                state["folder"] = str(MEDIA_DIR)
            self._send_json(state)
            return

        if path == "/api/splashes":
            # Sorted lists make the CMS UI deterministic.
            brands = sorted(
                [v for k, v in _splash_registry.items() if k.startswith("brand:")],
                key=lambda m: m["name"],
            )
            concepts = sorted(
                [v for k, v in _splash_registry.items() if k.startswith("concept:")],
                key=lambda m: m["name"],
            )
            self._send_json({
                "brands":   brands,
                "concepts": concepts,
                "cityBrand": dict(_city_brand),  # current city → brand mapping
            })
            return

        if path == "/api/activity":
            if self._require_perm("activity.view") is None:
                return
            # Newest first so the CMS doesn't have to reverse client-side.
            with _STATE_LOCK:
                items = list(_ACTIVITY_LOG)
            items.reverse()
            self._send_json({"items": items})
            return

        self.send_error(404, "Unknown API endpoint")

    def _serve_api_post(self) -> None:
        path = self.path.split("?", 1)[0]
        body = self._read_json()

        # ── Auth: login ────────────────────────────────────────
        # body: { credential: "<google-jwt>" }. Returns the public user
        # shape and sets the HttpOnly session cookie.
        if path == "/api/auth/login":
            credential = (body.get("credential") or "").strip()
            if not credential:
                self._send_json({"error": "missing_credential"}, status=400)
                return
            try:
                user, token = auth.login_with_google_credential(
                    credential, self.headers.get("User-Agent")
                )
            except RuntimeError as e:
                # Server-side misconfiguration (no GOOGLE_CLIENT_ID).
                self._send_json({"error": "server_misconfigured", "detail": str(e)}, status=503)
                return
            except ValueError as e:
                # Verification or policy reject. The frontend keys off this
                # short error string for the message it shows the user.
                code = str(e)
                self._send_json({"error": code}, status=401)
                return
            _log_activity(
                kind="auth",
                text=f"{user['display_name']} signed in",
                icon="check",
                tone="ok",
                who=user["display_name"],
            )
            self._send_json(
                {"user": auth.public_user(user)},
                extra_headers=[("Set-Cookie", auth.session_cookie_value(token))],
            )
            return

        # ── Auth: logout ───────────────────────────────────────
        if path == "/api/auth/logout":
            token = auth.session_token_from_cookie_header(self.headers.get("Cookie"))
            user = self._current_user()
            if token:
                auth.logout(token)
            if user:
                _log_activity(
                    kind="auth",
                    text=f"{user['display_name']} signed out",
                    icon="close",
                    who=user["display_name"],
                )
            self._send_json(
                {"ok": True},
                extra_headers=[("Set-Cookie", auth.clear_cookie_value())],
            )
            return

        # ── Users: invite ──────────────────────────────────────
        # body: { email, displayName, role }. The invited user has to sign
        # in with Google before they actually appear in the workspace —
        # this just creates the row that login_with_google_credential will
        # match on email.
        if path == "/api/users":
            actor = self._require_perm("users.invite")
            if actor is None:
                return
            email = (body.get("email") or "").strip().lower()
            display_name = (body.get("displayName") or "").strip()
            role = (body.get("role") or "user").strip()
            if not email or not display_name:
                self._send_json({"error": "missing_fields"}, status=400)
                return
            if role not in auth.ROLES:
                self._send_json({"error": "bad_role"}, status=400)
                return
            if not auth.email_domain_allowed(email):
                self._send_json({"error": "domain_blocked"}, status=400)
                return
            if not auth.role_can_be_assigned_by(actor["role"], role):
                self._send_json({"error": "role_above_actor"}, status=403)
                return
            if role == "owner":
                # Owner is singular and never created via invite.
                self._send_json({"error": "cannot_invite_owner"}, status=400)
                return
            if db.find_user_by_email(email):
                self._send_json({"error": "already_exists"}, status=409)
                return
            user_id = auth._new_id()
            now = int(time.time())
            new_user = {
                "id":            user_id,
                "email":         email,
                "display_name":  display_name,
                "role":          role,
                "status":        "active",
                "created_at":    now,
                "invited_by":    actor["id"],
                "google_sub":    None,
                "picture_url":   None,
                "last_login_at": None,
            }
            db.insert_user(new_user)
            _log_activity(
                kind="auth",
                text=f"Invited {display_name} ({role})",
                icon="check",
                tone="ok",
                who=actor["display_name"],
            )
            self._send_json({"user": auth.public_user(new_user)})
            return

        # ── Push to one or more screens ──────────────────────────
        # body: { deviceIds: [...], items: [...], mode: "replace"|"append" }
        # If deviceIds is empty/missing, push to every registered screen.
        if path == "/api/push":
            if self._require_perm("screens.push") is None:
                return
            items = body.get("items") or []
            mode = body.get("mode") or "replace"
            requested = body.get("deviceIds") or []
            # Same fan-out semantics as /api/screens/<id>/playlist —
            # selecting one member of a sync group implicitly selects
            # all members, so the group doesn't fracture on the next
            # poll. Opt out with `fanOutToGroup: false`.
            fan_out = body.get("fanOutToGroup", True) is not False
            with _STATE_LOCK:
                base_targets = [d for d in requested if d in _screens] or list(_screens.keys())
                if fan_out:
                    expanded: set[str] = set()
                    for d in base_targets:
                        st = _per_screen.get(d) or {}
                        gid = st.get("syncGroup")
                        if gid:
                            expanded.update(
                                k for k, v in _per_screen.items()
                                if v.get("syncGroup") == gid
                            )
                        expanded.add(d)
                    targets = sorted(expanded)
                else:
                    targets = base_targets
                pushed = 0
                for device_id in targets:
                    s = _ensure_screen_state(device_id)
                    if mode == "append":
                        # Dedupe by id when appending so the same clip can't
                        # show up twice in a row.
                        existing_ids = {x.get("id") for x in s["items"]}
                        for v in items:
                            if v.get("id") not in existing_ids:
                                s["items"].append(v)
                    else:
                        s["items"] = list(items)
                    s["revision"] += 1
                    s["pushedAt"] = time.time()
                    pushed += 1
                top_rev = max((s["revision"] for s in _per_screen.values()), default=0)
                if pushed:
                    _save_per_screen()
            print(f"[push] mode={mode} items={len(items)} -> {pushed} screens", file=sys.stderr)
            verb = "Replaced playlist on" if mode == "replace" else "Added to"
            _log_activity(
                kind="push",
                text=f"{verb} {pushed} screen{'' if pushed == 1 else 's'} · {len(items)} video{'' if len(items) == 1 else 's'}",
                icon="upload",
            )
            self._send_json({"ok": True, "screensTargeted": pushed, "revision": top_rev, "items": len(items)})
            return

        if path == "/api/screens/register":
            device_id = body.get("deviceId") or "unknown"
            name = body.get("name") or device_id
            with _STATE_LOCK:
                _screens[device_id] = {
                    **_screens.get(device_id, {}),
                    "deviceId":        device_id,
                    "name":            name,
                    "location":        body.get("location"),
                    "registeredAt":    time.time(),
                    "lastHeartbeat":   time.time(),
                    "appVersion":      body.get("appVersion"),
                    "deviceModel":     body.get("deviceModel"),
                    "ramMb":           body.get("ramMb"),
                    "screenWidth":     body.get("screenWidth"),
                    "screenHeight":    body.get("screenHeight"),
                    "orientation":     body.get("orientation"),
                }
                is_new = "registeredAt" not in (_screens.get(device_id, {}))
                state = _ensure_screen_state(device_id)
                # Auto-group by store. When a screen registers with a
                # location.storeId and doesn't already have an explicit
                # sync group, default to "store:<storeId>" — so every
                # screen at the same store falls into the same group
                # without anyone touching the CMS. Admins can still
                # opt out (set syncGroup to null) or override (to a
                # custom group key like "wall-A").
                loc = body.get("location") or {}
                store_id = loc.get("storeId")
                if state.get("syncGroup") is None and store_id:
                    state["syncGroup"] = f"store:{store_id}"
                    _save_per_screen()
                _save_screens()
            print(f"[register] {device_id} ({name})", file=sys.stderr)
            _log_activity(
                kind="register",
                text=f"{name} {'registered' if is_new else 're-registered'}",
                icon="check",
                tone="ok",
                target=device_id,
            )
            self._send_json({"ok": True, "screenId": device_id})
            return

        # v0.1.15: light up every member of a sync group with a giant
        # synchronised clock so staff can stand in front of two screens
        # and visually confirm they tick on the same wall-clock second.
        # Body: { durationSec?: int (default 60) }. Returns the list of
        # screens affected.
        if path.startswith("/api/sync-groups/") and path.endswith("/calibrate"):
            if self._require_perm("screens.command") is None:
                return
            group_id = urllib.parse.unquote(path[len("/api/sync-groups/"):-len("/calibrate")])
            try:
                duration_sec = int(body.get("durationSec") or 60)
            except (TypeError, ValueError):
                self.send_error(400, "durationSec must be an integer"); return
            duration_sec = max(5, min(600, duration_sec))   # clamp 5 s – 10 min
            until_ms = int((time.time() + duration_sec) * 1000)
            with _STATE_LOCK:
                # Match every screen currently tagged with this group.
                # Also accept a single-screen group_id (deviceId) so the
                # CMS can calibrate a lone screen for an eye-check of
                # the clock-sync math.
                targets = [
                    d for d, s in _per_screen.items()
                    if s.get("syncGroup") == group_id
                ]
                if not targets and group_id in _per_screen:
                    targets = [group_id]
                for tid in targets:
                    st = _ensure_screen_state(tid)
                    st["calibrateUntilMs"] = until_ms
                    # Queue a refresh command so the tablet picks up the
                    # new field on its very next /api/state poll rather
                    # than waiting up to the poll interval. The tablet's
                    # poll loop drains pendingCommands and re-fetches.
                    st["pendingCommands"].append({"command": "refresh", "at": time.time()})
                _save_per_screen()
            _log_activity(
                kind="settings",
                text=f"Calibration started on {len(targets)} screen(s) in '{group_id}' ({duration_sec}s)",
                icon="settings",
                target=group_id,
            )
            self._send_json({"ok": True, "calibrateUntilMs": until_ms, "screensTargeted": len(targets)})
            return

        if path == "/api/screens/heartbeat":
            # Rich heartbeat — tablet streams device info every tick so the
            # CMS can render a true "Status" panel on screen detail.
            device_id = body.get("deviceId") or "unknown"
            with _STATE_LOCK:
                s = _screens.get(device_id)
                if not s:
                    s = {
                        "deviceId": device_id,
                        "name": body.get("name") or device_id,
                        "registeredAt": time.time(),
                    }
                    _screens[device_id] = s
                # Merge anything the tablet sent. None values mean "no change".
                for key in (
                    "name", "location", "appVersion", "deviceModel",
                    "ramMb", "screenWidth", "screenHeight", "orientation",
                    "tier", "cachedVideoIds", "cacheBytes", "freeStorageBytes",
                    "currentRevision", "status",
                    # v0.1.14: HDMI modes the box can output. Tablet
                    # enumerates Display.getSupportedModes() and pushes
                    # them up so the CMS can render a picker. Shape:
                    # [{"id": <int>, "w": <int>, "h": <int>, "hz": <float>}, ...]
                    # plus "activeDisplayMode": <int> for the currently-
                    # selected mode id (useful for showing a checkmark
                    # in the picker without the tablet first acking the
                    # override).
                    "supportedModes", "activeDisplayMode",
                    # v0.1.23: decoder-class tier (low|medium|high) and
                    # the per-item safe bitrate ceiling (Mbps) derived
                    # from RAM. CMS uses these to flag library entries
                    # that would crash a particular screen before the
                    # operator pushes them. The tablet itself enforces
                    # the cap; reporting it is just so the CMS can warn.
                    "decoderTier", "safeBitrateMbps",
                ):
                    val = body.get(key)
                    if val is not None:
                        s[key] = val
                s["lastHeartbeat"] = time.time()
                state = _ensure_screen_state(device_id)
                _save_screens()
            self._send_json({
                "ok":          True,
                "revision":    state["revision"],
                "mixSplash":   state["mixSplash"],
            })
            return

        # v0.1.25: tablets ship batches of warning/error log entries
        # here on every heartbeat tick. Stored as JSON lines in
        # <logs_dir>/<deviceId>.jsonl so the file grows monotonically
        # without a per-entry-file fan-out. Auth-free for the same
        # reason as /api/crashes — a tablet shipping diagnostics
        # might not have a valid CMS session at the moment it's
        # uploading. Size-cap below keeps a flapping tablet from
        # filling disk.
        if path == "/api/logs":
            body = self._read_json()
            if not isinstance(body, dict) or not body:
                self.send_error(400, "Empty or non-JSON body"); return
            device_id = (body.get("deviceId") or "unknown")[:80]
            safe_dev = re.sub(r"[^a-zA-Z0-9_.-]+", "_", device_id) or "unknown"
            entries = body.get("entries")
            if not isinstance(entries, list) or not entries:
                self._send_json({"ok": True, "wrote": 0}); return
            LOGS_DIR.mkdir(parents=True, exist_ok=True)
            target = LOGS_DIR / f"{safe_dev}.jsonl"
            # Trim if the file is over 2 MB — keep the tail. .jsonl
            # files don't have nested structure, so we can rebuild
            # the last N kB cheaply.
            try:
                if target.is_file() and target.stat().st_size > 2 * 1024 * 1024:
                    with open(target, "rb") as f:
                        f.seek(-1024 * 1024, os.SEEK_END)
                        tail = f.read()
                    # Drop the partial first line.
                    nl = tail.find(b"\n")
                    if nl >= 0:
                        tail = tail[nl + 1:]
                    with open(target, "wb") as f:
                        f.write(tail)
            except Exception:
                # Trimming is best-effort; if it fails the next write
                # still appends and we'll just have a bigger file.
                pass
            app_version = body.get("appVersion") or "unknown"
            with open(target, "ab") as f:
                for e in entries:
                    if not isinstance(e, dict):
                        continue
                    record = {
                        "deviceId":   device_id,
                        "appVersion": app_version,
                        "time":       e.get("time"),
                        "level":      e.get("level"),
                        "tag":        e.get("tag"),
                        "message":    e.get("message"),
                    }
                    if e.get("cause"):
                        record["cause"] = e.get("cause")
                    f.write(json.dumps(record).encode("utf-8"))
                    f.write(b"\n")
            self._send_json({"ok": True, "wrote": len(entries)})
            return

        # v0.1.21: tablets POST crash reports here on the launch
        # after they crash. Authentication is intentionally
        # tablet-friendly — the device might not have a valid user
        # session at the moment it's shipping a crash (e.g. the
        # crash itself wiped the session). We do not gate this on
        # _require_perm; the only abuse vector is filling disk,
        # which we cap below.
        if path == "/api/crashes":
            body = self._read_json()
            if not isinstance(body, dict) or not body:
                self.send_error(400, "Empty or non-JSON body"); return
            CRASHES_DIR.mkdir(parents=True, exist_ok=True)
            # Trim runaway spool: keep at most 500 files. Drop the
            # oldest when we'd exceed it. Generous cap so a flapping
            # tablet has room to log, low enough to fit comfortably
            # on /data.
            existing = sorted(CRASHES_DIR.glob("*.json"))
            while len(existing) >= 500:
                try:
                    existing[0].unlink()
                except Exception:
                    pass
                existing.pop(0)
            device_id = (body.get("deviceId") or "unknown")[:80]
            time_ms = int(body.get("timeMs") or time.time() * 1000)
            # Sanitise the deviceId chunk so it can't escape the
            # directory or contain shell-unfriendly characters.
            safe_dev = re.sub(r"[^a-zA-Z0-9_.-]+", "_", device_id) or "unknown"
            target = CRASHES_DIR / f"{safe_dev}-{time_ms}.json"
            target.write_text(json.dumps(body, indent=2), encoding="utf-8")
            screen_name = (_screens.get(device_id) or {}).get("name") or device_id
            _log_activity(
                kind="crash",
                text=f"Crash reported by {screen_name}: {body.get('exceptionClass') or 'unknown'}",
                icon="warning",
                tone="err",
                target=device_id,
            )
            self._send_json({"ok": True})
            return

        # ── Manually trigger a library scan (Drive Sync → Sync now) ─
        if path == "/api/library/refresh":
            if self._require_perm("library.sync") is None:
                return
            # Run on a background thread so the HTTP request doesn't hold up
            # the response. The UI polls /api/library/info to see when it's done.
            threading.Thread(target=run_library_scan, daemon=True).start()
            _log_activity(
                kind="sync",
                text="Drive sync started",
                icon="sync",
            )
            self._send_json({"ok": True, "queued": True})
            return

        # ── Splash mapping update ────────────────────────────────
        # POST /api/splashes/mapping  { city: "NYC", brand: "tmrw"|"smartech"|null }
        if path == "/api/splashes/mapping":
            if self._require_perm("settings.edit") is None:
                return
            city = (body.get("city") or "").strip()
            brand = body.get("brand")
            if not city:
                self.send_error(400, "Missing city"); return
            with _STATE_LOCK:
                if brand is None or brand == "":
                    _city_brand.pop(city, None)
                else:
                    _city_brand[city] = brand
                # Bump revisions on every screen so they re-fetch and pick up
                # the new splash without needing a poll-induced delay.
                for s in _per_screen.values():
                    s["revision"] += 1
                _save_per_screen()
            self._send_json({"ok": True, "cityBrand": dict(_city_brand)})
            return

        # ── Per-screen controls ──────────────────────────────────
        # POST /api/screens/<deviceId>/command         { command: "reboot"|"clearCache"|"unregister"|"update"|"refresh" }
        # POST /api/screens/<deviceId>/playlist        { items: [...], mode: "replace"|"append" }
        # POST /api/screens/<deviceId>/mix-splash      { mixSplash: bool }
        # POST /api/screens/<deviceId>/audio           { audioOn: bool }
        # POST /api/screens/<deviceId>/poll-mode       { pollMode: "fast"|"normal"|"slow" }
        # POST /api/screens/<deviceId>/low-data-mode   { lowDataMode: bool }   (legacy — writes pollMode)
        # POST /api/screens/<deviceId>/sync-group      { syncGroup: string | null }
        # POST /api/screens/<deviceId>/display-mode    { displayMode: int | null }
        m = re.match(r"^/api/screens/([^/]+)/(command|playlist|mix-splash|audio|poll-mode|low-data-mode|sync-group|display-mode)$", path)
        if m:
            device_id = urllib.parse.unquote(m.group(1))
            action = m.group(2)
            # Two callers hit these endpoints:
            #   • CMS users (push from the web admin) — gated on the
            #     `screens.push` / `screens.command` permission.
            #   • The tablet itself (staff overlay's playlist editor,
            #     mix-splash toggle, audio toggle) — no user session.
            #     We let the tablet edit *its own* state when the
            #     URL's deviceId matches a registered screen.
            # `command` is privileged either way (reboot, unregister)
            # and stays user-only.
            is_self_edit = (
                action in ("playlist", "mix-splash", "audio", "poll-mode", "low-data-mode", "sync-group", "display-mode")
                and device_id in _screens
            )
            if not is_self_edit:
                need = "screens.command" if action in ("command", "mix-splash") else "screens.push"
                if self._require_perm(need) is None:
                    return
            with _STATE_LOCK:
                if action == "command":
                    cmd = body.get("command")
                    if cmd not in ("reboot", "clearCache", "unregister", "update", "refresh"):
                        self.send_error(400, "Unknown command"); return
                    state = _ensure_screen_state(device_id)
                    state["pendingCommands"].append({"command": cmd, "at": time.time()})
                    _save_per_screen()
                    print(f"[command] {device_id} -> {cmd}", file=sys.stderr)
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    label = {
                        "reboot":     "Rebooted",
                        "clearCache": "Cleared cache on",
                        "unregister": "Unregistered",
                        "update":     "Triggered update on",
                        "refresh":    "Forced refresh on",
                    }[cmd]
                    _log_activity(
                        kind="command",
                        text=f"{label} {screen_name}",
                        icon={
                            "reboot":     "schedule",
                            "clearCache": "trash",
                            "unregister": "close",
                            "update":     "download",
                            "refresh":    "refresh",
                        }[cmd],
                        tone="err" if cmd == "unregister" else None,
                        target=device_id,
                    )
                    self._send_json({"ok": True, "queued": cmd})
                    return
                if action == "playlist":
                    items = body.get("items") or []
                    mode = body.get("mode") or "replace"
                    # If the target screen belongs to a sync group, fan
                    # out the push to every member. Sync only works when
                    # every member is on the same playlist + revision —
                    # pushing to a lone member would break the group on
                    # the next poll, which is almost never what the user
                    # actually wants. Caller can pass `fanOutToGroup:
                    # false` to opt out and push to just this screen.
                    fan_out = body.get("fanOutToGroup", True) is not False
                    base_state = _ensure_screen_state(device_id)
                    group_id = base_state.get("syncGroup") if fan_out else None
                    if group_id:
                        targets = [
                            d for d, s in _per_screen.items()
                            if s.get("syncGroup") == group_id
                        ]
                        # Defensive: make sure the original is included
                        # even if it hasn't shown up in the dict yet.
                        if device_id not in targets:
                            targets.append(device_id)
                    else:
                        targets = [device_id]
                    for tid in targets:
                        st = _ensure_screen_state(tid)
                        if mode == "append":
                            existing = {x.get("id") for x in st["items"]}
                            for v in items:
                                if v.get("id") not in existing:
                                    st["items"].append(v)
                        else:
                            st["items"] = list(items)
                        st["revision"] += 1
                        st["pushedAt"] = time.time()
                    # v0.1.16: when this push hits a sync group, anchor
                    # the loop epoch at now + COORDINATED_START_DELAY_SEC
                    # so every member's tablet does the coordinated-
                    # start pause-and-resume dance on the next poll.
                    # This is the ONLY legitimate trigger for a reset —
                    # _group_loop_epoch no longer ping-pongs the epoch
                    # based on per-screen revision counters that
                    # naturally diverge across group members.
                    if group_id:
                        _reset_group_loop_epoch(group_id, time.time())
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    verb = "Replaced playlist on" if mode == "replace" else "Added to"
                    if group_id and len(targets) > 1:
                        log_text = (
                            f"{verb} {len(targets)} screens in sync group "
                            f"'{group_id}' · {len(items)} video"
                            f"{'' if len(items) == 1 else 's'}"
                        )
                    else:
                        log_text = (
                            f"{verb} {screen_name} · {len(items)} video"
                            f"{'' if len(items) == 1 else 's'}"
                        )
                    _log_activity(
                        kind="push",
                        text=log_text,
                        icon="upload",
                        target=device_id,
                    )
                    self._send_json({
                        "ok": True,
                        "revision": base_state["revision"],
                        "screensTargeted": len(targets),
                        "syncGroup": group_id,
                    })
                    return
                if action == "mix-splash":
                    state = _ensure_screen_state(device_id)
                    state["mixSplash"] = bool(body.get("mixSplash", True))
                    state["revision"] += 1                  # bump so player picks up flag change
                    _save_per_screen()
                    self._send_json({"ok": True, "mixSplash": state["mixSplash"]})
                    return
                if action == "audio":
                    state = _ensure_screen_state(device_id)
                    state["audioOn"] = bool(body.get("audioOn", False))
                    _save_per_screen()
                    # Don't bump revision — audio toggles shouldn't
                    # force a full playlist re-init on the tablet.
                    # The player polls /api/state every ~3s and reads
                    # audioOn there, so the change picks up promptly
                    # without restarting the current video.
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    _log_activity(
                        kind="settings",
                        text=("Unmuted " if state["audioOn"] else "Muted ") + screen_name,
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "audioOn": state["audioOn"]})
                    return
                if action == "poll-mode":
                    raw = body.get("pollMode")
                    if raw not in POLL_MODES:
                        self.send_error(400, f"pollMode must be one of {POLL_MODES}"); return
                    state = _ensure_screen_state(device_id)
                    state["pollMode"] = raw
                    # Keep the legacy flag in sync so older tablets reading
                    # lowDataMode still behave correctly until they update.
                    state["lowDataMode"] = (raw == "slow")
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    pretty = {"fast": "Fast (10 s)", "normal": "Normal (60 s)", "slow": "Slow (10 min)"}[raw]
                    _log_activity(
                        kind="settings",
                        text=f"Poll mode → {pretty} on {screen_name}",
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "pollMode": raw})
                    return
                if action == "low-data-mode":
                    # Legacy alias kept for older clients that still call
                    # this endpoint. Maps to pollMode "slow" / "normal".
                    state = _ensure_screen_state(device_id)
                    legacy = bool(body.get("lowDataMode", False))
                    state["lowDataMode"] = legacy
                    state["pollMode"] = "slow" if legacy else DEFAULT_POLL_MODE
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    _log_activity(
                        kind="settings",
                        text=(
                            "Enabled low data mode on " if legacy
                            else "Disabled low data mode on "
                        ) + screen_name,
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "lowDataMode": legacy, "pollMode": state["pollMode"]})
                    return
                if action == "sync-group":
                    # Empty string or null clears the group; otherwise the
                    # string is stored verbatim (typically a store ID like
                    # "NYC-1" or a custom group key like "wall-A").
                    raw_group = body.get("syncGroup")
                    new_group = None
                    if isinstance(raw_group, str):
                        cleaned = raw_group.strip()
                        new_group = cleaned if cleaned else None
                    state = _ensure_screen_state(device_id)
                    state["syncGroup"] = new_group
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    _log_activity(
                        kind="settings",
                        text=(
                            f"Joined sync group '{new_group}' on " if new_group
                            else "Left sync group on "
                        ) + screen_name,
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "syncGroup": new_group})
                    return
                if action == "display-mode":
                    # Pick an HDMI mode for this screen. null = auto
                    # (leave the box alone). Non-null must be an int
                    # matching a Display.Mode.modeId the tablet
                    # previously reported in `supportedModes`. We
                    # don't validate it against that list here — the
                    # tablet does that on the receiving end and falls
                    # back to "no change" if the id has disappeared
                    # (e.g. a different cable / TV is now attached so
                    # the supported list is different).
                    raw_mode = body.get("displayMode")
                    new_mode: int | None
                    if raw_mode is None:
                        new_mode = None
                    elif isinstance(raw_mode, bool):
                        # Bool is a subclass of int in Python — guard
                        # explicitly so True/False can't sneak in as
                        # mode id 1/0.
                        self.send_error(400, "displayMode must be int or null"); return
                    elif isinstance(raw_mode, int):
                        new_mode = raw_mode
                    else:
                        self.send_error(400, "displayMode must be int or null"); return
                    state = _ensure_screen_state(device_id)
                    state["displayMode"] = new_mode
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    # Try to render a human label using the modes the
                    # tablet has reported — purely for the activity log.
                    label = "auto"
                    if new_mode is not None:
                        modes = (_screens.get(device_id) or {}).get("supportedModes") or []
                        for mm in modes:
                            try:
                                if int(mm.get("id")) == new_mode:
                                    label = f"{mm.get('w')}×{mm.get('h')} @ {round(float(mm.get('hz') or 0))}Hz"
                                    break
                            except Exception:
                                pass
                        else:
                            label = f"mode {new_mode}"
                    _log_activity(
                        kind="settings",
                        text=f"Display mode → {label} on {screen_name}",
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "displayMode": new_mode})
                    return

        self.send_error(404, "Unknown API endpoint")

    # ── Users PATCH/DELETE ────────────────────────────────────────
    # Edit (role / displayName / status) and remove. Owner is protected
    # against demotion or deletion at every entry point.

    def _serve_api_patch(self) -> None:
        path = self.path.split("?", 1)[0]
        body = self._read_json()

        # ── Update a video's library-side flags ─────────────────
        # PATCH /api/library/videos/<id> { defaultUnmute: bool }
        # Used by the CMS content library to toggle a per-video
        # "default to unmute" flag. The next /api/state response to
        # the tablet includes the new value, and the player applies
        # it on the next onMediaItemTransition.
        m_v = re.match(r"^/api/library/videos/([A-Za-z0-9_\-]+)$", path)
        if m_v:
            actor = self._require_perm("library.edit")
            if actor is None:
                return
            video_id = m_v.group(1)
            patch: dict = {}
            if "defaultUnmute" in body:
                patch["defaultUnmute"] = bool(body.get("defaultUnmute"))
            if not patch:
                self._send_json({"error": "nothing_to_update"}, status=400); return
            updated = _update_video_in_library(video_id, patch)
            if not updated:
                self._send_json({"error": "video_not_found"}, status=404); return
            _log_activity(
                kind="settings",
                text=("Unmuted '" if patch.get("defaultUnmute") else "Muted '")
                     + (updated.get("title") or video_id) + "' by default",
                icon="settings",
                who=actor.get("display_name"),
            )
            self._send_json({"video": updated})
            return

        m = re.match(r"^/api/users/([A-Za-z0-9_-]+)$", path)
        if not m:
            self.send_error(404, "Unknown API endpoint"); return
        actor = self._require_perm("users.edit")
        if actor is None:
            return
        target_id = m.group(1)
        target = db.find_user_by_id(target_id)
        if not target:
            self._send_json({"error": "not_found"}, status=404); return

        # Owner row is locked: only Owner can edit Owner, and Owner can't
        # be demoted (would leave the workspace ownerless).
        if target.get("role") == "owner" and actor["id"] != target["id"]:
            self._send_json({"error": "owner_locked"}, status=403); return

        patch: dict = {}
        if "displayName" in body:
            name = (body.get("displayName") or "").strip()
            if not name:
                self._send_json({"error": "missing_displayName"}, status=400); return
            patch["display_name"] = name
        if "role" in body:
            role = (body.get("role") or "").strip()
            if role not in auth.ROLES:
                self._send_json({"error": "bad_role"}, status=400); return
            if target.get("role") == "owner" and role != "owner":
                self._send_json({"error": "cannot_demote_owner"}, status=403); return
            if role == "owner" and target.get("role") != "owner":
                self._send_json({"error": "cannot_promote_to_owner"}, status=403); return
            if not auth.role_can_be_assigned_by(actor["role"], role):
                self._send_json({"error": "role_above_actor"}, status=403); return
            patch["role"] = role
        if "status" in body:
            status_val = (body.get("status") or "").strip()
            if status_val not in ("active", "disabled"):
                self._send_json({"error": "bad_status"}, status=400); return
            if target.get("role") == "owner" and status_val != "active":
                self._send_json({"error": "cannot_disable_owner"}, status=403); return
            patch["status"] = status_val
            # Disabling kills all live sessions for that user.
            if status_val == "disabled":
                db.delete_sessions_for_user(target_id)
        if not patch:
            self._send_json({"error": "nothing_to_update"}, status=400); return
        updated = db.update_user(target_id, patch)
        _log_activity(
            kind="auth",
            text=f"Updated {updated['display_name']} ({updated['role']})",
            icon="check",
            who=actor["display_name"],
        )
        self._send_json({"user": auth.public_user(updated)})

    def _serve_api_delete(self) -> None:
        path = self.path.split("?", 1)[0]
        m = re.match(r"^/api/users/([A-Za-z0-9_-]+)$", path)
        if not m:
            self.send_error(404, "Unknown API endpoint"); return
        actor = self._require_perm("users.delete")
        if actor is None:
            return
        target_id = m.group(1)
        target = db.find_user_by_id(target_id)
        if not target:
            self._send_json({"error": "not_found"}, status=404); return
        if target.get("role") == "owner":
            self._send_json({"error": "cannot_delete_owner"}, status=403); return
        if target["id"] == actor["id"]:
            self._send_json({"error": "cannot_delete_self"}, status=403); return
        db.delete_sessions_for_user(target_id)
        db.delete_user(target_id)
        _log_activity(
            kind="auth",
            text=f"Removed {target['display_name']}",
            icon="close",
            tone="err",
            who=actor["display_name"],
        )
        self._send_json({"ok": True})

    def _serve_release_download(self, flavor: str) -> None:
        """Stream the latest release's modern or legacy APK to the client.

        Works in two modes:
          • Public repo (no token): hits the asset API anonymously;
            GitHub 302s to its CDN; urllib follows the redirect and we
            stream the resulting bytes. The reason this exists when
            direct GitHub links would also work: some corporate
            networks allow `github.com` but block
            `release-assets.githubusercontent.com`, so a click on the
            release URL falls off a cliff. Proxying through our own
            host sidesteps that.
          • Private repo (with SCREENS_GITHUB_TOKEN): the asset API
            with octet-stream Accept returns the file directly under
            auth.

        Pipes the bytes through in 64 KB chunks so Python's HTTP
        server doesn't have to buffer the whole APK in memory."""
        info = _release_info()
        if not info:
            self.send_error(503, "Release info unavailable"); return
        asset_id = info.get(f"_{flavor}AssetId")
        if not asset_id:
            self.send_error(404, f"No {flavor} APK in latest release"); return
        api_url = f"https://api.github.com/repos/{GITHUB_RELEASES_REPO}/releases/assets/{asset_id}"
        req = urllib.request.Request(
            api_url,
            headers=_github_headers(accept="application/octet-stream"),
        )
        try:
            # urllib follows the 302 from the asset API to the CDN by
            # default. The resulting `upstream` is the actual binary
            # stream; we just relay it.
            upstream = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as e:
            print(f"[release-download] upstream HTTP {e.code}: {e.reason}", file=sys.stderr)
            self.send_error(502, f"Upstream fetch failed ({e.code})")
            return
        except Exception as e:
            print(f"[release-download] upstream fetch failed: {e}", file=sys.stderr)
            self.send_error(502, "Upstream fetch failed")
            return
        try:
            filename = f"screens-player-{flavor}-v{info.get('versionName','')}.apk"
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            content_length = upstream.headers.get("Content-Length")
            if content_length:
                self.send_header("Content-Length", content_length)
            # Tell the browser to save with our nice filename rather
            # than inheriting the opaque CDN one. Curl + OkHttp honour
            # this too.
            self.send_header(
                "Content-Disposition",
                f'attachment; filename="{filename}"',
            )
            self.send_header("Cache-Control", "no-store")
            self._cors_headers()
            self.end_headers()
            try:
                while True:
                    chunk = upstream.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError, OSError):
                # Client cancelled — happens when a user closes the
                # download dialog mid-stream. Drop silently.
                return
        finally:
            upstream.close()

    def _serve_media(self, head_only: bool) -> None:
        # Cloud mode: /media/<drive_file_id> means stream from Drive API.
        # Local mode: /media/<brand>/<file.mp4> is a filesystem path.
        # Detection: a Drive file ID has no slashes after /media/ and
        # matches the alphanumeric/underscore/hyphen alphabet — see
        # drive_client.looks_like_drive_id.
        if self.path.startswith("/media/"):
            seg = self.path[len("/media/"):].split("?", 1)[0].split("#", 1)[0].rstrip("/")
            if (
                drive_client is not None
                and drive_client.is_configured()
                and "/" not in seg
                and drive_client.looks_like_drive_id(seg)
            ):
                self._serve_media_from_drive(seg, head_only)
                return

        path = Path(self.translate_path(self.path))
        if not path.is_file():
            self.send_error(404, "Media file not found")
            return

        size = path.stat().st_size
        ctype = self.guess_type(str(path))

        range_header = self.headers.get("Range")
        start, end = 0, size - 1
        partial = False
        if range_header:
            m = RANGE_RE.match(range_header)
            if m:
                start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
                end = min(end, size - 1)
                if start > end:
                    self.send_error(416, "Requested range not satisfiable")
                    self.send_header("Content-Range", f"bytes */{size}")
                    return
                partial = True

        length = end - start + 1
        # Cloud Run buffers responses with an explicit Content-Length
        # up to ~32 MB. Splash files often exceed that (4K Smartech
        # splash is 69 MB), so for full-file GETs above the threshold
        # we drop Content-Length and signal connection-close. Range
        # responses keep their length — the range is bounded by the
        # client's request, never exceeds 32 MB in normal use, and
        # clients rely on the exact byte count for seeking.
        STREAM_THRESHOLD = 32 * 1024 * 1024
        stream_no_length = (not partial) and (length > STREAM_THRESHOLD)

        if partial:
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)

        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        if stream_no_length:
            self.send_header("Connection", "close")
            self.close_connection = True
        else:
            self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.end_headers()

        if head_only:
            return

        try:
            with open(path, "rb") as f:
                f.seek(start)
                remaining = length
                chunk = 64 * 1024
                while remaining > 0:
                    buf = f.read(min(chunk, remaining))
                    if not buf:
                        break
                    try:
                        self.wfile.write(buf)
                    except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError, OSError):
                        # Browser/tablet cancelled the range — normal during
                        # seek, hover-then-leave, and tablet polling. Don't
                        # spam the log with tracebacks.
                        return
                    remaining -= len(buf)
        except FileNotFoundError:
            # Race with file deletion. Already sent headers, nothing else to do.
            pass

    def _serve_media_from_drive(self, file_id: str, head_only: bool) -> None:
        """Stream a Drive file out via /media/<file_id>.

        Forwards the client's Range header to Drive's alt=media endpoint
        and pipes the response back to the HTTP socket. The drive_client
        download is buffered server-side (acceptable for typical brand
        videos under ~100MB; the player APK caches client-side on first
        download anyway, so it's a one-time hit per video per device).

        Metadata (size + mime) comes from library.json when available —
        avoids a per-request Drive `files.get()` round-trip that
        Drive's per-user QPS limit was 404ing under load (we saw
        ~50/24/3 split of 404/200/500 across recent /media/ requests
        after a fresh scan). Falls back to a live API call only when
        the file isn't in the cached library.
        """
        if drive_client is None:
            self.send_error(500, "Drive client unavailable")
            return

        size = 0
        ctype = "video/mp4"
        cached = _library_lookup_by_drive_id(file_id)
        if cached:
            try:
                size = int((cached.get("sizeMb") or 0) * 1024 * 1024)
            except Exception:
                size = 0
            # The library doesn't store mimetype; fall back to mp4
            # which all our brand content uses. Real Range handling
            # comes from the upstream response anyway.
        else:
            # Not in library — fall back to Drive for metadata. This
            # still pays the rate-limit tax but only for files we
            # didn't index in the last scan (rare).
            try:
                meta = drive_client.get_metadata(file_id)
                size = int(meta.get("size") or 0)
                ctype = meta.get("mimeType") or "video/mp4"
            except Exception as e:
                print(f"[/media] metadata lookup failed for {file_id}: {e}", file=sys.stderr)
                self.send_error(404, f"Drive file not found: {e}")
                return

        range_header = self.headers.get("Range")

        # HEAD doesn't transfer bytes — just respond with metadata.
        if head_only:
            if range_header and size:
                self.send_response(206)
                self.send_header("Content-Range", f"bytes 0-{size - 1}/{size}")
            else:
                self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            if size:
                self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "public, max-age=3600")
            self.end_headers()
            return

        # GET: stream the body. Headers go out on the first chunk so we
        # can echo the actual status (206 vs 200) Drive returned. For
        # full-file responses we deliberately OMIT Content-Length and
        # close the connection at end-of-body — the cached `sizeMb`
        # from library.json is rounded to 1 decimal MB, so claiming an
        # exact byte count there causes OkHttp on the player to
        # premature-EOF the stream and fail the cache write. Range
        # responses get the precise length back from drive_client.
        headers_sent = False
        attempt = 0
        while True:
            attempt += 1
            try:
                for status, start, end, chunk in drive_client.stream_file(
                    file_id, range_header=range_header
                ):
                    if not headers_sent:
                        if status == 206:
                            self.send_response(206)
                            self.send_header("Content-Range", f"bytes {start}-{end}/*")
                            self.send_header("Content-Length", str(end - start + 1))
                        else:
                            self.send_response(200)
                            # No Content-Length: actual byte count is
                            # only knowable as we stream. Connection:close
                            # tells HTTP/1.1 clients to read until EOF;
                            # the close_connection flag makes the Python
                            # HTTP server actually drop the socket after
                            # this response instead of trying to reuse.
                            self.send_header("Connection", "close")
                            self.close_connection = True
                        self.send_header("Content-Type", ctype)
                        self.send_header("Accept-Ranges", "bytes")
                        self.send_header("Cache-Control", "public, max-age=3600")
                        self.end_headers()
                        headers_sent = True
                    try:
                        self.wfile.write(chunk)
                    except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError, OSError):
                        # Client cancelled (seek, navigation, polling reset).
                        return
                # Success — drop out of the retry loop.
                break
            except Exception as e:
                if not headers_sent and attempt < 2:
                    print(f"[/media] stream attempt {attempt} failed for {file_id}: {e} — retrying", file=sys.stderr)
                    continue
                if not headers_sent:
                    print(f"[/media] stream failed for {file_id}: {e}", file=sys.stderr)
                    self.send_error(500, f"Drive stream failed: {e}")
                return

    # Quieter than the default stdlib log line.
    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write(f"{self.address_string()} - {fmt % args}\n")

    def handle_one_request(self) -> None:
        try:
            super().handle_one_request()
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
            # Peer dropped mid-response (tablet poll, browser hover, etc.).
            # Don't bubble — would otherwise dump a multi-line traceback into
            # the demo console for every cancelled request.
            self.close_connection = True


class ThreadedServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    global SPLASH_DIR

    if not APP_DIR.is_dir():
        sys.exit(f"App folder missing: {APP_DIR}")
    if not MEDIA_DIR.is_dir():
        print(f"Warning: media folder not found at {MEDIA_DIR}", file=sys.stderr)

    # Cloud mode: pull the splash MP4s from Drive into a local cache so
    # /splash/<file> serves from disk with proper Range support. Repoint
    # SPLASH_DIR at the cache so _build_splash_registry walks it.
    if drive_client and drive_client.is_configured():
        cache = _hydrate_splashes_from_drive()
        if cache:
            SPLASH_DIR = cache
            print(f"[splash] hydrated from Drive into {cache}")

    _build_splash_registry()
    # Restore per-screen playlists + tablet registry from the last
    # process's persisted state. Without this every Cloud Run redeploy
    # would wipe every screen's playlist and audio/splash flags.
    _load_state_from_disk()
    # Daily re-scan in a background thread. Doesn't run on boot — the
    # existing library.json from the last run is used; Drive Sync UI lets
    # the user trigger an on-demand scan if needed.
    threading.Thread(target=daily_sync_loop, daemon=True).start()
    # Initial scan if the library is missing or empty. Triggers
    # automatically on fresh deploys so the CMS doesn't show "no
    # content" for up to 24 hours while waiting for the daily timer.
    # Runs in the background so the HTTP server starts immediately.
    _trigger_initial_sync_if_needed()
    httpd = ThreadedServer((BIND, PORT), Handler)
    ip = lan_ip()
    # Stick to ASCII in console output — Windows cp1252 chokes on arrows.
    print(f"Screens CMS demo")
    print(f"  CMS (this laptop):  http://localhost:{PORT}/#/dashboard")
    print(f"  Tablet should use:  http://{ip}:{PORT}")
    print(f"  app      = {APP_DIR}")
    print(f"  media    = {MEDIA_DIR}")
    print(f"  splash   = {SPLASH_DIR}  ({len(_splash_registry)} splashes loaded)")
    print(f"  library  = {LIBRARY_JSON.name}  (re-scans every 24h, on-demand via Drive Sync)")
    print("Ctrl+C to stop.\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        httpd.shutdown()


if __name__ == "__main__":
    main()
