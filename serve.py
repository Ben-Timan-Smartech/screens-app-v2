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

import hmac
import html
import http.server
import json
import os
import re
import secrets
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
# v0.1.92: guided brand experiences — self-contained, single-file HTML with no
# external assets (see interactive/README.md). Served public + read-only at
# /interactive/<name>.html; the tablet downloads one once and caches it, then
# renders from the local copy, so the experience runs with no network. Kept in
# the repo (not the uploads bucket) so content is versioned + code-reviewed and
# ships on a normal server deploy — no APK release needed to change it.
INTERACTIVE_DIR = PROJECT / "interactive"

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


def _github_urlopen(url: str, *, accept: str = "application/vnd.github+json", timeout: int = 8, extra_headers: dict | None = None):
    """Open a GitHub API URL, falling back to an *unauthenticated* request
    when a configured token is rejected.

    The releases repo is public. A stale/expired `SCREENS_GITHUB_TOKEN`
    makes even public-repo requests fail with 401 (GitHub doesn't ignore a
    bad token and serve anonymously — it rejects the call), which silently
    broke `/api/release/latest` (returns empty → players think they're up
    to date) and the APK download proxy. So: try the token first if set,
    then retry once with no Authorization header. Raises the last error if
    both attempts fail."""
    token = os.environ.get("SCREENS_GITHUB_TOKEN")
    header_sets = []
    if token:
        header_sets.append(_github_headers(accept=accept))
    anon = {k: v for k, v in _github_headers(accept=accept).items() if k != "Authorization"}
    header_sets.append(anon)
    if extra_headers:
        # v0.1.78: forward client headers (notably Range) to GitHub + the CDN
        # so the APK proxy supports resumable downloads.
        for h in header_sets:
            h.update(extra_headers)
    last_exc: Exception | None = None
    for i, headers in enumerate(header_sets):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout)
        except urllib.error.HTTPError as e:
            last_exc = e
            authed = "Authorization" in headers
            if authed and e.code in (401, 403, 404) and i < len(header_sets) - 1:
                print(f"[github] token rejected ({e.code}) on {url}; retrying anonymously", file=sys.stderr)
                continue
            raise
        except Exception as e:
            last_exc = e
            if i < len(header_sets) - 1:
                continue
            raise
    if last_exc:
        raise last_exc


def _fetch_latest_release() -> dict | None:
    """Hit GitHub's API for the latest release. Returns the parsed JSON
    or None on any error. Errors are swallowed because the consumers
    (login page, player) both have graceful "release info unavailable"
    paths — they should never crash because GitHub is briefly slow."""
    url = f"https://api.github.com/repos/{GITHUB_RELEASES_REPO}/releases/latest"
    try:
        with _github_urlopen(url, timeout=8) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[release] fetch failed: {e}", file=sys.stderr)
        return None


def _latest_tag_from_atom() -> str | None:
    """Token-free fallback for the latest release tag.

    Reads the PUBLIC `releases.atom` feed, served by github.com — NOT
    api.github.com — so it isn't subject to the API's 60/hr anonymous
    per-IP limit that Cloud Run's shared egress IP keeps tripping once the
    `SCREENS_GITHUB_TOKEN` is dead. The newest release is the first
    `<entry>`; its alternate link is `…/releases/tag/<tag>`. Returns the tag
    (e.g. "v0.1.77") or None."""
    url = f"https://github.com/{GITHUB_RELEASES_REPO}/releases.atom"
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": "screens-app-v2-server", "Accept": "application/atom+xml"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            xml = resp.read().decode("utf-8", "replace")
    except Exception as e:
        print(f"[release] atom fallback failed: {e}", file=sys.stderr)
        return None
    m = re.search(r"/releases/tag/([^\"'<>\s]+)", xml)
    return m.group(1) if m else None


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
    assets: list = []
    notes = ""
    published_at = None
    release_url = None
    if raw:
        tag = raw.get("tag_name") or ""
        assets = raw.get("assets") or []
        notes = raw.get("body") or ""
        published_at = raw.get("published_at")
        release_url = raw.get("html_url")
    else:
        # API unreachable — typically a dead SCREENS_GITHUB_TOKEN plus
        # anonymous api.github.com calls that Cloud Run's shared egress IP
        # gets rate-limited on (60/hr per IP). Fall back to the PUBLIC
        # releases.atom feed (github.com, not the rate-limited
        # api.github.com) for the latest tag. That's all we need: APKs
        # download via the public release-download URL, which requires no
        # asset IDs (see _serve_release_download). Keeps updates flowing
        # with no token at all.
        tag = _latest_tag_from_atom() or ""
        if tag:
            release_url = f"https://github.com/{GITHUB_RELEASES_REPO}/releases/tag/{tag}"
            print(f"[release] API unavailable — atom fallback, latest tag {tag}", file=sys.stderr)
    if not tag:
        # Couldn't reach GitHub at all. Don't poison the cache with empty
        # data — let the next request retry.
        return {}
    version_name = tag[1:] if tag.startswith("v") else tag
    # Match: MAJOR.MINOR.PATCH → MAJOR*10000 + MINOR*100 + PATCH (mirrors
    # the formula in player/app/build.gradle.kts so the player can
    # compare server-reported versionCode to its own BuildConfig).
    try:
        major, minor, patch = (int(p) for p in version_name.split(".")[:3])
        version_code = major * 10_000 + minor * 100 + patch
    except (ValueError, TypeError):
        version_code = 0
    def _find_asset(flavor: str) -> dict | None:
        for a in assets:
            if flavor in (a.get("name") or "").lower():
                return a
        return None
    modern_asset = _find_asset("modern")
    legacy_asset = _find_asset("legacy")
    # On the atom fallback we have no asset list — our release workflow
    # always ships both flavors, so advertise both URLs in that case.
    have_assets = raw is not None

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
        "modernUrl":    _apk_url("modern") if (modern_asset or not have_assets) else None,
        "legacyUrl":    _apk_url("legacy") if (legacy_asset or not have_assets) else None,
        "publishedAt":  published_at,
        "releaseUrl":   release_url,
        "notes":        notes,
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

# v0.1.75: CMS-uploaded splash overrides. One subfolder per brand/concept
# (key = "<kind>-<name>" slug) holding landscape.* / portrait.* + a
# meta.json recording the exact {kind, name}. Lives under the persistent
# uploads dir so it survives redeploys, and is overlaid onto the Drive-
# scanned splash registry (see _apply_uploaded_splashes) — letting an
# operator replace a screen's splash straight from the CMS, no Drive
# round-trip and no APK release.
SPLASH_UPLOADS_DIR = UPLOADS_DIR / "splashes"

# v0.1.96: CMS-uploaded guided experiences. Lives under the persistent uploads
# dir (NOT baked into the container image like interactive/ — that's exactly the
# trap that made v0.1.92's vendored content 404 in prod), so an admin can add a
# brand experience with no deploy and it survives redeploys. Served by the same
# public /interactive/<name>.html route the vendored ones use, so the tablet
# caches uploaded and built-in experiences identically.
EXPERIENCE_UPLOADS_DIR = UPLOADS_DIR / "experiences"

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
# v0.1.38: stores added from the CMS Locations tab. Built-ins live in
# the static taxonomy on both clients (app/components/data.jsx and
# LocationTaxonomy.kt) so the picker has a default if /api/stores is
# unreachable; this file is just the additions.
CUSTOM_STORES_JSON = Path(os.environ.get(
    "SCREENS_CUSTOM_STORES_PATH",
    _state_path_default("custom_stores.json"),
))
# v0.1.96: index of CMS-uploaded guided experiences. One entry per uploaded
# file: {id, name, brand, filename, sizeBytes, uploadedAt, uploadedBy}. The
# vendored interactive/*.html are listed alongside these at runtime (see
# _list_experiences) but aren't in this file — they ship with the code.
EXPERIENCES_JSON = Path(os.environ.get(
    "SCREENS_EXPERIENCES_PATH",
    _state_path_default("experiences.json"),
))
# v0.1.56: city → brand splash mapping. The dict is mutated by the
# Settings → Splashes UI; pre-v0.1.56 it lived only in memory, so any
# Cloud Run redeploy silently reset it to defaults. Now persisted
# alongside the rest of the state so the operator's choices survive.
CITY_BRAND_JSON = Path(os.environ.get(
    "SCREENS_CITY_BRAND_PATH",
    _state_path_default("city_brand.json"),
))
# v0.1.58: owner-managed integration secrets (Brand Asset Manager API key,
# etc.). Stored as a flat {name: {value, updatedAt, updatedBy}} dict so we
# can extend with more keys without an endpoint change. Lives on the
# FUSE-mounted bucket on Cloud Run; an env var (SCREENS_BRAND_API_KEY)
# seeds the value on first boot but the on-disk JSON wins after that.
SECRETS_JSON = Path(os.environ.get(
    "SCREENS_SECRETS_PATH",
    _state_path_default("secrets.json"),
))

# Cloud Run injects $PORT (defaults to 8080); on a laptop we keep 8765.
PORT = int(os.environ.get("PORT", "8765"))
BIND = "0.0.0.0"   # Listen on all interfaces so the tablet can reach us on LAN.

# Shared secret for the read-only /api/metrics endpoint (Claude Projects
# Dashboard). Set on the Cloud Run service; the same value goes on the
# dashboard side. Empty = the endpoint is closed (every request 401s).
METRICS_KEY = os.environ.get("METRICS_KEY", "")

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
# v0.1.38: keyed by slug id. {id, name, address, city}
_custom_stores: dict[str, dict] = {}
# v0.1.96: uploaded guided experiences (the vendored interactive/*.html aren't
# in here — see _list_experiences). {id, name, brand, filename, sizeBytes,
# uploadedAt, uploadedBy}
_experiences: list[dict] = []


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


def _atomic_write_text(path: Path, text: str) -> None:
    """Atomically write a pre-serialised string. Safe to call WITHOUT holding
    _STATE_LOCK — and hot paths should, so the (gcsfuse-backed, occasionally
    slow) write never runs under the lock and stalls other request threads.
    The temp file name carries the writer's thread id so a background flush and
    a rare synchronous save can't clobber each other's temp file."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + f".{threading.get_ident()}.tmp")
        tmp.write_text(text, encoding="utf-8")
        tmp.replace(path)
    except Exception as e:
        # We don't want a disk hiccup to crash an API request — log and
        # continue. Worst case the next mutation re-tries the write.
        print(f"[state] atomic write to {path} failed: {e}", file=sys.stderr)


def _atomic_write_json(path: Path, data: object) -> None:
    """Atomic JSON write. Caller does NOT need to hold _STATE_LOCK —
    callers in this module already do, but this helper doesn't assume.
    Serialises and writes on the calling thread; fleet-frequency state savers
    should instead use the coalesced *_soon variants, which hand the write to
    the background flusher (see _flush_state_loop) so it never runs under the
    lock."""
    _atomic_write_text(path, json.dumps(data, indent=2))


def _save_per_screen() -> None:
    """Persist _per_screen NOW, on the calling thread. Call inside _STATE_LOCK.

    Reserved for the rare paths that want the write to land before returning
    (startup migration, a full-fleet content push). Fleet-frequency / toggle
    callers must use [_save_per_screen_soon] so the gcsfuse write happens on the
    background flusher instead of under the lock — see [_flush_state_loop]."""
    _atomic_write_json(PER_SCREEN_JSON, _per_screen)


# v0.2.2: heartbeat write coalescing — see _save_screens_soon for the why.
# v0.2.12: the actual disk write for BOTH state objects now runs on the
# background flusher [_flush_state_loop], never inline under _STATE_LOCK. gcsfuse
# caps writes at ~1/sec per object; when a save stalled while the request thread
# held _STATE_LOCK, every other thread that needed the lock — crucially every
# /api/state poll and every self-edit/toggle POST — stalled with it. That is why
# a screen's rotation change could "not stick": the POST hung behind the write
# backlog and the next poll snapped the screen back to the server's old value.
# The *_soon savers just mark state dirty; the flusher serialises it under the
# lock (fast, CPU-only) and does the I/O with the lock released.
_screens_dirty = False
_screens_flushed_at = 0.0
# 10s keeps the whole fleet an order of magnitude under the GCS per-object cap
# no matter how many screens are added, and is far below the 60s poll interval,
# so nothing that reads lastHeartbeat off disk notices the delay.
_SCREENS_FLUSH_SEC = 10.0
_per_screen_dirty = False
# How often the background flusher wakes to persist dirty state.
_STATE_FLUSH_TICK_SEC = 2.0


def _save_screens() -> None:
    """Persist _screens NOW, on the calling thread. Call inside _STATE_LOCK.

    Reserved for rare operator writes (register / rename / location / unregister)
    where a person is waiting; fleet-frequency callers must use
    [_save_screens_soon]."""
    global _screens_dirty, _screens_flushed_at
    _atomic_write_json(SCREENS_JSON, _screens)
    _screens_dirty = False
    _screens_flushed_at = time.monotonic()


def _save_screens_soon() -> None:
    """Mark _screens dirty; the background flusher persists it, throttled to at
    most once per [_SCREENS_FLUSH_SEC]. Call inside _STATE_LOCK.

    `lastHeartbeat` changes on EVERY beat from EVERY screen, and _save_screens
    rewrites the whole object. In prod that object lives on a gcsfuse mount, and
    **GCS caps mutations at ~1/sec per object**. At 28 screens the fleet sustained
    ~1.35 rewrites/sec — permanently over the cap. GCS answers 429
    rateLimitExceeded, gcsfuse retries, the write blocks. Since v0.2.12 that write
    is handed to the flusher rather than run under _STATE_LOCK, so a stalled flush
    no longer takes the request threads — or the whole CMS — down with it. The
    in-memory update already happened, so /api/screens and the online/offline
    logic stay instantly accurate; only the disk flush waits."""
    global _screens_dirty
    _screens_dirty = True


def _save_per_screen_soon() -> None:
    """Mark _per_screen dirty; the background flusher persists it within
    [_STATE_FLUSH_TICK_SEC]. Call inside _STATE_LOCK.

    Used by the self-edit / per-screen toggle endpoints (rotation, audio, product
    card, sync group, …). The in-memory change is visible to the very next
    /api/state poll, and the (gcsfuse) disk write is deferred off the lock, so the
    POST returns immediately instead of hanging behind a write backlog under load
    — which is what made a rotation change fail to stick until it was retried."""
    global _per_screen_dirty
    _per_screen_dirty = True


def _flush_state_loop() -> None:
    """Background daemon: persist dirty in-memory state to disk WITHOUT holding
    _STATE_LOCK during the (gcsfuse-backed, sometimes slow) write. Each tick it
    serialises the dirty object under the lock — fast, CPU-only, no I/O — then
    releases the lock and writes. Keeping the write off the lock is what stops a
    stalled GCS flush from taking every request thread with it (v0.2.12)."""
    global _screens_dirty, _screens_flushed_at, _per_screen_dirty
    while True:
        time.sleep(_STATE_FLUSH_TICK_SEC)
        try:
            per_screen_text = None
            screens_text = None
            with _STATE_LOCK:
                if _per_screen_dirty:
                    per_screen_text = json.dumps(_per_screen, indent=2)
                    _per_screen_dirty = False
                # _screens stays throttled: lastHeartbeat churns on every beat and
                # the object must stay under the GCS per-object write cap.
                if _screens_dirty and (time.monotonic() - _screens_flushed_at) >= _SCREENS_FLUSH_SEC:
                    screens_text = json.dumps(_screens, indent=2)
                    _screens_dirty = False
                    _screens_flushed_at = time.monotonic()
            # Disk I/O with the lock released. Thread-id-tagged temp files (see
            # _atomic_write_text) keep this from racing a rare synchronous save.
            if per_screen_text is not None:
                _atomic_write_text(PER_SCREEN_JSON, per_screen_text)
            if screens_text is not None:
                _atomic_write_text(SCREENS_JSON, screens_text)
        except Exception as e:
            print(f"[state] flush loop error: {e}", file=sys.stderr)


def _save_custom_stores() -> None:
    """Persist _custom_stores. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(CUSTOM_STORES_JSON, _custom_stores)


def _save_city_brand() -> None:
    """Persist _city_brand. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(CITY_BRAND_JSON, dict(_city_brand))


def _save_experiences() -> None:
    """Persist _experiences. Call inside _STATE_LOCK after any mutation."""
    _atomic_write_json(EXPERIENCES_JSON, _experiences)


# v0.1.96 — the self-containment check. This is the rule that makes a guided
# experience work OFFLINE: the tablet caches ONE html file, so anything the page
# pulls from the network at render time is a blank box on a shop-floor screen
# the moment wifi blips. A README can't enforce that; this does, at upload.
#
# Deliberately conservative — we reject rather than try to rewrite/inline. A
# false reject costs the uploader an inline-your-assets pass; a false accept
# costs a dead screen in front of a customer.
_EXTERNAL_REF_PATTERNS = [
    # <script src="…">, <link href="…">, <img src="…">, <iframe src="…">, …
    (re.compile(r"""<[^>]+\b(?:src|href)\s*=\s*["']\s*(?:https?:)?//""", re.I),
     "an external src/href (script, link, image, iframe...)"),
    # CSS @import url(…) and url(…) pointing off-box
    (re.compile(r"""@import\s+(?:url\()?\s*["']?\s*(?:https?:)?//""", re.I),
     "an external CSS @import"),
    (re.compile(r"""url\(\s*["']?\s*(?:https?:)?//""", re.I),
     "an external url() (font or image)"),
    # fetch()/XHR to an absolute URL — the page would hang offline
    (re.compile(r"""\b(?:fetch|XMLHttpRequest|importScripts)\b[^;\n]{0,80}["']\s*(?:https?:)?//""", re.I),
     "a network call (fetch/XHR) to an external URL"),
]

# A guided experience is text; 5 MB is already enormous for inline HTML+CSS+SVG
# and keeps a bad upload from filling the state bucket.
_EXPERIENCE_MAX_BYTES = 5 * 1024 * 1024


def _validate_experience_html(raw: bytes) -> tuple[str | None, str | None]:
    """Return (text, error). `error` non-None means reject the upload."""
    if not raw:
        return None, "File is empty."
    if len(raw) > _EXPERIENCE_MAX_BYTES:
        return None, f"File is too large ({len(raw)} bytes; cap {_EXPERIENCE_MAX_BYTES})."
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None, "File must be UTF-8 HTML."
    low = text.lower()
    if "<html" not in low and "<!doctype html" not in low and "<body" not in low:
        return None, "That doesn't look like an HTML page."
    for pattern, what in _EXTERNAL_REF_PATTERNS:
        m = pattern.search(text)
        if m:
            snippet = text[m.start():m.start() + 70].replace("\n", " ").strip()
            return None, (
                f"Rejected: the page references something off-device — {what}. "
                f"Found near: “{snippet}…”. A guided experience must be fully "
                "self-contained (everything inline) so it keeps working when the "
                "screen has no network."
            )
    return text, None


# v0.2.6 — the oldest WebView on the fleet.
#
# MEASURED, not assumed. A probe page run on a real legacy signage box reported
# **"Chrome 83 (WebView)"** — Android 6/7-era hardware whose WebView stopped
# updating in 2020 and never will again (Google dropped WebView support for
# those OS versions). Before measuring, we'd assumed ~106; the gap between the
# assumption and the truth is exactly where the bugs lived.
#
# CSS newer than this doesn't error on those boxes. The declaration is simply
# **dropped**, silently, and the layout quietly breaks — no console, no crash,
# nothing in any log. That is how a WHOOP deck that looked perfect in every
# browser rendered small and clipped on a shop floor: `#fit{inset:0}` (Chrome
# 87) was ignored, so the element whose whole job was to fill the screen
# shrink-wrapped to its content instead.
LEGACY_WEBVIEW_CHROME = 83

# (regex, min Chrome, human name, what to use instead)
_LEGACY_CSS_CHECKS = [
    (re.compile(r"color-mix\s*\(", re.I), 111, "color-mix()",
     "use rgba(var(--your-colour-rgb), .14) — define the colour as an "
     "'R,G,B' triplet variable and rgba() it"),
    (re.compile(r"(?<![\w-])inset\s*:", re.I), 87, "the `inset` shorthand",
     "write top/right/bottom/left in full"),
    (re.compile(r"(?<![\w-])aspect-ratio\s*:", re.I), 88, "aspect-ratio",
     "use the padding-top percentage trick, or fixed sizes"),
    (re.compile(r":has\s*\(", re.I), 105, "the :has() selector",
     "restructure with a class toggled in JS"),
    (re.compile(r"(?<![\w-])@container(?![\w-])", re.I), 105, "container queries",
     "use a media query or size in JS"),
    (re.compile(r"(?<![\w-])@layer(?![\w-])", re.I), 99, "@layer",
     "order your rules by specificity instead"),
    (re.compile(r"(?<![\w-])accent-color\s*:", re.I), 93, "accent-color",
     "style the control directly"),
    (re.compile(r"text-wrap\s*:\s*(balance|pretty)", re.I), 114, "text-wrap: balance/pretty",
     "hard-wrap the copy, or accept the default wrapping"),
    (re.compile(r":is\s*\(", re.I), 88, "the :is() selector",
     "write the selectors out in full"),
    (re.compile(r"\.\s*replaceAll\s*\(", re.I), 85, "String.replaceAll()",
     "use .replace(/x/g, …)"),
]


def _strip_comments(text: str) -> str:
    """Blank out /* … */ and <!-- … --> so a checker can't match prose.

    Not cosmetic. The first run of the legacy check reported "color-mix() used
    2x" against a file with zero color-mix RULES — it was matching the comment
    that explained the color-mix fix. A checker that cries wolf gets ignored,
    and an ignored checker is worse than none: it spends the operator's trust
    and still lets the real thing through.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    return text


def _legacy_css_warnings(text: str) -> list[str]:
    """Things this page uses that the oldest screens can't render.

    Warnings, NOT rejections: a fleet of modern screens can use modern CSS
    quite happily, and it isn't this function's place to decide that a brand's
    experience is wrong. But a silent break on a customer-facing screen is the
    worst way to find out, so the upload says it plainly and up front.
    """
    text = _strip_comments(text)
    out: list[str] = []
    for pattern, need, name, hint in _LEGACY_CSS_CHECKS:
        hits = pattern.findall(text)
        if not hits:
            continue
        out.append(
            f"{name} is used {len(hits)}x but needs Chrome {need}. The oldest "
            f"screens run Chrome {LEGACY_WEBVIEW_CHROME}, which ignores it — "
            f"the rule is dropped and the layout breaks with no error. "
            f"Instead: {hint}."
        )

    # `gap` needs splitting by container: grid gap is Chrome 57 (fine), but
    # FLEX gap is Chrome 84 and silently collapses the spacing on a legacy box.
    # Only the flex ones are worth warning about, so parse the rule rather than
    # grepping for "gap:" and crying wolf on every grid.
    flex_gap = 0
    for block in re.findall(r"<style[^>]*>(.*?)</style>", text, re.S | re.I):
        for _sel, body in re.findall(r"([^{}]+)\{([^{}]*)\}", block):
            if re.search(r"(?<![\w-])gap\s*:", body) and \
               re.search(r"display\s*:\s*(inline-)?flex", body):
                flex_gap += 1
    if flex_gap:
        out.append(
            f"`gap` inside a flex container is used {flex_gap}x but needs "
            f"Chrome 84. The oldest screens run Chrome {LEGACY_WEBVIEW_CHROME}, "
            "where it's ignored and the spacing collapses. Instead: use margins "
            "(`.parent > * + * {margin-left: …}`). Note grid gap is fine — "
            "that's supported back to Chrome 57."
        )
    return out


def _experience_public_url(filename: str) -> str:
    public = (auth.PUBLIC_URL or "").rstrip("/")
    path = f"/interactive/{filename}"
    return f"{public}{path}" if public else path


def _list_experiences() -> list[dict]:
    """Every guided experience the CMS/tablet can choose from: the vendored
    interactive/*.html that ship with the code, plus anything uploaded.

    `builtin` marks the vendored ones — they can't be deleted from the CMS
    because they're part of the repo, not the uploads bucket.
    """
    out: list[dict] = []
    try:
        for f in sorted(INTERACTIVE_DIR.glob("*.html")):
            out.append({
                "id": f.stem,
                "name": f.stem.replace("-", " ").replace("_", " ").title(),
                "brand": None,
                "filename": f.name,
                "url": _experience_public_url(f.name),
                "sizeBytes": f.stat().st_size,
                "builtin": True,
                "uploadedAt": None,
                "uploadedBy": None,
            })
    except OSError:
        pass
    with _STATE_LOCK:
        uploaded = list(_experiences)
    for e in uploaded:
        fn = e.get("filename") or ""
        path = EXPERIENCE_UPLOADS_DIR / fn
        if not path.is_file():
            continue    # index/file drifted — don't advertise a 404
        out.append({
            "id": e.get("id"),
            "name": e.get("name") or fn,
            "brand": e.get("brand"),
            "filename": fn,
            "url": _experience_public_url(fn),
            "sizeBytes": e.get("sizeBytes") or path.stat().st_size,
            "builtin": False,
            "uploadedAt": e.get("uploadedAt"),
            "uploadedBy": e.get("uploadedBy"),
        })
    return out


def _save_secrets() -> None:
    """Persist _secrets. Call inside _STATE_LOCK after any mutation.

    v0.1.58: contains integration credentials (Brand Asset Manager API
    key etc.). Lives on the same FUSE-mounted state bucket as the other
    JSON files — a Cloud Run redeploy must not wipe credentials."""
    _atomic_write_json(SECRETS_JSON, dict(_secrets))


# v0.1.63: brand logo map from the tm:rw index /brands feed. Cached
# in-memory with a TTL and refreshed lazily on /api/library reads, so
# we don't add a tm:rw round-trip to every poll. Keyed by lowercased
# brand name. tm:rw carries casing-duplicate brand rows (e.g. "Anker"
# with no logo + "ANKER" with one); we keep the row that actually has
# a logoUrl, breaking ties on higher liveCount.
_BRAND_LOGO_CACHE: dict = {"fetchedAt": 0.0, "map": {}, "hash": "0"}
_BRAND_LOGO_TTL = 6 * 60 * 60   # 6 hours


def _brand_logo_map() -> dict:
    """Return {brandNameLower: logoUrl}. Best-effort: a missing key,
    network error, or unexpected shape leaves the previous map in place
    (or an empty one) so the library still serves — brands just render
    their generated letter mark instead of a logo."""
    now = time.time()
    cached = _BRAND_LOGO_CACHE["map"]
    if cached and (now - _BRAND_LOGO_CACHE["fetchedAt"]) < _BRAND_LOGO_TTL:
        return cached
    entry = _secrets.get("brandApiKey") or {}
    key = (entry.get("value") or "").strip()
    if not key:
        _BRAND_LOGO_CACHE["fetchedAt"] = now   # don't retry every poll
        return cached
    req = urllib.request.Request(
        f"{BRAND_API_BASE}/brands",
        headers={"X-API-Key": key, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            rows = json.loads(resp.read().decode("utf-8") or "[]")
    except Exception as e:
        print(f"[brandlogos] /brands fetch failed: {e}", file=sys.stderr)
        _BRAND_LOGO_CACHE["fetchedAt"] = now
        return cached
    best: dict[str, dict] = {}
    for r in rows if isinstance(rows, list) else []:
        name = (r.get("name") or "").strip()
        if not name:
            continue
        logo = r.get("logoUrl") or None
        live = r.get("liveCount") or 0
        k = name.lower()
        cur = best.get(k)
        if cur is None:
            best[k] = {"logoUrl": logo, "live": live}
            continue
        cur_has, cand_has = bool(cur["logoUrl"]), bool(logo)
        if (cand_has and not cur_has) or (cand_has == cur_has and live > cur["live"]):
            best[k] = {"logoUrl": logo, "live": live}
    new_map = {k: v["logoUrl"] for k, v in best.items() if v["logoUrl"]}
    import hashlib
    new_hash = hashlib.sha1(
        json.dumps(new_map, sort_keys=True).encode("utf-8")
    ).hexdigest()[:8]
    _BRAND_LOGO_CACHE.update({"map": new_map, "fetchedAt": now, "hash": new_hash})
    print(f"[brandlogos] {len(new_map)} brand logo(s) from tm:rw", file=sys.stderr)
    return new_map


# v0.1.64: assigned-video map from the tm:rw index /videos feed. Lets
# the Content Library split a brand's Drive videos into "active" (the
# asset manager has registered them as an assigned/marketing video) and
# "orphan" (present in the Drive folder but unknown to tm:rw). Keyed by
# brandLower -> { fileNameLower: {productName, active} }. Fetched
# per-brand (GET /videos?brand=) for only the brands the library
# actually has, cached on the same 6h TTL as logos.
_TMRW_VIDEOS_CACHE: dict = {"fetchedAt": 0.0, "byBrand": {}, "hash": "0"}
# v0.1.65: dropped from 6h to 5min. The fetch is now a single GET
# /videos call (cheap), and 6h was painfully slow while the asset
# manager is actively having videos added. "Sync now" also force-
# resets this (see _reset_tmrw_caches), so changes can be pulled on
# demand without waiting out the TTL.
_TMRW_VIDEOS_TTL = 5 * 60   # 5 minutes

# v0.2.8: most products a single family/brand video's shopper card will cycle
# through. Family scope is naturally small (variants of one product); brand
# scope can be a whole catalogue, which is too many to rotate meaningfully — so
# the card takes the first N and stops.
_CARD_PRODUCTS_CAP = 8


def _reset_tmrw_caches() -> None:
    """Force the next /api/library to re-pull tm:rw brands + videos.
    Wired to the 'Sync now' action so an operator can pull a freshly
    added assigned video immediately instead of waiting out the TTL."""
    _BRAND_LOGO_CACHE["fetchedAt"] = 0.0
    _TMRW_VIDEOS_CACHE["fetchedAt"] = 0.0


def _drive_thumb_url(file_path: str | None) -> str | None:
    """v0.1.77: turn a tm:rw imagery `filePath` into a small, public,
    render-friendly thumbnail URL for the Content Library packshot.

    tm:rw stores the main image as a Drive link
    (`https://drive.google.com/uc?export=view&id=<ID>`). The
    `lh3.googleusercontent.com/d/<ID>=w512` form serves the same file as a
    ~10 KB JPEG with no auth and no virus-scan interstitial, so both an
    <img> tag (CMS) and Coil (APK) can load it directly. Returns None if we
    can't find a Drive id (and passes through a plain http(s) image URL)."""
    if not file_path:
        return None
    m = re.search(r"[?&]id=([A-Za-z0-9_-]+)", file_path) \
        or re.search(r"/d/([A-Za-z0-9_-]+)", file_path)
    if m:
        return f"https://lh3.googleusercontent.com/d/{m.group(1)}=w512"
    return file_path if file_path.startswith("http") else None


# v0.1.86: per-currency prices for the shopper-facing product card. The tm:rw
# API exposes region-specific price fields (priceGbp/rrpGbp = GBP/London,
# rrpBerlinEur = Berlin, rrpRomeEur = Rome, priceUsd = NYC, priceEur). Read
# defensively — the schema uses additionalProperties and a given row may carry
# only some. Returns {gbp,usd,eur,berlinEur,romeEur} with the values present,
# or None if there's no price at all. The player picks the field for its store's
# region.
_PRICE_FIELDS = {
    "gbp":       ("priceGbp", "rrpGbp"),
    "usd":       ("priceUsd",),
    "eur":       ("priceEur",),
    "berlinEur": ("rrpBerlinEur",),
    "romeEur":   ("rrpRomeEur",),
}


def _extract_prices(row: dict) -> dict | None:
    if not isinstance(row, dict):
        return None
    out: dict = {}
    for cur, fields in _PRICE_FIELDS.items():
        for f in fields:
            val = row.get(f)
            if val not in (None, "", 0):
                out[cur] = val
                break
    return out or None


def _tmrw_videos_map() -> dict:
    """Return {brandLower: {"display": <brand>, "videos": {fileNameLower:
    {fileName, productName, active, orientation, resolution, sku, scope}}}}.

    v0.1.65: one GET /videos call for the whole assigned-video set
    (marketing videos are few relative to products), grouped by brand —
    so a tm:rw brand with assigned videos shows up even if it has no
    Drive folder yet (e.g. "Moods"). Brand-scope rows carry the brand in
    scopeKey; family/product-scope rows fall back to an explicit `brand`
    field if present, else scopeKey.

    Best-effort: missing key / network error leaves the previous map in
    place so the library still serves."""
    now = time.time()
    cached = _TMRW_VIDEOS_CACHE["byBrand"]
    if cached and (now - _TMRW_VIDEOS_CACHE["fetchedAt"]) < _TMRW_VIDEOS_TTL:
        return cached
    entry = _secrets.get("brandApiKey") or {}
    key = (entry.get("value") or "").strip()
    if not key:
        _TMRW_VIDEOS_CACHE["fetchedAt"] = now
        return cached
    req = urllib.request.Request(
        f"{BRAND_API_BASE}/videos",
        headers={"X-API-Key": key, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            rows = json.loads(resp.read().decode("utf-8") or "[]")
    except Exception as e:
        print(f"[tmrwvideos] /videos fetch failed: {e}", file=sys.stderr)
        _TMRW_VIDEOS_CACHE["fetchedAt"] = now
        return cached
    # v0.1.70: brand attribution. Brand-scope rows carry the brand in
    # scopeKey, but product/family-scope rows carry the SKU / familyId
    # there and have NO brand field — so the old "group by scopeKey"
    # filed product-scope videos under the SKU (a TCL product video
    # vanished). Resolve those to the real brand: prefer an explicit
    # `brand` field, then /product?sku=<sku> (cached per refresh), then
    # the familyId prefix, and only fall back to scopeKey as a last
    # resort.
    # v0.1.77: per-SKU detail (brand + packshot image), fetched once per
    # refresh via a single /product?sku= call and shared by both brand
    # resolution AND the Content Library packshot thumbnails.
    _sku_detail: dict[str, dict] = {}

    def _product_detail(sku: str) -> dict:
        if sku not in _sku_detail:
            d: dict = {"brand": None, "packshot": None,
                       "descShort": None, "descLong": None, "prices": None}
            try:
                preq = urllib.request.Request(
                    f"{BRAND_API_BASE}/product?sku={urllib.parse.quote(sku)}",
                    headers={"X-API-Key": key, "Accept": "application/json"},
                )
                with urllib.request.urlopen(preq, timeout=6) as presp:
                    pdata = json.loads(presp.read().decode("utf-8") or "{}")
                prod = pdata.get("product") or {}
                d["brand"] = (prod.get("brand") or "").strip() or None
                # v0.1.86: per-currency prices may ride on the product record
                # (the caller also checks the /videos row as a fallback).
                d["prices"] = _extract_prices(prod)
                imagery = pdata.get("imagery") or []
                main = next((im for im in imagery
                             if (im.get("role") or "").lower() == "main"), None) \
                    or (imagery[0] if imagery else None)
                if main:
                    # Prefer `driveUrl` (the servable Drive link) over
                    # `filePath`: for many products (e.g. TCL Global) filePath
                    # is a local CloudStorage *sync path* and the Drive URL
                    # lives in driveUrl; for others (e.g. Foreo) filePath itself
                    # is the Drive URL and driveUrl is null. Try driveUrl first.
                    d["packshot"] = _drive_thumb_url(main.get("driveUrl") or main.get("filePath"))
            except Exception as e:
                print(f"[tmrwvideos] product lookup for sku {sku} failed: {e}", file=sys.stderr)
            # v0.1.86: short/long marketing copy for the product card, from the
            # dedicated /description endpoint. Separate best-effort call so a
            # description miss never loses the packshot/brand fetched above.
            try:
                dreq = urllib.request.Request(
                    f"{BRAND_API_BASE}/description?sku={urllib.parse.quote(sku)}",
                    headers={"X-API-Key": key, "Accept": "application/json"},
                )
                with urllib.request.urlopen(dreq, timeout=6) as dresp:
                    ddata = json.loads(dresp.read().decode("utf-8") or "{}")
                d["descShort"] = (ddata.get("short") or "").strip() or None
                d["descLong"] = (ddata.get("long") or "").strip() or None
            except Exception as e:
                print(f"[tmrwvideos] description lookup for sku {sku} failed: {e}", file=sys.stderr)
            _sku_detail[sku] = d
        return _sku_detail[sku]

    # v0.2.8: expand a family/brand-scope video into the list of products it
    # represents, so the shopper card can cycle through them. A product-scope
    # video already resolves to one product and skips all this. Everything here
    # is best-effort and capped: if it can't resolve at least two real
    # products, it returns None and the card keeps its single-product
    # behaviour, so there's no regression where tm:rw data is thin or a brand
    # name doesn't match cleanly.
    _brand_products_cache: dict[str, list] = {}

    def _brand_products(brand: str) -> list:
        bl = (brand or "").strip().lower()
        if not bl:
            return []
        if bl not in _brand_products_cache:
            got: list = []
            try:
                preq = urllib.request.Request(
                    f"{BRAND_API_BASE}/products?brand={urllib.parse.quote(brand)}",
                    headers={"X-API-Key": key, "Accept": "application/json"},
                )
                with urllib.request.urlopen(preq, timeout=8) as presp:
                    got = json.loads(presp.read().decode("utf-8") or "[]")
            except Exception as e:
                print(f"[tmrwvideos] products for brand {brand!r} failed: {e}", file=sys.stderr)
                got = []
            _brand_products_cache[bl] = got if isinstance(got, list) else []
        return _brand_products_cache[bl]

    def _card_from_product(p: dict) -> dict | None:
        """One tm:rw product row → the shopper-card shape, or None if it's too
        empty to be worth a slide."""
        name = (p.get("name") or p.get("productName") or "").strip() or None
        if not name:
            return None
        sku = (p.get("ivendSku") or p.get("sku") or "").strip() or None
        detail = _product_detail(sku) if sku else {
            "packshot": None, "descShort": None, "descLong": None, "prices": None}
        prices = _extract_prices(p) or detail.get("prices")
        card = {
            "product": name,
            "prices": prices,
            "description": detail.get("descShort"),
            "descriptionLong": detail.get("descLong"),
            "packshotUrl": detail.get("packshot"),
        }
        # A name alone is a blank card. Only cycle to products that also carry a
        # price, a description, or an image — something a shopper can read.
        if not (prices or card["description"] or card["descriptionLong"] or card["packshotUrl"]):
            return None
        return {k: v for k, v in card.items() if v is not None}

    def _expand_card_products(r: dict, brand: str | None, scope: str) -> list | None:
        if scope not in ("family", "brand") or not brand:
            return None
        prods = _brand_products(brand)
        if not prods:
            return None
        if scope == "family":
            fam = (r.get("familyId") or r.get("scopeKey") or "").strip().lower()
            members = [p for p in prods
                       if (p.get("familyId") or "").strip().lower() == fam] if fam else []
        else:  # brand scope — the whole brand, capped below
            members = list(prods)
        cards: list = []
        seen: set = set()
        for p in members:
            key_id = (p.get("ivendSku") or p.get("sku") or p.get("name") or "").strip().lower()
            if not key_id or key_id in seen:
                continue
            seen.add(key_id)
            c = _card_from_product(p)
            if c:
                cards.append(c)
            if len(cards) >= _CARD_PRODUCTS_CAP:
                break
        # Only a cycling widget if there are ≥2; one falls through to the
        # existing single-product path unchanged.
        return cards if len(cards) >= 2 else None

    def _resolve_brand(r: dict) -> str | None:
        if r.get("brand"):
            return r["brand"].strip() or None
        scope = (r.get("scope") or "").strip().lower()
        scope_key = (r.get("scopeKey") or "").strip()
        if scope == "brand":
            return scope_key or None
        # product / family scope — find the real brand.
        sku = (r.get("sku") or (scope_key if scope == "product" else "")).strip()
        if sku and _product_detail(sku)["brand"]:
            return _product_detail(sku)["brand"]
        fam = (r.get("familyId") or "").strip()
        if fam and "|" in fam:
            # familyId is "<brandslug>|pgid:..." — slug, not display,
            # but matches a library brand case-insensitively.
            return fam.split("|", 1)[0] or None
        return scope_key or None

    by_brand: dict[str, dict] = {}
    for r in rows if isinstance(rows, list) else []:
        fn = (r.get("fileName") or "").strip()
        if not fn:
            continue
        brand = (_resolve_brand(r) or "").strip()
        if not brand:
            continue
        _row_scope = (r.get("scope") or "").strip().lower()
        # v0.1.71: SKU surfaced as a Content Library list column.
        # Product-scope rows carry the SKU in scopeKey when there's no
        # explicit `sku` field; brand/family rows usually have none.
        sku_val = (r.get("sku") or (r.get("scopeKey") if _row_scope == "product" else "") or "").strip() or None
        bucket = by_brand.setdefault(brand.lower(), {"display": brand, "videos": {}})
        bucket["videos"][fn.lower()] = {
            "fileName": fn,
            "productName": (r.get("productName") or "").strip() or None,
            "active": bool(r.get("active")),
            "orientation": (r.get("orientation") or "").strip() or None,
            "resolution": (r.get("resolution") or "").strip() or None,
            "sku": sku_val,
            # v0.1.69: scope drives the Content Library sectioning —
            # "brand" → Brand Global Videos; "family"/"product" → grouped
            # under their product. Normalised to lowercase.
            "scope": _row_scope or None,
            # v0.1.77: product packshot (tm:rw main image) for the Content
            # Library thumbnail. Only product-scope rows resolve to a SKU →
            # product → main image; brand/family rows stay null and fall
            # back to the generated thumbnail / brand logo.
            "packshotUrl": (_product_detail(sku_val)["packshot"] if sku_val else None),
            # v0.1.86: product-card fields — short/long description (tm:rw
            # /description) + per-currency prices (row first, product fallback).
            "description": (_product_detail(sku_val)["descShort"] if sku_val else None),
            "descriptionLong": (_product_detail(sku_val)["descLong"] if sku_val else None),
            "prices": (_extract_prices(r) or (_product_detail(sku_val)["prices"] if sku_val else None)),
            # v0.2.8: for a family/brand video, the products it represents, so
            # the shopper card can cycle through them. None for a single-product
            # video (or when fewer than two resolve) — the card then keeps its
            # existing single-product behaviour.
            "products": _expand_card_products(r, brand, _row_scope),
        }
    import hashlib
    # v0.1.77: fold the packshot URL into the hash (not just the file set)
    # so a newly-resolved product image busts the /api/library ETag and
    # reaches clients instead of serving a stale, packshot-less 304.
    new_hash = hashlib.sha1(
        json.dumps({k: [(fk, vv.get("packshotUrl"), vv.get("description"), vv.get("prices"),
                         vv.get("products"))
                    for fk, vv in sorted(v["videos"].items())]
                    for k, v in by_brand.items()}, sort_keys=True).encode("utf-8")
    ).hexdigest()[:8]
    _TMRW_VIDEOS_CACHE.update({"byBrand": by_brand, "fetchedAt": now, "hash": new_hash})
    total = sum(len(v["videos"]) for v in by_brand.values())
    print(f"[tmrwvideos] {total} assigned video(s) across {len(by_brand)} brand(s) from tm:rw", file=sys.stderr)
    return by_brand


def _library_with_logos() -> tuple[dict, str]:
    """Library payload enriched with tm:rw data. Returns (data, etag).
    Never mutates the shared library cache — builds a fresh dict.

    Per brand: `logoUrl` (case-insensitive name match).
    Per video: `tmrwAssigned` (bool — tm:rw knows this file as an
    assigned video, matched by basename), `tmrwActive` (bool — assigned
    AND its product/brand is live), and `productLine` (the tm:rw product
    name for grouping). Unmatched videos get tmrwAssigned=false so the
    CMS can bucket them as orphans.

    ETag folds in the logo + videos map hashes so a tm:rw refresh
    invalidates client caches without waiting for a Drive re-sync."""
    data = _load_library()
    logos = _brand_logo_map()
    vids = _tmrw_videos_map()
    out = dict(data)

    brands = data.get("brands")
    if logos and isinstance(brands, list):
        out["brands"] = [
            ({**b, "logoUrl": logos.get((b.get("name") or "").lower())}
             if logos.get((b.get("name") or "").lower()) else b)
            for b in brands
        ]

    # Tag scanned videos + track which assigned videos we matched, so
    # the rest can be surfaced as "active, pending sync" entries below.
    videos = data.get("videos")
    matched: dict[str, set] = {}   # brandLower -> {fileNameLower matched}
    if isinstance(videos, list):
        new_videos = []
        for v in videos:
            bkey = (v.get("brand") or "").lower()
            fnkey = (v.get("filename") or "").lower()
            bucket = vids.get(bkey)
            row = bucket["videos"].get(fnkey) if bucket else None
            if row:
                matched.setdefault(bkey, set()).add(fnkey)
                nv = {**v, "tmrwAssigned": True, "tmrwActive": row["active"],
                      "tmrwScope": row.get("scope")}
                if row.get("productName"):
                    nv["productLine"] = row["productName"]
                # v0.1.71: SKU + tm:rw orientation/resolution feed the
                # Content Library list columns. The scan already has
                # width/height for matched files; tm:rw fills the gaps
                # (and is the only source for the SKU).
                if row.get("sku"):
                    nv["sku"] = row["sku"]
                if row.get("orientation"):
                    nv["tmrwOrientation"] = row["orientation"]
                if row.get("resolution"):
                    nv["tmrwResolution"] = row["resolution"]
                # v0.1.77: product packshot thumbnail for the Content Library.
                if row.get("packshotUrl"):
                    nv["packshotUrl"] = row["packshotUrl"]
                # v0.1.86: product-card fields (shopper-facing card).
                if row.get("description"):
                    nv["description"] = row["description"]
                if row.get("descriptionLong"):
                    nv["descriptionLong"] = row["descriptionLong"]
                if row.get("prices"):
                    nv["prices"] = row["prices"]
                if row.get("products"):
                    nv["products"] = row["products"]      # v0.2.8: cycle list
                new_videos.append(nv)
            else:
                new_videos.append({**v, "tmrwAssigned": False, "tmrwActive": False})

        # v0.1.65: surface assigned videos tm:rw knows about but that
        # aren't in the Drive scan yet (e.g. a brand-wide video for a
        # brand with no Brand Content folder). They show in the library
        # as active, flagged `pendingSync` — the CMS renders them but
        # blocks push since there's no streamable mediaUrl until the
        # file lands in the scanned folder. mediaUrl=None on purpose.
        for bkey, bucket in vids.items():
            done = matched.get(bkey, set())
            for fnkey, row in bucket["videos"].items():
                if fnkey in done:
                    continue
                new_videos.append({
                    "id": f"tmrw-{_slug(bkey)}-{_slug(row['fileName'])}",
                    "title": row.get("productName") or row["fileName"],
                    "brand": bucket["display"],
                    "product": row.get("productName"),
                    "productLine": row.get("productName"),
                    "filename": row["fileName"],
                    "mediaUrl": None,
                    "sizeMb": None,
                    "durationSec": None,
                    "duration": "—",
                    "width": None,
                    "height": None,
                    "screens": 0,
                    "tmrwAssigned": True,
                    "tmrwActive": row["active"],
                    "tmrwScope": row.get("scope"),
                    # v0.1.71: pending videos aren't in the Drive scan, so
                    # tm:rw is the only source for these list columns.
                    "sku": row.get("sku"),
                    "tmrwOrientation": row.get("orientation"),
                    "tmrwResolution": row.get("resolution"),
                    "packshotUrl": row.get("packshotUrl"),  # v0.1.77
                    "description": row.get("description"),        # v0.1.86
                    "descriptionLong": row.get("descriptionLong"),
                    "prices": row.get("prices"),
                    "products": row.get("products"),             # v0.2.8
                    "pendingSync": True,
                    "source": "tmrw",
                })
        out["videos"] = new_videos

    # Ensure brands that only exist in tm:rw (have assigned videos but no
    # Drive folder) appear in the brand rail, and bump video counts to
    # include surfaced assigned videos.
    brands_out = out.get("brands")
    if isinstance(brands_out, list) and vids:
        by_name = {(b.get("name") or "").lower(): b for b in brands_out}
        for bkey, bucket in vids.items():
            if bkey in by_name:
                continue
            display = bucket["display"]
            brands_out.append({
                "id": _slug(bkey),
                "name": display,
                "videos": len(bucket["videos"]),
                "products": [],
                "logoUrl": logos.get(bkey),
            })
        out["brands"] = brands_out

    etag = (
        f'"lib-{int(_LIBRARY_CACHE.get("mtime") or 0)}'
        f'-{_BRAND_LOGO_CACHE["hash"]}-{_TMRW_VIDEOS_CACHE["hash"]}"'
    )
    return out, etag


# v0.1.86: card data for the shopper-facing product card, keyed by the playing
# item's library id — the tm:rw-enriched description + per-currency prices +
# packshot, plus the brand logo (for the image fallback: packshot → brand logo
# → nothing). Cached by the library ETag so /api/state polls don't rebuild the
# index each time; only screens with the productCard toggle on ever call this.
_ENRICHED_CARD_CACHE: dict = {"etag": None, "byId": {}}


def _product_card_for(video_id: str) -> dict | None:
    if not video_id:
        return None
    data, etag = _library_with_logos()
    if _ENRICHED_CARD_CACHE["etag"] != etag:
        logos = _brand_logo_map()
        by_id: dict[str, dict] = {}
        for v in data.get("videos") or []:
            vid = v.get("id")
            if not vid:
                continue
            card: dict = {}
            if v.get("description"):
                card["description"] = v["description"]
            if v.get("descriptionLong"):
                card["descriptionLong"] = v["descriptionLong"]
            if v.get("prices"):
                card["prices"] = v["prices"]
            if v.get("packshotUrl"):
                card["packshotUrl"] = v["packshotUrl"]
            if v.get("products"):
                card["products"] = v["products"]      # v0.2.8: multi-product cycle
            blogo = logos.get((v.get("brand") or "").lower())
            if blogo:
                card["brandLogoUrl"] = blogo
            if card:
                by_id[vid] = card
        _ENRICHED_CARD_CACHE.update({"etag": etag, "byId": by_id})
    return _ENRICHED_CARD_CACHE["byId"].get(video_id)


def _test_brand_api_key() -> dict:
    """v0.1.59: probe the tm:rw index API with the stored Brand Asset
    Manager API key. Returns a structured result the CMS renders inline
    next to the key field:

      ok=true                  status=ok           — /counts returned 200
      ok=false  status=no_key                     — no key set yet
      ok=false  status=unauthorized               — key rejected (401/403)
      ok=false  status=unreachable                — couldn't reach server
      ok=false  status=server_error               — 5xx from upstream
      ok=false  status=unexpected                 — other non-2xx

    v0.1.61: switched ping from /me (doesn't exist on the tm:rw index
    API — returns 404 for valid keys) to /counts. It's lightweight,
    auth-required, and returns the brand / product / asset roll-up
    which we echo back so the operator sees they're talking to the
    right catalogue. /health stays unused: it's publicly reachable
    so a 200 there only proves the network, not the key."""
    entry = _secrets.get("brandApiKey") or {}
    key = (entry.get("value") or "").strip()
    if not key:
        return {"ok": False, "status": "no_key", "detail": "No key saved yet"}

    started = time.time()
    url = f"{BRAND_API_BASE}/counts"
    req = urllib.request.Request(url, headers={"X-API-Key": key, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            elapsed_ms = int((time.time() - started) * 1000)
            try:
                payload = json.loads(resp.read().decode("utf-8") or "{}")
            except Exception:
                payload = {}
            # Echo top-level scalar fields so the operator sees the
            # catalogue shape (brand count, product count, etc.).
            # Cap at 6 entries so a chatty endpoint can't bloat the
            # response card.
            identity = {}
            if isinstance(payload, dict):
                for k, v in payload.items():
                    if len(identity) >= 6:
                        break
                    if isinstance(v, (str, int, float)) and not isinstance(v, bool):
                        identity[k] = v
            return {
                "ok": True, "status": "ok", "latencyMs": elapsed_ms,
                "detail": "API key accepted by tm:rw index",
                "identity": identity,
            }
    except urllib.error.HTTPError as e:
        elapsed_ms = int((time.time() - started) * 1000)
        body_text = ""
        upstream_msg = None
        try:
            body_text = e.read().decode("utf-8", errors="replace")
            upstream_msg = (json.loads(body_text) or {}).get("error")
        except Exception:
            pass
        if e.code in (401, 403):
            return {
                "ok": False, "status": "unauthorized", "latencyMs": elapsed_ms,
                "httpStatus": e.code,
                "detail": upstream_msg or "API key rejected by tm:rw index",
            }
        if 500 <= e.code < 600:
            return {
                "ok": False, "status": "server_error", "latencyMs": elapsed_ms,
                "httpStatus": e.code,
                "detail": upstream_msg or f"Upstream {e.code} — tm:rw index reported an error",
            }
        return {
            "ok": False, "status": "unexpected", "latencyMs": elapsed_ms,
            "httpStatus": e.code,
            "detail": upstream_msg or f"Unexpected HTTP {e.code}",
        }
    except urllib.error.URLError as e:
        return {
            "ok": False, "status": "unreachable",
            "detail": f"Couldn't reach {BRAND_API_BASE}: {e.reason}",
        }
    except Exception as e:
        return {
            "ok": False, "status": "unreachable",
            "detail": f"Network error: {e!s}",
        }


def _load_state_from_disk() -> None:
    """One-shot loader, called once on module import. Best-effort —
    a missing or corrupt file just means we start with empty state,
    same as fresh boot before persistence existed."""
    global _per_screen, _screens, _sync_groups, _custom_stores
    with _STATE_LOCK:
        for path, target_name in [
            (PER_SCREEN_JSON, "_per_screen"),
            (SCREENS_JSON, "_screens"),
            (SYNC_GROUPS_JSON, "_sync_groups"),
            (CUSTOM_STORES_JSON, "_custom_stores"),
            (CITY_BRAND_JSON, "_city_brand"),
            (SECRETS_JSON, "_secrets"),
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
                elif target_name == "_custom_stores":
                    _custom_stores = raw
                elif target_name == "_city_brand":
                    # v0.1.56: don't clobber the _city_brand global —
                    # other code holds references to it. Merge in place.
                    _city_brand.clear()
                    _city_brand.update(raw)
                elif target_name == "_secrets":
                    _secrets.clear()
                    _secrets.update(raw)
                else:
                    _sync_groups = raw
                print(f"[state] loaded {len(raw)} entries from {path}", file=sys.stderr)
            except Exception as e:
                print(f"[state] load {path} failed: {e}", file=sys.stderr)

        # v0.1.96: the experiences index is a LIST, so it can't ride the
        # dict-only loop above.
        global _experiences
        if EXPERIENCES_JSON.is_file():
            try:
                raw = json.loads(EXPERIENCES_JSON.read_text(encoding="utf-8"))
                if isinstance(raw, list):
                    _experiences = raw
                    print(f"[state] loaded {len(raw)} experiences from {EXPERIENCES_JSON}", file=sys.stderr)
                else:
                    print(f"[state] {EXPERIENCES_JSON}: top-level isn't a list, skipping", file=sys.stderr)
            except Exception as e:
                print(f"[state] load {EXPERIENCES_JSON} failed: {e}", file=sys.stderr)


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

# v0.1.60: presence rules scale with how often a screen is expected
# to call in. The old code used a fixed 15-second cut-off, which
# marked SLOW-mode tablets (5-minute interval) offline the moment
# they hung up the last poll — they hadn't gone anywhere, they
# just weren't due back for another 4 minutes. Now we read the
# screen's pollMode and derive both thresholds from it:
#
#   live   — currently in active contact (just polled, next one is
#            imminent). Within ~1.25 × poll interval, with a 5 s
#            grace floor for network jitter on FAST mode.
#   online — recently in contact, expected to check back in soon.
#            Within ~2.5 × poll interval. A screen that misses one
#            poll cycle still counts as online; missing two flips
#            it to offline.
POLL_INTERVAL_SEC: dict[str, int] = {
    "fast":   10,
    "normal": 60,
    "slow":   300,
}
LIVE_MULTIPLIER   = 1.25   # one poll cycle + 25 % jitter
ONLINE_MULTIPLIER = 2.5    # two cycles + half — survives one missed beat


def _poll_interval_sec(poll_mode: str | None) -> int:
    return POLL_INTERVAL_SEC.get(poll_mode or DEFAULT_POLL_MODE,
                                 POLL_INTERVAL_SEC[DEFAULT_POLL_MODE])


def _live_threshold_sec(poll_mode: str | None) -> float:
    """Hb-age cutoff under which a screen counts as actively connected
    right now. Adds a 5 s floor so FAST-mode jitter (10 s ✕ 1.25 = 12.5)
    leaves room for the actual round-trip."""
    return max(15.0, _poll_interval_sec(poll_mode) * LIVE_MULTIPLIER)


def _online_threshold_sec(poll_mode: str | None) -> float:
    """Hb-age cutoff under which a screen is still considered online.
    Tolerates one fully-missed poll cycle so a single dropped request
    doesn't briefly mark the fleet red on the dashboard."""
    return max(30.0, _poll_interval_sec(poll_mode) * ONLINE_MULTIPLIER)


def _presence(last_heartbeat: float | None, poll_mode: str | None,
              now: float | None = None) -> tuple[bool, bool]:
    """Return (live, online) for one screen given its last heartbeat
    timestamp and its pollMode. Both False when last_heartbeat is
    falsy — a screen with no heartbeat at all is offline by definition,
    regardless of pollMode."""
    if not last_heartbeat:
        return (False, False)
    age = (now if now is not None else time.time()) - last_heartbeat
    return (age < _live_threshold_sec(poll_mode),
            age < _online_threshold_sec(poll_mode))


# ── Platform metrics (Claude Projects Dashboard) ─────────────────────
# Powers GET /api/metrics: read-only, aggregate, no-PII usage counts for the
# founders' dashboard. Cached so a few-minute poll never recomputes hot.
_METRICS_CACHE: dict = {"data": None, "at": 0.0}
_METRICS_TTL_SEC = 60.0


def _metrics_payload() -> dict:
    """Build the /api/metrics response. All numbers come from in-memory live
    state (no external calls, sub-ms), so this is fast; the cache just spares
    us recomputing on every dashboard poll. Wrapped fail-soft: if the compute
    throws we still return a valid 200 with whatever metrics we have."""
    now = time.time()
    cached = _METRICS_CACHE.get("data")
    if cached is not None and (now - _METRICS_CACHE.get("at", 0.0)) < _METRICS_TTL_SEC:
        return cached

    metrics: list[dict] = []

    def add(key: str, label: str, value, period: str, unit: str | None = None) -> None:
        m = {"key": key, "label": label, "value": value, "period": period}
        if unit is not None:
            m["unit"] = unit
        metrics.append(m)

    try:
        # Everything reads _screens / _per_screen — snapshot under the lock,
        # but the work is trivial so we just compute inside it (no I/O here).
        with _STATE_LOCK:
            total = len(_screens)
            online = 0
            stores: set[str] = set()
            for dev, meta in _screens.items():
                st = _per_screen.get(dev, {})
                last = meta.get("lastHeartbeat") or 0
                pm = st.get("pollMode") or DEFAULT_POLL_MODE
                _, online_flag = _presence(last, pm, now)
                if online_flag:
                    online += 1
                sid = ((meta.get("location") or {}).get("storeId") or "").strip()
                if sid:
                    stores.add(sid)
            cutoff = now - 7 * 86400
            updated_7d = sum(
                1 for st in _per_screen.values()
                if isinstance(st.get("pushedAt"), (int, float)) and st["pushedAt"] >= cutoff
            )
        add("screens_online", "Screens online", online, "now", unit="screens")
        add("screens_total", "Screens deployed", total, "now", unit="screens")
        add("stores_live", "Stores running the platform", len(stores), "now", unit="stores")
        add("screens_updated_7d", "Screens given new content", updated_7d, "7d", unit="screens")
    except Exception as e:
        print(f"[metrics] compute failed: {e}", file=sys.stderr)

    payload = {
        "platform": "screens-app",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
        "metrics": metrics[:8],
    }
    _METRICS_CACHE["data"] = payload
    _METRICS_CACHE["at"] = now
    return payload


# ── Offline-screen alerting ──────────────────────────────────────────
def _alert_label(s: dict) -> str:
    name = s.get("name") or s.get("deviceId") or "?"
    store = (s.get("location") or {}).get("storeId")
    return f"{name} ({store})" if store else name


def _send_alert(text: str) -> None:
    """Post an alert to the configured webhook. Slack/Discord/Mattermost
    incoming webhooks all accept {"text": ...}. No-op when the webhook isn't
    configured (the event is still in the activity log). Never raises."""
    if not ALERT_WEBHOOK:
        return
    try:
        data = json.dumps({"text": text}).encode("utf-8")
        req = urllib.request.Request(
            ALERT_WEBHOOK, data=data,
            headers={"Content-Type": "application/json", "User-Agent": "screens-app-v2"},
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            resp.read()
    except Exception as e:
        print(f"[alerts] webhook post failed: {e}", file=sys.stderr)


def _offline_monitor_loop() -> None:
    """Watch for screens going offline / recovering and alert once per
    transition. A screen is alerted as down only after (a) it was seen online
    at least once this run — so stale rows don't alert on boot — and (b) it's
    been offline past its normal online threshold + ALERT_OFFLINE_AFTER_SEC,
    which debounces flapping."""
    print(f"[alerts] offline monitor started (webhook {'on' if ALERT_WEBHOOK else 'off'})",
          file=sys.stderr)
    while True:
        time.sleep(60)
        try:
            now = time.time()
            downs: list[tuple[str, str]] = []
            ups: list[tuple[str, str]] = []
            with _STATE_LOCK:
                for device_id, s in _screens.items():
                    last = s.get("lastHeartbeat") or 0
                    pm = (_per_screen.get(device_id) or {}).get("pollMode", DEFAULT_POLL_MODE)
                    _, online = _presence(last, pm, now)
                    if online:
                        _ever_online.add(device_id)
                        if device_id in _alerted_offline:
                            _alerted_offline.discard(device_id)
                            ups.append((_alert_label(s), device_id))
                        continue
                    if device_id not in _ever_online:
                        continue  # never connected this run — don't alert
                    down_for = (now - last) if last else None
                    threshold = _online_threshold_sec(pm) + ALERT_OFFLINE_AFTER_SEC
                    if (down_for is not None and down_for >= threshold
                            and device_id not in _alerted_offline):
                        _alerted_offline.add(device_id)
                        downs.append((f"{_alert_label(s)} · ~{int(down_for // 60)} min", device_id))
            # Log + notify outside the lock — the webhook does network I/O.
            for label, dev in downs:
                _log_activity(kind="offline", text=f"{label} went offline",
                              icon="offline", tone="err", target=dev)
                _send_alert(f"🔴 Screen {label} went offline")
            for label, dev in ups:
                _log_activity(kind="back", text=f"{label} is back online",
                              icon="check", tone="ok", target=dev)
                _send_alert(f"🟢 Screen {label} is back online")
        except Exception as e:
            print(f"[alerts] monitor iteration failed: {e}", file=sys.stderr)


def _ensure_screen_state(device_id: str) -> dict:
    """Lazily create a per-screen state record. Caller must hold _STATE_LOCK."""
    s = _per_screen.get(device_id)
    if s is None:
        s = {
            "revision": 0,
            "items": [],
            "pushedAt": None,
            "mixSplash": True,                    # bundled splash mixed in by default
            "productCard": False,                 # v0.1.86: shopper-facing product info card — opt-in per screen via /api/screens/<id>/product-card
            # v0.2.8: physical-mount display rotation in degrees (0/90/180/270).
            # For a panel mounted rotated where Android reports the wrong way up;
            # the player rotates its whole output to match. 0 = no rotation (the
            # default for every screen — nothing changes). Set via
            # /api/screens/<id>/rotation.
            "rotation": 0,
            # v0.1.92: guided brand experience. When set to an https URL the
            # screen keeps playing its normal loop to attract, but shows a
            # "tap to explore" prompt; a tap opens that URL fullscreen in a
            # locked-down kiosk WebView, and it returns to the loop after an
            # idle timeout. None = ordinary video-only screen. Set via
            # /api/screens/<id>/experience.
            "experienceUrl": None,
            # v0.1.95: where the "tap to explore" attract prompt sits —
            # "top" (default) or "bottom". Never a corner: those are the
            # staff-unlock zones. Set via /api/screens/<id>/experience.
            "experiencePromptPos": "top",
            # v0.1.98: customer-facing "next video" control on the screen.
            # Off by default — it puts a visible tappable arrow on a
            # shop-floor screen, so it's opt-in per screen like productCard
            # rather than a fleet-wide change. Forced off in a sync group
            # (see /api/state): skipping one member alone would visibly break
            # the lockstep the group exists to provide.
            "tapNext": False,
            # v0.2.0: slim playback progress bar along the bottom of the video.
            # Opt-in per screen for the same reason as tapNext — it puts visible
            # chrome on a shop-floor screen. Unlike tapNext this is NOT forced
            # off in a sync group: it's read-only decoration, and every member
            # is at the same position anyway, so the bars agree.
            "progressBar": False,
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
        _save_per_screen_soon()
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
    # v0.1.81: "tmrw" as a selectable *concept* (not just a brand), backed by
    # the same "Splash - tmrw" folder — so a screen whose concept is "tmrw"
    # resolves to the tm:rw splash directly (concept beats city). Sharing the
    # folder means an uploaded tm:rw splash propagates to both brand:tmrw and
    # concept:tmrw (see _apply_uploaded_splashes sibling-key logic).
    ("concept", "tmrw",       "Splash - tmrw"),
    ("concept", "Smartech",   "Splash - Smartech"),
    ("concept", "Bikeshop",   "Splash - Bike Shop"),
    ("concept", "7EVN",       "Splash - 7EVN"),
    ("concept", "Playhouse",  "Splash - Playhouse"),
    ("concept", "Sanctuary",  "Splash - Sanctuary"),
    ("concept", "The Track",  "Splash - The Track"),
    ("concept", "Cornershop", "Splash - Cornershop"),
    ("concept", "tm:rw Cafe", "Splash - tm-rw Cafe"),
]

# Reverse maps so a CMS-uploaded splash for one (kind, name) propagates to
# every registry key backed by the same Drive folder — e.g. brand:smartech
# and concept:Smartech both use "Splash - Smartech", so an upload to either
# updates both (and thus screens keyed by city-brand OR by concept).
_splash_folder_by_target = {(k, n): fn for (k, n, fn) in SPLASH_FOLDERS}
_splash_keys_by_folder: dict = {}
for _sk, _sn, _sfn in SPLASH_FOLDERS:
    _splash_keys_by_folder.setdefault(_sfn, []).append((_sk, _sn))

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

# v0.1.84: device-token auth for the tablet's own self-edit calls.
# SELF_EDIT_ACTIONS are the actions a tablet performs on its OWN screen with
# no CMS login (staff overlay playlist editor, audio/poll/sync toggles). An
# authenticated CMS caller is always permission-checked instead (so a
# read-only viewer can't push via this path). A cookieless caller must present
# the screen's deviceSecret (issued at /register). ENFORCE flips grace→required
# once the fleet is on the APK that sends the header — until then a pre-secret
# tablet (no header) is grace-allowed (and logged); a *wrong* secret is always
# rejected.
SELF_EDIT_ACTIONS = (
    "playlist", "mix-splash", "product-card", "tap-next", "progress-bar", "audio",
    "poll-mode", "low-data-mode", "sync-group", "display-mode", "rotation",
)
ENFORCE_DEVICE_SECRET = os.environ.get("SCREENS_ENFORCE_DEVICE_SECRET", "0") == "1"

# v0.1.85: offline-screen alerting. When a screen that was online drops offline
# past a grace window it's logged to the activity feed and (if a webhook is
# configured) pushed to Slack/Discord/Mattermost; a recovery notice fires when
# it comes back. Webhook is optional — unset means activity-log only.
ALERT_WEBHOOK = os.environ.get("SCREENS_ALERT_WEBHOOK", "").strip()
try:
    ALERT_OFFLINE_AFTER_SEC = int(os.environ.get("SCREENS_ALERT_OFFLINE_AFTER_SEC", "300"))
except ValueError:
    ALERT_OFFLINE_AFTER_SEC = 300
_alerted_offline: set[str] = set()   # deviceIds we've already flagged as down
_ever_online: set[str] = set()       # deviceIds seen online at least once this run
# v0.1.58: integration secrets, {name: {value, updatedAt, updatedBy}}.
# Currently holds the Brand Asset Manager API key; structured as a dict
# so adding more keys later doesn't require an endpoint change. Only the
# Owner can read or write this map.
_secrets: dict = {}
# Whitelist of accepted secret slugs + their human labels (used in
# activity-log lines). Endpoints 404 for any name not in this map so a
# typo or crafted URL can't be used to fish around the secrets file.
_INTEGRATION_SECRETS: dict[str, str] = {
    "brandApiKey": "Brand Asset Manager API key",
}

# v0.1.59: tm:rw index API — Brand Asset Manager backend. Override with
# SCREENS_BRAND_API_BASE for staging. The /me endpoint is the canonical
# "is this key valid" check (the publicly-reachable /health probe only
# tells us the server is up, not whether we can authenticate).
BRAND_API_BASE = os.environ.get(
    "SCREENS_BRAND_API_BASE",
    # v0.1.64: tm:rw index redeployed to a new Cloud Run revision URL.
    # The old "-6dgw63xeaa-" host still answers (slightly stale) but is
    # being retired. Override with SCREENS_BRAND_API_BASE if it moves
    # again.
    "https://tmrw-index-api-izdr7go5hq-nw.a.run.app",
).rstrip("/")

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


def run_library_scan(force_full: bool = False, only_brand: str | None = None) -> dict:
    """Synchronously re-run scan-videos.py. Streams progress lines into
    _sync_state so the Drive Sync UI can render a live count.

    v0.1.46: pass `force_full=True` from manual "Sync now" requests so
    the change-token short-circuit in scan-videos.py is bypassed.
    Auto-sync (daily timer + initial-on-empty) defaults to False — if
    Drive reports zero changes since the last cursor, the scan exits
    in ~1 s without re-walking the inventory.

    v0.1.68: pass `only_brand` to re-scan just one brand folder and
    merge it into the existing library (CMS "Refresh this folder").
    """
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
        print(f"[sync] launching {scan_script} (force_full={force_full}, only_brand={only_brand!r})", file=sys.stderr, flush=True)
        env = os.environ.copy()
        if force_full:
            env["SCREENS_FORCE_FULL_SCAN"] = "1"
        if only_brand:
            env["SCREENS_SCAN_ONLY_BRAND"] = only_brand
        proc = subprocess.Popen(
            [sys.executable, "-u", str(scan_script)],   # -u = unbuffered stdout
            cwd=str(PROJECT),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,                                  # line-buffered
            encoding="utf-8",
            errors="replace",
            env=env,
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
    """Scan SPLASH_DIR for the configured folders and pick a landscape
    splash plus an optional portrait splash so the server can serve the
    right aspect per screen orientation (see [resolve_splash_for]).

    Matching is by TOP-LEVEL filename — the only level the Drive hydrator
    pulls (`_hydrate_splashes_from_drive` doesn't recurse), so portrait
    variants must live alongside the landscape file, not in a subfolder:
      • a name containing "portrait"  → the portrait variant
      • a name containing "landscape" → the explicit landscape pick
      • neither marker                → largest top-level file is landscape
        (legacy single-splash behaviour; portrait stays absent and
        portrait screens fall back to the landscape file)
    Files under Old/Compressed/NoLogo/Portrait subfolders stay ignored."""
    skip_segments = ("/old/", "/compressed/", "/nologo/", "/portrait/")

    def _url_for(f) -> str:
        rel_path = f.relative_to(SPLASH_DIR)
        base = "/splash/" + "/".join(urllib.parse.quote(p) for p in rel_path.parts)
        # Cache-bust on file size so that *replacing* a splash (same filename,
        # new content) changes the URL — the tablet keys its splash cache off
        # the URL, so without this a content update to an existing file would
        # never reach screens that already cached the old bytes. Size is stable
        # across Drive re-hydrations for unchanged content (mtime is not).
        try:
            return f"{base}?v={f.stat().st_size}"
        except OSError:
            return base

    def _by_size(ps):
        return sorted(ps, key=lambda p: p.stat().st_size, reverse=True)

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
        # filesystem find.
        pool = [c for c in candidates if c.parent == folder] or candidates
        portrait_pool = [c for c in pool if "portrait" in c.stem.lower()]
        landscape_pool = [c for c in pool if c not in portrait_pool]
        explicit_ls = [c for c in landscape_pool if "landscape" in c.stem.lower()]
        # Explicit "landscape" name wins; else largest non-portrait; else
        # largest of anything (degenerate all-portrait folder).
        ls_ranked = _by_size(explicit_ls) or _by_size(landscape_pool) or _by_size(pool)
        chosen = ls_ranked[0]
        meta = {
            "kind":     kind,
            "name":     name,
            "filename": chosen.name,
            "url":      _url_for(chosen),
            "sizeMb":   round(chosen.stat().st_size / (1024 * 1024), 1),
        }
        portrait_ranked = _by_size(portrait_pool)
        if portrait_ranked:
            p = portrait_ranked[0]
            meta["filenamePortrait"] = p.name
            meta["urlPortrait"]      = _url_for(p)
            meta["sizePortraitMb"]   = round(p.stat().st_size / (1024 * 1024), 1)
        out[f"{kind}:{name}"] = meta
    _splash_registry.clear()
    _splash_registry.update(out)
    _city_brand.clear()
    _city_brand.update(DEFAULT_CITY_BRAND)
    # Overlay any CMS-uploaded splashes on top of the Drive-scanned set.
    _apply_uploaded_splashes()


def _apply_uploaded_splashes() -> None:
    """Overlay CMS-uploaded splashes (under SPLASH_UPLOADS_DIR) on top of
    the Drive-scanned registry. An uploaded file wins *per orientation* —
    an orientation without an upload keeps whatever the Drive scan found.
    Idempotent; called at the end of _build_splash_registry and again after
    each upload so the change is live on the next screen poll."""
    root = SPLASH_UPLOADS_DIR
    if not root.is_dir():
        return

    def _uploaded_url(f) -> str:
        rel = f.relative_to(UPLOADS_DIR)
        base = "/uploaded/" + "/".join(urllib.parse.quote(p) for p in rel.parts)
        try:
            return f"{base}?v={f.stat().st_size}"
        except OSError:
            return base

    for sub in sorted(root.iterdir()):
        if not sub.is_dir():
            continue
        meta_file = sub / "meta.json"
        if not meta_file.is_file():
            continue
        try:
            info = json.loads(meta_file.read_text(encoding="utf-8"))
            kind = str(info["kind"]); name = str(info["name"])
        except Exception:
            continue

        def _find(orient: str):
            for f in sorted(sub.glob(f"{orient}.*")):
                if f.is_file() and f.suffix.lower() in (".mp4", ".mov"):
                    return f
            return None

        ls = _find("landscape")
        pt = _find("portrait")
        if not (ls or pt):
            continue
        # Apply to every registry key backed by the same Drive folder, so an
        # upload for brand "smartech" also lands on concept "Smartech" (both
        # back "Splash - Smartech"). resolve_splash_for checks concept before
        # brand, so without this a brand upload would miss concept screens.
        folder = _splash_folder_by_target.get((kind, name))
        sibling_keys = (_splash_keys_by_folder.get(folder)
                        if folder else None) or [(kind, name)]
        for sk, sn in sibling_keys:
            reg_key = f"{sk}:{sn}"
            meta = dict(_splash_registry.get(reg_key) or {"kind": sk, "name": sn})
            if ls:
                meta["url"]      = _uploaded_url(ls)
                meta["filename"] = ls.name
                meta["sizeMb"]   = round(ls.stat().st_size / (1024 * 1024), 1)
                meta["uploadedLandscape"] = True
            if pt:
                meta["urlPortrait"]      = _uploaded_url(pt)
                meta["filenamePortrait"] = pt.name
                meta["sizePortraitMb"]   = round(pt.stat().st_size / (1024 * 1024), 1)
                meta["uploadedPortrait"] = True
            meta["source"] = "upload"
            _splash_registry[reg_key] = meta


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
                    for status, _, _, _, chunk in drive_client.stream_file(f["id"]):
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


def resolve_splash_for(
    city: str | None,
    concept: str | None,
    orientation: str | None = None,
) -> dict | None:
    """Return splash meta for a screen at (city, concept). Concept overrides
    brand. When the screen reports a portrait orientation and the resolved
    splash has a portrait variant, returns a shallow copy whose `url`/`sizeMb`
    point at the portrait file — so callers that just read `url` transparently
    get the right aspect. `variant` records which file was served."""
    m = None
    if concept:
        m = _splash_registry.get(f"concept:{concept}")
    if m is None and city:
        brand = _city_brand.get(city)
        if brand:
            m = _splash_registry.get(f"brand:{brand}")
    if m is None:
        return None
    if (orientation or "").upper().startswith("PORT") and m.get("urlPortrait"):
        m = {
            **m,
            "url":     m["urlPortrait"],
            "sizeMb":  m.get("sizePortraitMb", m.get("sizeMb")),
            "variant": "portrait",
        }
    return m


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
        # SECURITY: contain the SPA-fallback path inside APP_DIR. This override
        # replaces the stdlib translate_path (which normalises away `..`), so
        # without this guard a request like `/..%2f..%2fdata/secrets.json`
        # decodes to `../../data/secrets.json` and `resolve()` escapes APP_DIR,
        # serving the auth DB / integration secrets / source. Mirror the
        # containment the media/splash/brand/uploaded branches already do.
        full = (APP_DIR / path).resolve()
        try:
            full.relative_to(APP_DIR)
        except ValueError:
            return str(APP_DIR / "index.html")
        return str(full)

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
        # v0.1.92: guided brand experiences. Public + read-only: the tablet
        # fetches the HTML once and caches it locally (same deal as /media
        # videos), so this must not sit behind a CMS session.
        if raw_path.startswith("/interactive/"):
            self._serve_interactive(raw_path); return
        if raw_path == "/apk":
            self._serve_release_download("modern"); return
        if raw_path == "/apk/legacy":
            self._serve_release_download("legacy"); return
        if raw_path == "/apk/modern":
            self._serve_release_download("modern"); return
        # Public, no-sign-in landing page for installing / re-installing the
        # player APK. The point: hand someone (or a tablet's own browser) a
        # single URL — screens.smartechworld.com/download — where they pick
        # modern vs legacy, see the current version, and can re-tap to
        # re-trigger a download when the in-app updater stalls.
        if raw_path in ("/download", "/install"):
            self._serve_download_page(); return
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
        if self.path.split("?", 1)[0] == "/api/splashes/upload":
            self._serve_api_splash_upload()
            return
        if self.path.split("?", 1)[0] == "/api/experiences/upload":
            self._serve_api_experience_upload()
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

    def end_headers(self) -> None:
        # v0.1.76: force browsers to revalidate the SPA's static assets
        # (index.html / *.jsx / *.js / *.css) on every load. They previously
        # carried no Cache-Control, so a CMS deploy kept showing a stale
        # cached page (and "fixes don't show up") until a manual hard-refresh.
        # `no-cache` = "always revalidate" — the stdlib file handler answers
        # If-Modified-Since with a 304 when unchanged, so this is cheap; on a
        # deploy the file mtime changes and the browser gets fresh content.
        # API responses set their own Cache-Control (no-store) and media /
        # uploads / splash / apk are served by dedicated handlers, so scope
        # this to the static-file paths only.
        p = self.path.split("?", 1)[0]
        if not (
            p.startswith("/api/") or p.startswith("/media/")
            or p.startswith("/splash/") or p.startswith("/uploaded/")
            or p.startswith("/brand/") or p.startswith("/apk")
        ):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

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

    # v0.2.3: typographic characters that show up in our own operator-facing
    # error messages. Folded to ASCII rather than replaced with "?" so the
    # message still reads properly in the CMS.
    _REASON_ASCII_FOLD = str.maketrans({
        "—": "-", "–": "-", "‘": "'", "’": "'",
        "“": '"', "”": '"', "…": "...", " ": " ",
    })

    def send_error(self, code, message=None, explain=None):
        """Keep non-latin-1 text out of the HTTP status line.

        `http.server` drops `message` straight into the status line and encodes
        that line as **latin-1**. A single character outside that range raises
        UnicodeEncodeError *while the response is half-written*: the handler
        thread dies, Cloud Run sees a malformed HTTP response and kills the
        **instance**, and because this service runs minScale=maxScale=1 there is
        no second instance to take over — the entire CMS goes down until it is
        redeployed, and does not recover on its own.

        That is not hypothetical. An **em dash in our own upload-rejection
        message** ("references something off-device — an external src/href")
        took production offline every single time an operator uploaded an
        experience that failed validation. The status line is a place for a
        short ASCII reason, and nothing else.

        So: fold typography to ASCII, flatten newlines (which would also break
        the status line, or worse, inject headers), and move the full original
        text to `explain`, which lands in the response BODY — built via
        `error_message_format` and encoded UTF-8, so it can safely carry
        anything. The CMS reads its message out of that body (see
        `uploadExperience` in ui.jsx), so operators still get the real reason.

        Applied here, on the Handler, rather than at each call site: there are
        ~18 `send_error` calls that interpolate a filename, brand, Drive error
        or validation message, any of which can carry a non-ASCII character. A
        guard at the boundary can't be forgotten by the next one.
        """
        if message is not None:
            flat = " ".join(str(message).split())          # no CR/LF/tabs
            safe = (flat.translate(self._REASON_ASCII_FOLD)
                        .encode("ascii", "replace").decode("ascii"))
            if explain is None and safe != flat:
                explain = flat                             # true text -> body
            message = safe
        super().send_error(code, message, explain)

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

    def _serve_api_experience_upload(self) -> None:
        """POST /api/experiences/upload — multipart/form-data:
          file   the .html bytes (required)
          name   human-readable label (optional; defaults to the filename)
          brand  brand id/label (optional)

        Admin+ only: an experience is arbitrary HTML+JS rendered fullscreen on
        a customer-facing screen, a far bigger blast radius than a video.

        The upload is REJECTED unless the page is fully self-contained (no
        external scripts/styles/images/fonts/fetches) — that's what lets the
        tablet cache one file and keep running with no network. Stored in the
        persistent uploads bucket, so it needs no deploy and survives redeploys.
        """
        if self._require_perm("experiences.edit") is None:
            return

        ctype = self.headers.get("Content-Type", "")
        if not ctype.lower().startswith("multipart/form-data"):
            self.send_error(415, "Expected multipart/form-data"); return
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
        if length > _EXPERIENCE_MAX_BYTES * 2:      # generous multipart overhead
            self.send_error(413, "Body too large"); return

        raw = self.rfile.read(length)
        try:
            parts = _parse_multipart(raw, boundary.encode("ascii"))
        except Exception as exc:
            self.send_error(400, f"Bad multipart body: {exc}"); return

        file_part = next((p for p in parts if p["name"] == "file" and p.get("filename")), None)

        def _field(field_name: str, default: str = "") -> str:
            return next((p["data"].decode("utf-8", "replace").strip()
                         for p in parts if p["name"] == field_name), default)

        if file_part is None or not file_part.get("data"):
            self.send_error(400, "Missing 'file' field"); return
        text, err = _validate_experience_html(file_part["data"])
        if err:
            # 422: the request is well-formed, the *content* is unusable.
            self.send_error(422, err); return

        orig = file_part.get("filename") or "experience.html"
        stem = re.sub(r"[^A-Za-z0-9_-]+", "-", Path(orig).stem).strip("-").lower()[:48] or "experience"
        name = (_field("name") or Path(orig).stem).strip()[:80]
        brand = _field("brand").strip()[:60] or None

        EXPERIENCE_UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
        with _STATE_LOCK:
            taken = {e.get("id") for e in _experiences}
        # Vendored names are reachable on the same route, so avoid colliding
        # with them too — first match wins in _serve_interactive.
        taken |= {p.stem for p in INTERACTIVE_DIR.glob("*.html")}
        exp_id = stem
        n = 2
        while exp_id in taken:
            exp_id = f"{stem}-{n}"; n += 1
        filename = f"{exp_id}.html"
        try:
            (EXPERIENCE_UPLOADS_DIR / filename).write_text(text, encoding="utf-8")
        except OSError as e:
            print(f"[experiences] write failed: {e}", file=sys.stderr)
            self.send_error(500, "Could not save the experience"); return

        user = self._current_user() or {}
        entry = {
            "id": exp_id,
            "name": name or exp_id,
            "brand": brand,
            "filename": filename,
            "sizeBytes": len(file_part["data"]),
            # Epoch ms, matching how the rest of the state stores timestamps —
            # avoids pulling datetime in just for this.
            "uploadedAt": int(time.time() * 1000),
            "uploadedBy": user.get("email") or user.get("name"),
        }
        with _STATE_LOCK:
            _experiences.append(entry)
            _save_experiences()
        # v0.2.6: flag CSS the oldest screens can't render. NOT a rejection —
        # a modern-only fleet can use modern CSS, and it isn't the server's
        # place to veto a brand's design. But these break SILENTLY on a Chrome
        # 83 box (rule dropped, no error anywhere), so the alternative to saying
        # it here is finding out from a shop floor. Logged as well as returned,
        # so it's on the record even if nobody reads the toast.
        warnings = _legacy_css_warnings(text)
        _log_activity(
            kind="library",
            text=f"Uploaded guided experience “{entry['name']}”"
                 + (f" — {len(warnings)} legacy-screen warning"
                    f"{'' if len(warnings) == 1 else 's'}" if warnings else ""),
            icon="upload",
        )
        if warnings:
            print(f"[experiences] {filename} legacy warnings: " + " | ".join(warnings),
                  file=sys.stderr)
        self._send_json({
            "ok": True,
            "experience": {**entry, "url": _experience_public_url(filename), "builtin": False},
            "warnings": warnings,
            "legacyChrome": LEGACY_WEBVIEW_CHROME,
        })

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
        # v0.1.86: uploading a single asset is a content-EDIT action, not a
        # Drive-SYNC action. It was gated on "library.sync" (owner/super_admin/
        # admin only), so the `manager`, `user` and `brand_partner` roles saw
        # the Upload button but got a 403 — "users can't upload." Gate on
        # "library.edit" (everyone except read-only viewers) to match; the
        # heavy global Drive-sync trigger keeps its stricter library.sync.
        if self._require_perm("library.edit") is None:
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

    def _serve_api_splash_upload(self) -> None:
        """POST /api/splashes/upload — multipart/form-data with fields:
          file         splash video bytes (required, .mp4/.mov)
          kind         "brand" | "concept" (optional, default "brand")
          name         splash name matching a known SPLASH_FOLDERS entry
                       (e.g. "smartech", "tmrw", "Smartech")
          orientation  "landscape" | "portrait" (required)

        Writes the file to SPLASH_UPLOADS_DIR/<key>/<orientation>.<ext> on
        the persistent uploads mount, records a meta.json, then overlays it
        on the live splash registry. Screens pick it up on their next poll
        (the URL is cache-busted on size). A CMS upload overrides the Drive
        splash for that orientation only. Routed straight from do_POST so it
        bypasses the JSON body reader, same as the library upload."""
        if self._require_perm("settings.edit") is None:
            return
        ctype = self.headers.get("Content-Type", "")
        if not ctype.lower().startswith("multipart/form-data"):
            self.send_error(415, "Expected multipart/form-data"); return
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
        raw = self.rfile.read(length)
        try:
            parts = _parse_multipart(raw, boundary.encode("ascii"))
        except Exception as exc:
            self.send_error(400, f"Bad multipart body: {exc}"); return

        file_part = next((p for p in parts if p["name"] == "file" and p.get("filename")), None)

        def _field(field_name: str, default: str = "") -> str:
            return next((p["data"].decode("utf-8", "replace").strip()
                         for p in parts if p["name"] == field_name), default)

        kind = (_field("kind", "brand") or "brand").lower()
        name = _field("name")
        orientation = _field("orientation").lower()

        if file_part is None:
            self.send_error(400, "Missing 'file' field"); return
        if orientation not in ("landscape", "portrait"):
            self.send_error(400, "orientation must be 'landscape' or 'portrait'"); return
        if kind not in ("brand", "concept"):
            self.send_error(400, "kind must be 'brand' or 'concept'"); return
        if not name:
            self.send_error(400, "Missing 'name' field"); return
        if len(name) > 64 or not re.match(r"^[A-Za-z0-9 :+&'./_-]+$", name):
            self.send_error(400, "Invalid splash name"); return
        # Brands are a fixed set (the city->brand mapping keys). Concepts are
        # open-ended so an operator can add a splash for ANY concept in the
        # location taxonomy — even one with no Drive folder yet; the upload
        # just creates a new concept:<name> entry in the registry that
        # screens reporting that concept then resolve to.
        if kind == "brand" and name not in {f[1] for f in SPLASH_FOLDERS if f[0] == "brand"}:
            self.send_error(400, f"Unknown brand '{name}'"); return
        orig_name = file_part.get("filename") or "splash.mp4"
        ext = os.path.splitext(orig_name)[1].lower().lstrip(".") or "mp4"
        if ext not in {"mp4", "mov"}:
            self.send_error(415, f"Splash must be .mp4 or .mov (got .{ext})"); return
        part_ctype = (file_part.get("content_type") or "").lower()
        if part_ctype and not (
            part_ctype.startswith("video/") or part_ctype == "application/octet-stream"
        ):
            self.send_error(415, f"Unsupported content type: {part_ctype}"); return

        key_slug = re.sub(r"[^a-zA-Z0-9]+", "_", f"{kind}-{name}").strip("_").lower() or "splash"
        dest_dir = SPLASH_UPLOADS_DIR / key_slug
        dest_dir.mkdir(parents=True, exist_ok=True)
        # Drop any prior file for this orientation (maybe a different ext)
        # so we never end up with both landscape.mp4 and landscape.mov.
        for old in dest_dir.glob(f"{orientation}.*"):
            try:
                old.unlink()
            except OSError:
                pass
        target = dest_dir / f"{orientation}.{ext}"
        tmp = target.with_suffix(target.suffix + ".part")
        with open(tmp, "wb") as fh:
            fh.write(file_part["data"])
        tmp.replace(target)
        (dest_dir / "meta.json").write_text(
            json.dumps({"kind": kind, "name": name}), encoding="utf-8"
        )

        with _STATE_LOCK:
            _apply_uploaded_splashes()
        meta = _splash_registry.get(f"{kind}:{name}", {})
        _log_activity(
            kind="upload",
            text=f"Uploaded {orientation} splash for {name}",
            icon="upload",
        )
        self._send_json({"ok": True, "splash": meta})

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

        # ── Platform metrics (founders' dashboard) ───────────────
        # Read-only aggregate usage counts. On the public internet, gated
        # ONLY by the X-Metrics-Key header (NOT a CMS session). Constant-time
        # compare; missing key and wrong key return the identical 401 (no
        # hints). The key is never logged (the request logger records the
        # path + status only, not headers).
        if path == "/api/metrics":
            provided = self.headers.get("X-Metrics-Key", "") or ""
            ok = bool(METRICS_KEY) and hmac.compare_digest(
                provided.encode("utf-8"), METRICS_KEY.encode("utf-8"))
            if not ok:
                self._send_json({"error": "unauthorized"}, status=401)
                return
            self._send_json(_metrics_payload())
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
                        _save_per_screen_soon()
                    # Per-screen splash resolution from the device's location.
                    screen_meta = _screens.get(screen_id, {})
                    location = screen_meta.get("location") or {}
                    splash = resolve_splash_for(
                        location.get("city"),
                        location.get("concept"),
                        screen_meta.get("orientation"),
                    )
                    # Enrich each pushed item with library-side flags
                    # (defaultUnmute) so the player can apply per-video
                    # audio at playback time. The items list is small
                    # per screen (a handful at most) so the lookup is
                    # cheap.
                    enriched_items = []
                    want_card = bool(s.get("productCard"))
                    for it in s["items"]:
                        lib = _library_lookup_by_id(it.get("id") or "")
                        merged = dict(it)
                        merged["defaultUnmute"] = bool((lib or {}).get("defaultUnmute"))
                        # v0.1.86: only screens with the product-card toggle on
                        # get the description / per-currency prices / packshot +
                        # brand logo for the on-screen card. Keeps the payload
                        # lean and the enriched-library lookup off the hot path
                        # for every other screen.
                        if want_card:
                            card = _product_card_for(it.get("id") or "")
                            if card:
                                merged.update(card)
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
                            # v0.1.60: each member's online window scales
                            # with its own pollMode — a SLOW member that
                            # last polled 4 min ago is still considered
                            # online while a FAST member that gap is not.
                            _, mem_online = _presence(last_hb, st.get("pollMode"), now_ts)
                            group_members.append({
                                "deviceId":   d,
                                "name":       meta.get("name") or d,
                                "online":     mem_online,
                                "screenCode": (meta.get("location") or {}).get("screenCode"),
                                "storeId":    (meta.get("location") or {}).get("storeId"),
                                "isSelf":     d == screen_id,
                            })
                        # Sort: self first, then alphabetical for stability.
                        group_members.sort(key=lambda m: (not m["isSelf"], (m.get("name") or "").lower()))
                    # v0.1.36: list every distinct sync group across the
                    # fleet so the tablet's "Join a group" picker has
                    # something to render without typing. Cheap: O(N)
                    # over _per_screen, which is at most a few hundred
                    # screens.
                    # v0.1.60: online cut-off scales per-member with
                    # pollMode rather than a fixed 15 s — see _presence.
                    available_groups: dict[str, dict] = {}
                    now_ts2 = time.time()
                    for d, st in _per_screen.items():
                        gid = st.get("syncGroup")
                        if not gid:
                            continue
                        meta2 = _screens.get(d) or {}
                        last_hb2 = meta2.get("lastHeartbeat") or 0
                        bucket = available_groups.setdefault(gid, {
                            "id": gid, "memberCount": 0, "onlineCount": 0,
                        })
                        bucket["memberCount"] += 1
                        _, ag_online = _presence(last_hb2, st.get("pollMode"), now_ts2)
                        if ag_online:
                            bucket["onlineCount"] += 1
                    available_sync_groups = sorted(
                        available_groups.values(), key=lambda g: g["id"],
                    )
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
                        "productCard": bool(s.get("productCard")),   # v0.1.86: on-screen product info card
                        "rotation":    int(s.get("rotation") or 0),  # v0.2.8: physical-mount display rotation (deg)
                        "experienceUrl": s.get("experienceUrl"),     # v0.1.92: guided brand experience (kiosk WebView), null = off
                        "experiencePromptPos": s.get("experiencePromptPos") or "top",   # v0.1.95: "top" | "bottom"
                        # v0.1.98: tap-to-skip. Forced OFF for a screen in a
                        # sync group, the same way mixSplash is: the group
                        # plays in lockstep off shared loop math, and letting
                        # one screen jump ahead would visibly break it (and the
                        # next sync tick would yank it back mid-video anyway).
                        # Enforced here, not just hidden in the UI, so an older
                        # tablet can't skip either. Stored value is preserved,
                        # so leaving the group restores the operator's choice.
                        "tapNext":     False if sync_group_id else bool(s.get("tapNext")),
                        # v0.2.0: playback progress bar. Deliberately NOT
                        # group-gated like tapNext above — it doesn't drive
                        # playback, and group members share a position, so
                        # their bars match rather than fight.
                        "progressBar": bool(s.get("progressBar")),
                        "audioOn":     s.get("audioOn", False),
                        "pollMode":    poll_mode,
                        # lowDataMode kept in the payload for old tablets
                        # that still read this field; new tablets read
                        # pollMode instead.
                        "lowDataMode": (poll_mode == "slow"),
                        "syncGroup":   sync_group_id,
                        "syncGroupMembers": group_members,
                        # v0.1.36: every distinct group on the fleet so
                        # tablets can render a Join picker without typing.
                        "availableSyncGroups": available_sync_groups,
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

        # v0.1.38: custom stores. Public read so the tablet's
        # LocationTaxonomy can merge in additions at app launch
        # without needing a user session. The CMS reads it on boot
        # too. POST + DELETE are gated below in the write section.
        if path == "/api/stores":
            with _STATE_LOCK:
                stores = sorted(_custom_stores.values(), key=lambda s: s.get("id", ""))
            self._send_json({"stores": stores})
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
                    # v0.1.60: presence is per-screen-pollMode now —
                    # a SLOW (5 min) tablet doesn't go offline just
                    # because it hasn't polled in 16 s. `live` and
                    # `online` are both exposed so the CMS can show
                    # "● Live" vs "● Online" vs "● Offline" if it
                    # wants the distinction; `online` keeps its
                    # existing semantics ("status pill should be
                    # green") so old consumers keep working.
                    pm = state.get("pollMode", DEFAULT_POLL_MODE)
                    live_flag, online_flag = _presence(last, pm, now)
                    record = {
                        **s,
                        "online":                online_flag,
                        "live":                  live_flag,
                        "pollIntervalSec":       _poll_interval_sec(pm),
                        "onlineThresholdSec":    int(_online_threshold_sec(pm)),
                        "liveThresholdSec":      int(_live_threshold_sec(pm)),
                        "secondsSinceHeartbeat": round(now - last, 1) if last else None,
                        "currentRevision":       state.get("revision", 0),
                        "currentItems":          state.get("items", []),
                        "mixSplash":             state.get("mixSplash", True),
                        "productCard":           bool(state.get("productCard")),   # v0.1.86
                        "rotation":              int(state.get("rotation") or 0),  # v0.2.8
                        "experienceUrl":         state.get("experienceUrl"),       # v0.1.92
                        "experiencePromptPos":   state.get("experiencePromptPos") or "top",   # v0.1.95
                        # Raw stored value (NOT the sync-group-forced one) so the
                        # CMS toggle shows what the operator actually chose and
                        # can explain why it's locked.
                        "tapNext":               bool(state.get("tapNext")),        # v0.1.98
                        "progressBar":           bool(state.get("progressBar")),    # v0.2.0
                        "audioOn":               state.get("audioOn", False),
                        "pollMode":              pm,
                        "lowDataMode":           pm == "slow",
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
                    # v0.1.84: never expose the per-device secret to CMS
                    # clients (the **s spread above would otherwise include it).
                    record.pop("deviceSecret", None)
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
            # v0.1.63: brands enriched with tm:rw logoUrl; the ETag
            # folds in the logo-map hash so a logo refresh also
            # invalidates client caches.
            data, etag = _library_with_logos()
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", "no-store")
                self._cors_headers()
                self.end_headers()
                return
            self._send_json(data, extra_headers=[("ETag", etag)])
            return

        if path == "/api/experiences":
            # v0.1.96: list guided experiences (vendored + uploaded). No
            # permission gate, matching /api/library above — the tablet reads
            # this with no CMS session to build its Add-content Experiences
            # section, and the HTML itself is already served publicly at
            # /interactive/. Uploading/deleting IS gated (experiences.edit).
            self._send_json({"experiences": _list_experiences()})
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
            # Sorted lists make the CMS UI deterministic. Snapshot under the
            # lock — a concurrent splash upload (_apply_uploaded_splashes) or
            # mapping edit mutates these dicts, and iterating them unlocked
            # would raise "dictionary changed size during iteration" → 500.
            with _STATE_LOCK:
                brands = sorted(
                    [v for k, v in _splash_registry.items() if k.startswith("brand:")],
                    key=lambda m: m["name"],
                )
                concepts = sorted(
                    [v for k, v in _splash_registry.items() if k.startswith("concept:")],
                    key=lambda m: m["name"],
                )
                city_brand_snapshot = dict(_city_brand)
            self._send_json({
                "brands":   brands,
                "concepts": concepts,
                "cityBrand": city_brand_snapshot,  # current city → brand mapping
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

        # v0.1.58: integration secrets — Brand Asset Manager API key,
        # etc. Owner only, on both read and write. Returns the raw value
        # so the Owner can copy it; the UI masks by default and reveals
        # on demand. A 403 for non-owners is enforced server-side so a
        # crafted fetch can't lift the key by spoofing the UI.
        m_sec = re.match(r"^/api/integrations/([a-zA-Z0-9_-]+)$", path)
        if m_sec:
            user = self._current_user()
            if user is None:
                self._send_json({"error": "unauthenticated"}, status=401); return
            if user.get("role") != "owner":
                self._send_json({"error": "owner_only"}, status=403); return
            name = m_sec.group(1)
            if name not in _INTEGRATION_SECRETS:
                self._send_json({"error": "unknown_secret"}, status=404); return
            entry = _secrets.get(name) or {}
            self._send_json({
                "name":      name,
                "value":     entry.get("value", ""),
                "updatedAt": entry.get("updatedAt"),
                "updatedBy": entry.get("updatedBy"),
            })
            return

        self.send_error(404, "Unknown API endpoint")

    def _serve_api_post(self) -> None:
        path = self.path.split("?", 1)[0]
        body = self._read_json()

        # v0.1.59: connection test for an integration secret. Calls the
        # remote API server-side so the key never leaves the CMS server
        # (it isn't even read into the request body — we just hit the
        # stored value). Owner-only, same as the read/write endpoints.
        # Currently only "brandApiKey" is wired; the route stays a
        # regex so adding more integrations later is a small add.
        m_test = re.match(r"^/api/integrations/([a-zA-Z0-9_-]+)/test$", path)
        if m_test:
            user = self._current_user()
            if user is None:
                self._send_json({"error": "unauthenticated"}, status=401); return
            if user.get("role") != "owner":
                self._send_json({"error": "owner_only"}, status=403); return
            name = m_test.group(1)
            if name not in _INTEGRATION_SECRETS:
                self._send_json({"error": "unknown_secret"}, status=404); return
            if name == "brandApiKey":
                result = _test_brand_api_key()
                self._send_json(result)
            else:
                self._send_json({"ok": False, "status": "not_implemented",
                                 "detail": "No test handler for this secret"}, status=501)
            return

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
            with _STATE_LOCK:
                prev = _screens.get(device_id, {})
                # v0.1.81: if an operator has renamed this screen from the
                # CMS (nameSetByOperator), keep that name across re-
                # registration. A tablet re-registers on every app relaunch
                # / update with its *local* name — without this, the operator
                # rename would silently revert on the next restart.
                name = (
                    (prev.get("name") if prev.get("nameSetByOperator") else None)
                    or body.get("name")
                    or device_id
                )
                # v0.1.81: a CMS location edit (locationSetByOperator) wins over
                # whatever the tablet re-reports on re-registration too.
                location = (
                    prev.get("location") if prev.get("locationSetByOperator")
                    else body.get("location")
                )
                _screens[device_id] = {
                    **prev,
                    "deviceId":        device_id,
                    "name":            name,
                    "location":        location,
                    "registeredAt":    time.time(),
                    "lastHeartbeat":   time.time(),
                    "appVersion":      body.get("appVersion"),
                    "appFlavor":       body.get("appFlavor"),
                    "deviceModel":     body.get("deviceModel"),
                    "ramMb":           body.get("ramMb"),
                    "screenWidth":     body.get("screenWidth"),
                    "screenHeight":    body.get("screenHeight"),
                    "orientation":     body.get("orientation"),
                }
                # v0.1.84: per-device secret for authenticating the tablet's
                # own self-edit calls. Issued once, preserved across re-
                # registration (the tablet stores it) — never regenerated, or
                # the tablet's stored copy would stop matching.
                device_secret = prev.get("deviceSecret") or secrets.token_urlsafe(24)
                _screens[device_id]["deviceSecret"] = device_secret
                is_new = "registeredAt" not in (_screens.get(device_id, {}))
                was_fresh = device_id not in _per_screen
                state = _ensure_screen_state(device_id)
                # v0.1.37: same legacy-flavor default the heartbeat
                # path applies — first-time-seen tablet starts SLOW
                # when it's the legacy build.
                if was_fresh and body.get("appFlavor") == "legacy":
                    state["pollMode"] = "slow"
                    state["lowDataMode"] = True
                    _save_per_screen()
                # v0.1.50: auto-group-by-storeId removed entirely.
                #
                # The v0.1.11 default was "tablet registers with
                # storeId → server drops it into store:<storeId>." That
                # was helpful when every screen at a store needed to be
                # in sync, but it kept biting users in the opposite
                # case: tablets at unrelated stores ending up grouped
                # purely because they happened to share a storeId, and
                # — after an APK update or fresh registration — tablets
                # silently re-joining a group the operator had
                # explicitly left.
                #
                # Sync groups are now strictly opt-in. The on-tablet
                # Join picker (staff overlay → content / device admin
                # → Sync group card, added in v0.1.36) and the CMS
                # Screen detail are the only ways a screen gets
                # grouped. Existing groups stay intact; the migration
                # below clears any auto-set "store:*" groups so the
                # current install resets cleanly.
                _save_screens()
            print(f"[register] {device_id} ({name})", file=sys.stderr)
            _log_activity(
                kind="register",
                text=f"{name} {'registered' if is_new else 're-registered'}",
                icon="check",
                tone="ok",
                target=device_id,
            )
            self._send_json({"ok": True, "screenId": device_id, "deviceSecret": device_secret})
            return

        # v0.1.15: light up every member of a sync group with a giant
        # synchronised clock so staff can stand in front of two screens
        # and visually confirm they tick on the same wall-clock second.
        # Body: { durationSec?: int (default 60) }. Returns the list of
        # screens affected.
        if path.startswith("/api/sync-groups/") and path.endswith("/calibrate"):
            group_id = urllib.parse.unquote(path[len("/api/sync-groups/"):-len("/calibrate")])
            # v0.2.9.1: auth. A CMS user with screens.command, OR a tablet
            # calibrating ITSELF — cookieless, presenting its own device secret,
            # where group_id is that screen's deviceId. Mirrors the self-edit
            # auth on /api/screens/<id>/<action> so the Device-admin Calibrate
            # button works on a no-login kiosk. Calibrating a whole multi-screen
            # group (group_id isn't a known deviceId) stays user-only.
            _cal_user = self._current_user()
            if _cal_user is not None:
                if self._require_perm("screens.command") is None:
                    return
            elif group_id in _screens:
                _provided = self.headers.get("X-Device-Secret")
                _expected = (_screens.get(group_id) or {}).get("deviceSecret")
                if _expected and _provided == _expected:
                    pass  # the device calibrating itself
                elif _provided and _provided != _expected:
                    self.send_error(403, "Invalid device secret"); return
                elif ENFORCE_DEVICE_SECRET:
                    self.send_error(403, "Device secret required"); return
                else:
                    print(f"[self-edit] {group_id} calibrate without device secret (grace)", file=sys.stderr)
            else:
                if self._require_perm("screens.command") is None:
                    return
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
                    "name", "location", "appVersion", "appFlavor", "deviceModel",
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
                    if val is None:
                        continue
                    # v0.1.81: a CMS rename sets nameSetByOperator. The tablet
                    # keeps reporting its *local* name on every heartbeat, so
                    # skip the name merge once an operator has overridden it —
                    # otherwise the rename would be clobbered seconds later.
                    if key == "name" and s.get("nameSetByOperator"):
                        continue
                    # v0.1.81: same override for location — once an operator
                    # edits region/city/store/concept from the CMS, don't let
                    # the tablet's reported location overwrite it.
                    if key == "location" and s.get("locationSetByOperator"):
                        continue
                    s[key] = val
                s["lastHeartbeat"] = time.time()
                # v0.1.37: pick the right pollMode default for a brand-
                # new tablet. Legacy flavor (Android 6/7 hardware on
                # event wifi) starts SLOW (5 min); modern stays NORMAL
                # (60 s). Only applies on first registration — we never
                # clobber a value the CMS or staff overlay has already
                # chosen. `was_fresh` checks _per_screen membership
                # before _ensure_screen_state lazily creates it.
                was_fresh = device_id not in _per_screen
                state = _ensure_screen_state(device_id)
                if was_fresh and body.get("appFlavor") == "legacy":
                    state["pollMode"] = "slow"
                    state["lowDataMode"] = True
                    _save_per_screen()
                # Coalesced, NOT _save_screens(): this fires for every beat of
                # every screen, and a full-object rewrite per beat put the fleet
                # permanently over the GCS ~1/sec per-object write cap, which is
                # what took the whole CMS down. See _save_screens_soon.
                _save_screens_soon()
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
            # NB: do NOT call self._read_json() again here. The body is
            # already read once at the top of _serve_api_post (the shared
            # `body = self._read_json()`). A second read calls
            # rfile.read(Content-Length) on an already-drained stream and
            # blocks until the socket times out on any keep-alive client
            # (OkHttp on the tablets, browsers, .NET) — which silently
            # broke log shipping fleet-wide from v0.1.25 until v0.1.74.
            # Reuse the parsed body from above.
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
            # Same fix as /api/logs above: reuse the body already read at
            # the top of _serve_api_post. Re-reading drained rfile here
            # hung every crash upload on keep-alive clients, so crash
            # reports never reached the server from v0.1.21 until v0.1.74.
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
            # v0.1.65: also drop the tm:rw brand/video caches so the next
            # /api/library re-pulls assigned videos + logos immediately.
            # "Sync now" is the operator's "go get the latest" button —
            # it should reflect a video they just registered in the asset
            # manager without waiting out the 5-min TTL.
            _reset_tmrw_caches()
            # Run on a background thread so the HTTP request doesn't hold up
            # the response. The UI polls /api/library/info to see when it's done.
            # v0.1.46: manual triggers always force a full scan — the
            # short-circuit is for the daily auto-sync, not for an
            # operator who just clicked Sync now expecting fresh data.
            threading.Thread(
                target=run_library_scan, kwargs={"force_full": True}, daemon=True,
            ).start()
            _log_activity(
                kind="sync",
                text="Drive sync started",
                icon="sync",
            )
            self._send_json({"ok": True, "queued": True})
            return

        # v0.1.68: refresh a single brand folder. Body: { brand }.
        # Re-scans just that folder and merges into library.json,
        # skipping the full-tree walk — fast when one brand changed.
        if path == "/api/library/refresh-folder":
            if self._require_perm("library.sync") is None:
                return
            brand = (body.get("brand") or "").strip()
            if not brand:
                self._send_json({"error": "missing_brand"}, status=400); return
            _reset_tmrw_caches()   # the brand's tm:rw videos may have changed too
            threading.Thread(
                target=run_library_scan,
                kwargs={"force_full": True, "only_brand": brand},
                daemon=True,
            ).start()
            _log_activity(
                kind="sync",
                text=f"Refreshing folder '{brand}'",
                icon="sync",
            )
            self._send_json({"ok": True, "queued": True, "brand": brand})
            return

        # ── Custom stores (v0.1.38) ──────────────────────────────
        # POST /api/stores  { id, name, address, city }
        # Adds a store to the dynamic taxonomy used by both clients.
        # Built-ins live in app/components/data.jsx + LocationTaxonomy.kt
        # and can't be edited from here — the form just appends.
        if path == "/api/stores":
            if self._require_perm("settings.edit") is None:
                return
            raw_id = (body.get("id") or "").strip().lower()
            name = (body.get("name") or "").strip()
            address = (body.get("address") or "").strip()
            city = (body.get("city") or "").strip()
            # Slug: only kebab-case lowercase alnum + hyphens. Anything
            # else would clash with existing built-in ids that this
            # shape expects ("smartech-selfridges", "tmrw-rinascente").
            slug_ok = bool(re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}", raw_id))
            if not slug_ok:
                self._send_json({"error": "bad_id", "detail": "id must be kebab-case lowercase, 2-63 chars"}, status=400); return
            if not name:
                self._send_json({"error": "bad_name"}, status=400); return
            if not city:
                self._send_json({"error": "bad_city"}, status=400); return
            # Reject collisions with known built-in ids so a custom
            # entry can't shadow a real retail store the picker
            # already shows.
            builtin_ids = {
                "tmrw-times-square", "smartech-selfridges",
                "smartech-kadewe", "tmrw-rinascente",
                "events", "test",
            }
            with _STATE_LOCK:
                if raw_id in builtin_ids:
                    self._send_json({"error": "reserved_id"}, status=409); return
                if raw_id in _custom_stores:
                    self._send_json({"error": "duplicate_id"}, status=409); return
                store = {"id": raw_id, "name": name, "address": address, "city": city}
                _custom_stores[raw_id] = store
                _save_custom_stores()
            _log_activity(
                kind="settings",
                text=f"Added store '{name}' ({raw_id})",
                icon="plus",
            )
            self._send_json({"ok": True, "store": store})
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
                # v0.1.56: persist to disk. Pre-v0.1.56 this dict lived
                # only in memory, so every Cloud Run redeploy silently
                # reset operator choices.
                _save_city_brand()
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
        # POST /api/screens/<deviceId>/product-card    { productCard: bool }
        # POST /api/screens/<deviceId>/experience      { experienceUrl?: string|null, promptPosition?: "top"|"bottom" }
        # POST /api/screens/<deviceId>/tap-next        { tapNext: bool }
        # POST /api/screens/<deviceId>/progress-bar    { progressBar: bool }
        # POST /api/screens/<deviceId>/audio           { audioOn: bool }
        # POST /api/screens/<deviceId>/poll-mode       { pollMode: "fast"|"normal"|"slow" }
        # POST /api/screens/<deviceId>/low-data-mode   { lowDataMode: bool }   (legacy — writes pollMode)
        # POST /api/screens/<deviceId>/sync-group      { syncGroup: string | null }
        # POST /api/screens/<deviceId>/display-mode    { displayMode: int | null }
        # POST /api/screens/<deviceId>/rotation        { rotation: 0|90|180|270 }
        # POST /api/screens/<deviceId>/name            { name: string }
        # POST /api/screens/<deviceId>/location        { region?, city?, storeId?, concept?, screenCode?, floor?, table? }
        m = re.match(r"^/api/screens/([^/]+)/(command|playlist|mix-splash|product-card|experience|tap-next|progress-bar|audio|poll-mode|low-data-mode|sync-group|display-mode|rotation|name|location)$", path)
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
            # v0.1.84: authenticate the caller.
            #  • A CMS session is ALWAYS permission-checked — a read-only
            #    viewer (who can list /api/screens and learn deviceIds) must
            #    not be able to push/audio/sync via the tablet self-edit path.
            #  • A cookieless caller is the tablet editing its own screen; it
            #    must present the screen's deviceSecret (issued at /register).
            #    Grace during rollout: a pre-secret tablet that sends no header
            #    is allowed + logged; a *wrong* secret is always rejected;
            #    ENFORCE_DEVICE_SECRET flips grace→required once the fleet ships
            #    the header.
            need = "screens.command" if action in ("command", "mix-splash") else "screens.push"
            user = self._current_user()
            if user is not None:
                if self._require_perm(need) is None:
                    return
            elif action in SELF_EDIT_ACTIONS and device_id in _screens:
                provided = self.headers.get("X-Device-Secret")
                expected = (_screens.get(device_id) or {}).get("deviceSecret")
                if expected and provided == expected:
                    pass  # authenticated device
                elif provided and provided != expected:
                    self.send_error(403, "Invalid device secret"); return
                elif ENFORCE_DEVICE_SECRET:
                    self.send_error(403, "Device secret required"); return
                else:
                    print(f"[self-edit] {device_id} {action} without device secret (grace)", file=sys.stderr)
            else:
                # Cookieless and not a valid self-edit (e.g. `command`) — 401.
                if self._require_perm(need) is None:
                    return
            with _STATE_LOCK:
                if action == "command":
                    cmd = body.get("command")
                    if cmd not in ("reboot", "restartPlayer", "clearCache", "unregister", "update", "refresh", "sendLogs"):
                        self.send_error(400, "Unknown command"); return
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id

                    # v0.1.57: unregister is now a server-side delete,
                    # not a queued command. The previous behaviour
                    # ("queued for next reconnect") was confusing —
                    # if the tablet was offline forever, the screen
                    # row hung around on the CMS list indefinitely.
                    # Now: the screen disappears from /api/screens
                    # immediately. If the tablet is online, its next
                    # /api/state poll returns "unknown screen" and
                    # it falls back to onboarding; if it's offline,
                    # the row just stays gone.
                    if cmd == "unregister":
                        _per_screen.pop(device_id, None)
                        _screens.pop(device_id, None)
                        _save_per_screen()
                        _save_screens()
                        print(f"[command] {device_id} -> unregister (deleted)", file=sys.stderr)
                        _log_activity(
                            kind="command",
                            text=f"Unregistered {screen_name}",
                            icon="close",
                            tone="err",
                            target=device_id,
                        )
                        self._send_json({"ok": True, "deleted": True})
                        return

                    state = _ensure_screen_state(device_id)
                    state["pendingCommands"].append({"command": cmd, "at": time.time()})
                    _save_per_screen_soon()
                    print(f"[command] {device_id} -> {cmd}", file=sys.stderr)
                    label = {
                        "reboot":        "Rebooted",
                        "restartPlayer": "Restarted player on",
                        "clearCache":    "Cleared cache on",
                        "update":        "Triggered update on",
                        "refresh":       "Forced refresh on",
                        "sendLogs":      "Requested logs from",
                    }[cmd]
                    _log_activity(
                        kind="command",
                        text=f"{label} {screen_name}",
                        icon={
                            "reboot":        "schedule",
                            "restartPlayer": "play",
                            "clearCache":    "trash",
                            "update":        "download",
                            "refresh":       "refresh",
                            "sendLogs":      "list",
                        }[cmd],
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
                    _save_per_screen_soon()
                    self._send_json({"ok": True, "mixSplash": state["mixSplash"]})
                    return
                if action == "product-card":
                    # v0.1.86: toggle the shopper-facing product info card for
                    # this screen. Bump revision so the tablet re-polls and
                    # starts / stops rendering the card on its next tick.
                    state = _ensure_screen_state(device_id)
                    state["productCard"] = bool(body.get("productCard", False))
                    state["revision"] += 1
                    _save_per_screen_soon()
                    self._send_json({"ok": True, "productCard": state["productCard"]})
                    return
                if action == "rotation":
                    # v0.2.8: physical-mount display rotation for this screen, in
                    # degrees. The player rotates its whole output to match a
                    # panel mounted rotated. 0 = no rotation (every screen's
                    # default). Only 0/90/180/270 are valid. Bump revision so the
                    # tablet re-polls and applies it on its next tick.
                    state = _ensure_screen_state(device_id)
                    try:
                        rot = int(body.get("rotation", 0))
                    except (TypeError, ValueError):
                        rot = -1
                    if rot not in (0, 90, 180, 270):
                        self._send_json(
                            {"ok": False, "error": "rotation must be 0, 90, 180 or 270"},
                            status=400,
                        )
                        return
                    state["rotation"] = rot
                    state["revision"] += 1
                    _save_per_screen_soon()
                    self._send_json({"ok": True, "rotation": state["rotation"]})
                    return
                if action == "experience":
                    # v0.1.92: point this screen at a guided brand experience
                    # (e.g. the WHOOP demo). null/"" clears it back to a plain
                    # video screen. Deliberately CMS-only (not in
                    # SELF_EDIT_ACTIONS): this decides what URL a kiosk
                    # WebView loads on a shop-floor device, so it stays behind
                    # the screens.push permission rather than being something a
                    # tablet can set for itself.
                    # v0.1.95: partial update — each field is only touched when
                    # its key is present, so the CMS can move the prompt without
                    # re-sending the URL (and vice versa).
                    state = _ensure_screen_state(device_id)
                    touched = []
                    if "experienceUrl" in body:
                        url = (body.get("experienceUrl") or "").strip()
                        if url:
                            parsed = urllib.parse.urlparse(url)
                            # https only — the player pins navigation to this
                            # origin, and an http page would be trivially
                            # tamperable on store wifi.
                            if parsed.scheme != "https" or not parsed.netloc:
                                self.send_error(400, "experienceUrl must be an absolute https:// URL")
                                return
                        else:
                            url = None
                        state["experienceUrl"] = url
                        touched.append("url")
                    if "promptPosition" in body:
                        pos = (body.get("promptPosition") or "").strip().lower()
                        # Only top/bottom: a corner would collide with the
                        # staff-unlock zones and swallow the customer's tap.
                        if pos not in ("top", "bottom"):
                            self.send_error(400, "promptPosition must be 'top' or 'bottom'")
                            return
                        state["experiencePromptPos"] = pos
                        touched.append("position")
                    if not touched:
                        self.send_error(400, "nothing to update — send experienceUrl and/or promptPosition")
                        return
                    state["revision"] += 1      # bump so the tablet re-polls promptly
                    _save_per_screen()
                    screen_name = (_screens.get(device_id) or {}).get("name") or device_id
                    if "url" in touched:
                        text = (f"Guided experience set on {screen_name}" if state["experienceUrl"]
                                else f"Guided experience cleared on {screen_name}")
                    else:
                        text = f"Guided experience prompt moved to {state['experiencePromptPos']} on {screen_name}"
                    _log_activity(kind="settings", text=text, icon="settings", target=device_id)
                    self._send_json({
                        "ok": True,
                        "experienceUrl": state["experienceUrl"],
                        "promptPosition": state.get("experiencePromptPos") or "top",
                    })
                    return
                if action == "tap-next":
                    # v0.1.98: customer-facing "next video" control. Bump the
                    # revision so the tablet re-polls and shows/hides it.
                    state = _ensure_screen_state(device_id)
                    state["tapNext"] = bool(body.get("tapNext", False))
                    state["revision"] += 1
                    _save_per_screen_soon()
                    self._send_json({"ok": True, "tapNext": state["tapNext"]})
                    return
                if action == "progress-bar":
                    # v0.2.0: slim playback progress bar along the bottom of the
                    # video. Bump the revision so the tablet re-polls promptly
                    # and shows/hides it, same as tap-next above.
                    state = _ensure_screen_state(device_id)
                    state["progressBar"] = bool(body.get("progressBar", False))
                    state["revision"] += 1
                    _save_per_screen_soon()
                    self._send_json({"ok": True, "progressBar": state["progressBar"]})
                    return
                if action == "audio":
                    state = _ensure_screen_state(device_id)
                    state["audioOn"] = bool(body.get("audioOn", False))
                    _save_per_screen_soon()
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
                    _save_per_screen_soon()
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
                    _save_per_screen_soon()
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
                    _save_per_screen_soon()
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
                    _save_per_screen_soon()
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
                if action == "name":
                    # v0.1.81: rename a screen from the CMS. The name lives on
                    # the _screens registry record (not per-screen playback
                    # state). We tag it nameSetByOperator so neither the
                    # tablet's heartbeat nor a re-registration clobbers it —
                    # see /api/screens/heartbeat + /api/screens/register.
                    # Operator-only (not in the self-edit list above, so this
                    # required screens.push). Works on offline screens too.
                    raw_name = body.get("name")
                    if not isinstance(raw_name, str):
                        self.send_error(400, "name must be a string"); return
                    new_name = raw_name.strip()[:80]
                    if not new_name:
                        self.send_error(400, "name cannot be empty"); return
                    s = _screens.get(device_id)
                    if not s:
                        self.send_error(404, "Unknown screen"); return
                    old_name = s.get("name") or device_id
                    s["name"] = new_name
                    s["nameSetByOperator"] = True
                    _save_screens()
                    if new_name != old_name:
                        _log_activity(
                            kind="settings",
                            text=f"Renamed '{old_name}' → '{new_name}'",
                            icon="settings",
                            target=device_id,
                        )
                    self._send_json({"ok": True, "name": new_name})
                    return
                if action == "location":
                    # v0.1.81: edit a screen's location (region/city/store/
                    # concept/screenCode/...) from the CMS. Only the keys
                    # present in the body are touched; "" or null clears a
                    # field. We tag locationSetByOperator so the tablet's
                    # heartbeat + re-registration don't clobber it (same
                    # override pattern as the name rename). Concept feeds
                    # resolve_splash_for, so this is how an operator fixes a
                    # screen pulling the wrong splash. Operator-only (not in
                    # the self-edit list → required screens.push). Works on
                    # offline screens too.
                    s = _screens.get(device_id)
                    if not s:
                        self.send_error(404, "Unknown screen"); return
                    loc = dict(s.get("location") or {})
                    allowed = ("region", "city", "storeId", "concept",
                               "screenCode", "floor", "table")
                    touched = False
                    for f in allowed:
                        if f not in body:
                            continue
                        v = body.get(f)
                        if v is None:
                            loc.pop(f, None); touched = True
                        elif isinstance(v, str):
                            vv = v.strip()
                            if vv:
                                loc[f] = vv
                            else:
                                loc.pop(f, None)
                            touched = True
                        else:
                            self.send_error(400, f"{f} must be a string or null"); return
                    if not touched:
                        self.send_error(400, "No location fields supplied"); return
                    s["location"] = loc
                    s["locationSetByOperator"] = True
                    _save_screens()
                    screen_name = s.get("name") or device_id
                    _log_activity(
                        kind="settings",
                        text=f"Edited location on {screen_name}",
                        icon="settings",
                        target=device_id,
                    )
                    self._send_json({"ok": True, "location": loc})
                    return

        self.send_error(404, "Unknown API endpoint")

    # ── Users PATCH/DELETE ────────────────────────────────────────
    # Edit (role / displayName / status) and remove. Owner is protected
    # against demotion or deletion at every entry point.

    def _serve_api_patch(self) -> None:
        path = self.path.split("?", 1)[0]
        body = self._read_json()

        # v0.1.58: PATCH /api/integrations/<name> { value: "<key>" }.
        # Owner-only — checked here, not via PERMISSIONS, because it's
        # a deliberate single-role gate. Empty string clears the secret.
        # The activity log records who changed it, but never the value.
        m_sec = re.match(r"^/api/integrations/([a-zA-Z0-9_-]+)$", path)
        if m_sec:
            user = self._current_user()
            if user is None:
                self._send_json({"error": "unauthenticated"}, status=401); return
            if user.get("role") != "owner":
                self._send_json({"error": "owner_only"}, status=403); return
            name = m_sec.group(1)
            if name not in _INTEGRATION_SECRETS:
                self._send_json({"error": "unknown_secret"}, status=404); return
            if "value" not in body:
                self._send_json({"error": "missing_value"}, status=400); return
            new_value = (body.get("value") or "").strip()
            with _STATE_LOCK:
                if new_value:
                    _secrets[name] = {
                        "value":     new_value,
                        "updatedAt": int(time.time()),
                        "updatedBy": user.get("display_name") or user.get("email"),
                    }
                else:
                    _secrets.pop(name, None)
                _save_secrets()
            _log_activity(
                kind="settings",
                text=(f"Cleared {_INTEGRATION_SECRETS[name]}"
                      if not new_value else
                      f"Updated {_INTEGRATION_SECRETS[name]}"),
                icon="settings",
                who=user.get("display_name"),
            )
            entry = _secrets.get(name) or {}
            self._send_json({
                "name":      name,
                "value":     entry.get("value", ""),
                "updatedAt": entry.get("updatedAt"),
                "updatedBy": entry.get("updatedBy"),
            })
            return

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

        # SECURITY: you can only edit a user you strictly out-rank. Without
        # this, an admin (has users.edit) could disable or demote a
        # super_admin — role_can_be_assigned_by only validates the *new* role,
        # not the target's current rank. Editing yourself is always allowed
        # (the field-level guards below still apply).
        if actor["id"] != target["id"] and not auth.actor_outranks(actor["role"], target.get("role") or ""):
            self._send_json({"error": "cannot_edit_peer"}, status=403); return

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
        # v0.1.56: on-tablet PIN for the staff overlay's unlock screen.
        # Accepts empty string to clear. The tablet pulls this from
        # /api/users on launch (v0.1.57); for now the hardcoded
        # UserDirectory.kt is the offline fallback.
        if "pin" in body:
            pin_val = (body.get("pin") or "").strip()
            if pin_val and not (pin_val.isdigit() and len(pin_val) == 4):
                self._send_json({"error": "bad_pin", "detail": "PIN must be exactly 4 digits or empty"}, status=400); return
            patch["pin"] = pin_val
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
        # v0.1.96: DELETE /api/experiences/<id> removes an UPLOADED experience.
        # Vendored interactive/*.html aren't in _experiences, so this 404s for
        # them — they're part of the repo, not something the CMS can delete.
        exp_m = re.match(r"^/api/experiences/([A-Za-z0-9_-]+)$", path)
        if exp_m:
            if self._require_perm("experiences.edit") is None:
                return
            exp_id = exp_m.group(1)
            with _STATE_LOCK:
                entry = next((e for e in _experiences if e.get("id") == exp_id), None)
                if entry is None:
                    self._send_json({"error": "not_found"}, status=404); return
                _experiences.remove(entry)
                _save_experiences()
            fn = entry.get("filename") or ""
            if fn:
                target = (EXPERIENCE_UPLOADS_DIR / fn).resolve()
                try:
                    inside = target.is_relative_to(EXPERIENCE_UPLOADS_DIR.resolve())
                except AttributeError:
                    inside = str(target).startswith(str(EXPERIENCE_UPLOADS_DIR.resolve()))
                if inside and target.is_file():
                    try:
                        target.unlink()
                    except OSError as e:
                        # Index is already updated; a stray file is harmless
                        # (it stops being advertised) but worth a log line.
                        print(f"[experiences] unlink {target} failed: {e}", file=sys.stderr)
            # v0.2.5: clear it off every screen that was showing it.
            #
            # This used to reason: "screens still pointed at it fall back to
            # their video loop — the player treats a failed fetch with NO CACHE
            # as no experience." The "no cache" is what made that wrong. The
            # whole point of an experience is that the tablet caches the file
            # and runs it offline forever, so a screen that already had it
            # **kept showing the deleted experience indefinitely** — deleting
            # from the library did nothing to the screens actually running it.
            #
            # It also stranded the CMS: the screen kept a URL that no longer
            # resolves to anything in the library, which the picker could only
            # show as an unknown "Custom:" value.
            #
            # So deleting now means deleting: any screen pointed at this
            # experience is reset to plain video, and the revision bump makes
            # the tablet pick that up on its next poll and drop the WebView.
            cleared: list[str] = []
            if fn:
                # Match on the PATH, not the full URL. A screen's stored
                # experienceUrl is absolute, and _experience_public_url builds
                # it from auth.PUBLIC_URL — so an exact-URL compare silently
                # matches nothing if a screen was pointed at this file when
                # PUBLIC_URL was a different origin (or unset). Then the delete
                # would look like it worked and leave the screen running the
                # experience forever, which is the exact bug being fixed. The
                # uploads dir is flat, so a "/interactive/<file>" suffix can
                # only ever mean this one file.
                suffix = f"/interactive/{fn}"
                deleted_url = _experience_public_url(fn)
                with _STATE_LOCK:
                    for device_id, state in _per_screen.items():
                        u = state.get("experienceUrl") or ""
                        if u and (u == deleted_url or u.split("?")[0].endswith(suffix)):
                            state["experienceUrl"] = None
                            state["revision"] += 1
                            cleared.append(device_id)
                    if cleared:
                        _save_per_screen()
            _log_activity(
                kind="library",
                text=f"Deleted guided experience “{entry.get('name') or exp_id}”"
                     + (f" — reset {len(cleared)} screen{'' if len(cleared) == 1 else 's'} to plain video"
                        if cleared else ""),
                icon="trash",
            )
            self._send_json({"ok": True, "id": exp_id, "screensReset": len(cleared)})
            return
        # v0.1.38: DELETE /api/stores/<id> removes a custom store.
        # Built-ins (defined in data.jsx + LocationTaxonomy.kt) can't
        # be deleted because they're not in _custom_stores; the
        # handler 404s for any id not in the dict.
        store_m = re.match(r"^/api/stores/([A-Za-z0-9_-]+)$", path)
        if store_m:
            if self._require_perm("settings.edit") is None:
                return
            store_id = store_m.group(1)
            with _STATE_LOCK:
                if store_id not in _custom_stores:
                    self._send_json({"error": "not_found"}, status=404); return
                name = _custom_stores[store_id].get("name") or store_id
                del _custom_stores[store_id]
                _save_custom_stores()
            _log_activity(
                kind="settings",
                text=f"Deleted store '{name}'",
                icon="trash",
                tone="err",
            )
            self._send_json({"ok": True})
            return

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

    def _serve_download_page(self) -> None:
        """Public, sign-in-free landing page for installing the player APK.

        Served at /download (and /install). Renders the current version and
        two big download buttons — modern (Android 8+) and legacy (Android
        6/7) — that point back at the /apk proxy routes. Re-tapping a button
        re-streams the file, so this doubles as the "the in-app updater
        stalled, just grab it again" page. Self-contained HTML (inline CSS,
        no app bundle) so it loads instantly on a fresh tablet's browser."""
        info = _release_info() or {}
        version = info.get("versionName") or ""
        release_url = info.get("releaseUrl") or ""
        published = (info.get("publishedAt") or "")[:10]   # YYYY-MM-DD
        ver_label = f"v{html.escape(version)}" if version else "latest build"

        # Recommend a build based on the device actually viewing this page —
        # normally the tablet's own browser, so its User-Agent carries the
        # Android version. Modern targets Android 8+; the legacy build is for
        # the old Android 6/7 retail boxes. Unknown / desktop → default to
        # Modern (fits nearly all current screens) with no hard recommendation.
        ua = self.headers.get("User-Agent", "")
        m_ver = re.search(r"Android\s+(\d+)", ua)
        android_major = int(m_ver.group(1)) if m_ver else None
        detected = android_major is not None
        recommended = "legacy" if (detected and android_major < 8) else "modern"

        def _btn(href: str, primary: bool, title: str, sub: str, badge_text: str = "") -> str:
            bg = "#2563eb" if primary else "#1c1c20"
            border = "#2563eb" if primary else "#33333a"
            fg = "#ffffff" if primary else "#e7e7ea"
            badge = f'<span class="rec">{html.escape(badge_text)}</span>' if badge_text else ""
            return (
                f'<a class="btn" download href="{href}" '
                f'style="background:{bg};border:1px solid {border};color:{fg}">'
                f'<span class="btn-row"><span class="btn-title">{html.escape(title)}</span>{badge}</span>'
                f'<span class="btn-sub">{html.escape(sub)}</span>'
                f'</a>'
            )

        modern_btn = _btn(
            "/apk", recommended == "modern", "Modern Download",
            "Android 8 and newer",
            "✓ Best for this screen" if (recommended == "modern" and detected) else "",
        )
        legacy_btn = _btn(
            "/apk/legacy", recommended == "legacy", "Legacy Download",
            "For older Android 6 & 7 boxes",
            "✓ Best for this screen" if (recommended == "legacy" and detected) else "",
        )

        # `android_major` is our own parsed int, so this hand-built HTML is safe
        # to inject unescaped.
        if detected:
            rec_label = "Modern" if recommended == "modern" else "Legacy"
            detect_line = (
                f'This screen is on <strong>Android {android_major}</strong> — '
                f'get the <strong>{rec_label}</strong> build.'
            )
        else:
            detect_line = (
                '<strong>Modern</strong> works on nearly all screens. Use '
                '<strong>Legacy</strong> only for older Android 6 / 7 boxes.'
            )

        ver_line = (
            f'Latest version <strong>{ver_label}</strong>'
            + (f' · released {html.escape(published)}' if published else "")
        ) if version else (
            "Version lookup is temporarily unavailable — the download buttons "
            "below still fetch the latest build."
        )
        notes_link = (
            f'<a class="notes" href="{html.escape(release_url)}" target="_blank" '
            f'rel="noopener noreferrer">Release notes ↗</a>'
            if release_url else ""
        )

        page = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex">
<title>Install the Screens player</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; min-height: 100vh;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: #0b0b0d; color: #e7e7ea;
    display: flex; align-items: flex-start; justify-content: center;
    padding: 40px 18px;
  }}
  .card {{ width: 100%; max-width: 460px; }}
  .kicker {{ font-size: 12px; letter-spacing: .14em; text-transform: uppercase; color: #6b6b73; }}
  h1 {{ font-size: 26px; line-height: 1.2; margin: 8px 0 6px; color: #fafafa; }}
  .ver {{ font-size: 13.5px; color: #9a9aa2; margin: 0 0 24px; line-height: 1.5; }}
  .ver strong {{ color: #d4d4d8; font-weight: 600; }}
  .btn {{
    display: flex; flex-direction: column; gap: 2px;
    text-decoration: none; border-radius: 12px;
    padding: 16px 18px; margin-bottom: 12px;
    transition: transform .04s ease;
  }}
  .btn:active {{ transform: scale(.99); }}
  .btn-row {{ display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }}
  .btn-title {{ font-size: 16px; font-weight: 600; }}
  .btn-sub {{ font-size: 12.5px; opacity: .8; }}
  .rec {{ font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
          background: rgba(255,255,255,.22); color: #fff; letter-spacing: .01em; white-space: nowrap; }}
  .detect {{ font-size: 13.5px; color: #c8c8d0; margin: 0 0 18px; line-height: 1.55;
             background: #131316; border: 1px solid #26262c; border-radius: 10px; padding: 12px 14px; }}
  .detect strong {{ color: #fafafa; font-weight: 600; }}
  .help {{
    margin-top: 22px; padding: 16px 18px;
    background: #131316; border: 1px solid #26262c; border-radius: 12px;
    font-size: 13px; line-height: 1.6; color: #a8a8b0;
  }}
  .help b {{ color: #d4d4d8; font-weight: 600; }}
  .help ol {{ margin: 8px 0 0; padding-left: 18px; }}
  .help li {{ margin-bottom: 4px; }}
  .notes {{ display: inline-block; margin-top: 14px; font-size: 13px; color: #7aa2f7; text-decoration: none; }}
  .foot {{ margin-top: 28px; font-size: 11.5px; color: #5a5a62; text-align: center; }}
</style>
</head>
<body>
  <div class="card">
    <div class="kicker">Smartech Screens</div>
    <h1>Install the player</h1>
    <p class="ver">{ver_line}</p>
    <p class="detect">{detect_line}</p>
    {modern_btn}
    {legacy_btn}
    {notes_link}
    <div class="help">
      <b>Download won't start, or stalled?</b> Just tap the button again — each
      tap re-fetches the file from the start.
      <ol>
        <li>Tap a download button above.</li>
        <li>Open the downloaded file and choose <b>Install</b>.</li>
        <li>If prompted, allow installs from this browser (Settings →
            "Install unknown apps"), then re-open the file.</li>
        <li>Launch <b>Screens</b> and follow on-screen setup.</li>
      </ol>
    </div>
    <div class="foot">Modern = Android 8 and newer · Legacy = Android 6 / 7. If a build won't install, try the other one.</div>
  </div>
</body>
</html>"""
        body = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors_headers()
        self.end_headers()
        try:
            self.wfile.write(body)
        except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError, OSError):
            return

    def _serve_interactive(self, raw_path: str) -> None:
        """Serve a guided-experience HTML file from INTERACTIVE_DIR.

        Public + read-only. Only bare `<name>.html` inside the directory is
        reachable: the name is matched against a strict pattern and the
        resolved path is re-checked to be inside INTERACTIVE_DIR, so `..`
        or an absolute path can't climb out and serve the repo (serve.py,
        secrets.json, ...) to an anonymous caller.
        """
        name = urllib.parse.unquote(raw_path[len("/interactive/"):])
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}\.html", name):
            self.send_error(404, "Not found"); return
        # v0.1.96: vendored experiences (shipped in the image) first, then
        # CMS-uploaded ones (persistent uploads bucket). Same public route for
        # both so the tablet caches them identically and doesn't care which is
        # which. Each candidate is re-checked to be inside its own root, so a
        # crafted name still can't climb out to serve.py / secrets.json.
        target = None
        for root in (INTERACTIVE_DIR, EXPERIENCE_UPLOADS_DIR):
            cand = (root / name).resolve()
            try:
                inside = cand.is_relative_to(root.resolve())
            except AttributeError:                   # py<3.9 safety net
                inside = str(cand).startswith(str(root.resolve()))
            if inside and cand.is_file():
                target = cand
                break
        if target is None:
            self.send_error(404, "Not found"); return
        try:
            body = target.read_bytes()
        except OSError as e:
            print(f"[interactive] read failed for {name}: {e}", file=sys.stderr)
            self.send_error(404, "Not found"); return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # Always revalidate: the tablet caches its own copy anyway, and this
        # way a redeploy of the experience reaches devices on their next pull.
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

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
        tag = info.get("tagName") or ""
        # v0.1.78: forward the client's Range so the tablet's resumable APK
        # download actually resumes (206) instead of restarting from 0 on
        # flaky wifi. Both the asset API and the public download URL 302 to a
        # CDN that honours Range; urllib carries the header across the redirect.
        client_range = self.headers.get("Range")
        if not asset_id and not tag:
            self.send_error(404, f"No {flavor} APK in latest release"); return

        def _open_upstream(range_val):
            """Open the upstream APK byte stream, optionally with a Range.
              • asset_id set → authed/asset API (private repos + exact bytes).
              • else tag → the PUBLIC release-download URL (works with a
                dead/absent token — avoids the rate-limited asset API).
            Both 302 to a CDN that honours Range; urllib carries the header
            across the redirect. _github_urlopen retries anonymously if a
            stale token is rejected (repo is public)."""
            if asset_id:
                api_url = f"https://api.github.com/repos/{GITHUB_RELEASES_REPO}/releases/assets/{asset_id}"
                return _github_urlopen(
                    api_url, accept="application/octet-stream", timeout=30,
                    extra_headers=({"Range": range_val} if range_val else None),
                )
            name = f"screens-player-{flavor}-{tag}.apk"
            pub_url = f"https://github.com/{GITHUB_RELEASES_REPO}/releases/download/{tag}/{name}"
            req_headers = {"User-Agent": "screens-app-v2-server"}
            if range_val:
                req_headers["Range"] = range_val
            return urllib.request.urlopen(
                urllib.request.Request(pub_url, headers=req_headers), timeout=30,
            )

        try:
            try:
                upstream = _open_upstream(client_range)
            except urllib.error.HTTPError as e:
                # v0.1.87: a stale/oversized `.part` on the tablet makes its
                # resumable Updater send `Range: bytes=<n>-` with n >= the
                # current APK size, so GitHub answers 416 Range Not Satisfiable.
                # We used to relay that as a 502 — but the Updater treats 502 as
                # a generic error and retries with the SAME range, so legacy
                # tablets loop on 502 and never update. On a 416, refetch the
                # whole file (no Range) and serve it as a plain 200: the
                # Updater's 200 branch truncates its `.part` and restarts from 0,
                # self-healing. (Twin of the media-proxy past-EOF 416 fix.)
                if e.code == 416 and client_range:
                    print("[release-download] upstream 416 (stale client range); refetching full file", file=sys.stderr)
                    client_range = None
                    upstream = _open_upstream(None)
                else:
                    raise
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
            # v0.1.78: relay the upstream's 206 + Content-Range so a resumed
            # download lands correctly; advertise Accept-Ranges so the tablet
            # knows it can resume.
            up_status = getattr(upstream, "status", None) or 200
            self.send_response(206 if up_status == 206 else 200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Accept-Ranges", "bytes")
            content_range = upstream.headers.get("Content-Range")
            if content_range:
                self.send_header("Content-Range", content_range)
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
                    # Past-EOF range: emit a proper 416 WITH Content-Range so
                    # the client learns the real size and re-requests (tablet
                    # self-heal). Can't use send_error() — it flushes
                    # end_headers() before we can add Content-Range, so the
                    # header was silently dropped. Mirrors the Drive path.
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
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
        # echo the actual status (206 vs 200) + the EXACT byte range/total
        # that drive_client parsed from Drive's own response headers.
        #
        # v0.1.74: declare an exact Content-Length whenever the body fits
        # Cloud Run's buffered-response ceiling (~32 MB). The exact length
        # is what lets the tablet's download-integrity guard
        # (VideoCache premature-EOF check) catch a truncated download and
        # re-pull the tail instead of caching a broken MP4. We use the
        # *real* size from Drive — NOT the rounded library `sizeMb`, which
        # is what historically forced us to omit the length (a rounded
        # count made OkHttp premature-EOF a perfectly good stream). Bodies
        # above the ceiling still stream with Connection: close + no
        # length (Cloud Run can't buffer a >32 MB declared response); the
        # v0.1.73 self-heal covers a rare truncation there.
        CLOUD_RUN_BUFFER_LIMIT = 32 * 1024 * 1024
        headers_sent = False
        attempt = 0
        while True:
            attempt += 1
            try:
                for status, start, end, total, chunk in drive_client.stream_file(
                    file_id, range_header=range_header
                ):
                    if not headers_sent:
                        if status == 206:
                            self.send_response(206)
                            total_s = str(total) if total else "*"
                            self.send_header("Content-Range", f"bytes {start}-{end}/{total_s}")
                            body_len = (end - start + 1) if end >= start else None
                            if body_len and body_len <= CLOUD_RUN_BUFFER_LIMIT:
                                self.send_header("Content-Length", str(body_len))
                            else:
                                self.send_header("Connection", "close")
                                self.close_connection = True
                        else:
                            self.send_response(200)
                            if total and total <= CLOUD_RUN_BUFFER_LIMIT:
                                self.send_header("Content-Length", str(total))
                            else:
                                # Length unknown or too big to buffer:
                                # stream to EOF. Connection:close tells
                                # HTTP/1.1 clients to read until the socket
                                # drops; close_connection makes the Python
                                # server actually drop it afterwards.
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
                # A Range that starts at/past EOF makes Drive return
                # 416 Range Not Satisfiable (urllib raises HTTPError
                # code=416). Forward it AS 416 — NOT 500. A tablet doing a
                # resumable download holds a `.part` that's now >= the real
                # file size (the video was re-encoded smaller while it had a
                # partial of the larger one); on 416 the player discards the
                # partial and re-pulls from byte 0, whereas a 500 it treats
                # as retryable and loops on the same out-of-range request
                # forever (the bug that wedged H8 on tcl-global-3). Surfaced
                # immediately, before the retry, since retrying is pointless.
                if not headers_sent and getattr(e, "code", None) == 416:
                    print(f"[/media] range past EOF for {file_id}: {e} — returning 416", file=sys.stderr)
                    self.send_response(416)
                    if size:
                        self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Type", ctype)
                    self.end_headers()
                    return
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
    # v0.1.58: env-var seed for the Brand Asset Manager API key. Only
    # used when the on-disk JSON has no value — once an Owner saves a
    # key from Settings → Integrations, the JSON wins. This means a
    # rotated key set via the CMS isn't quietly overwritten by a stale
    # env var on the next Cloud Run revision.
    _env_brand_api_key = os.environ.get("SCREENS_BRAND_API_KEY", "").strip()
    if _env_brand_api_key and not (_secrets.get("brandApiKey") or {}).get("value"):
        with _STATE_LOCK:
            _secrets["brandApiKey"] = {
                "value": _env_brand_api_key,
                "updatedAt": int(time.time()),
                "updatedBy": "env:SCREENS_BRAND_API_KEY",
            }
            _save_secrets()
        print("[secrets] seeded brandApiKey from SCREENS_BRAND_API_KEY", file=sys.stderr)
    # v0.1.50: clear ALL auto-set "store:<id>" sync groups. The
    # auto-group-by-storeId logic is gone (see the register handler);
    # this clears the residue from earlier installs so the current
    # state matches the new "opt in only" model. Custom group names
    # the operator explicitly typed (e.g. "wall-A") don't start with
    # "store:" and stay intact.
    with _STATE_LOCK:
        cleared = 0
        for d, s in _per_screen.items():
            grp = s.get("syncGroup")
            if not grp or not grp.startswith("store:"):
                continue
            s["syncGroup"] = None
            cleared += 1
        if cleared:
            _save_per_screen()
            print(f"[migrate] cleared {cleared} auto-set store:* sync group(s)", file=sys.stderr)
    # v0.2.12: background state flusher — persists dirty _screens / _per_screen
    # to disk off the request thread and out from under _STATE_LOCK, so a slow
    # gcsfuse write can't stall polls or self-edit/toggle POSTs (which is what
    # made rotation changes fail to stick under load).
    threading.Thread(target=_flush_state_loop, daemon=True).start()
    # Daily re-scan in a background thread. Doesn't run on boot — the
    # existing library.json from the last run is used; Drive Sync UI lets
    # the user trigger an on-demand scan if needed.
    threading.Thread(target=daily_sync_loop, daemon=True).start()
    # v0.1.85: watch for screens dropping offline and alert (webhook + activity).
    threading.Thread(target=_offline_monitor_loop, daemon=True).start()
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
