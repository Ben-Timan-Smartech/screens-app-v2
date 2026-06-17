"""
Scan the brand content for real video data and emit a JSX file that
overrides MOCK_VIDEOS / MOCK_BRANDS / MOCK_SCREENS_STORE / MOCK_ACTIVITY
with grounded values.

Two modes, picked automatically by env:

  • LOCAL  — walks DRIVE_ROOT (a Windows G:\\ Drive-for-Desktop mount).
             The default for dev. Probes MP4 atoms for width/height/
             duration since reads are cheap on a local file.

  • CLOUD  — uses the Drive API. Triggered by SCREENS_DRIVE_BRANDS_ID
             being set. Skips the MP4 probe (Drive bytes are remote +
             rate-limited; not worth pulling first 1MB of every video).
             Sets durationSec/width/height to None — CMS shows '—'.

Output is written next to this script, so it works the same on a Windows
laptop and inside the Cloud Run container at /app:
    <project>/app/components/real-data.jsx
    <project>/app/components/library.json
"""

from __future__ import annotations

import json
import os
import re
import struct
import urllib.parse
from pathlib import Path

# Drive helper — only imported on demand. Lets local dev run without
# google-api-python-client installed.
try:
    import drive_client
except ImportError:
    drive_client = None  # type: ignore

# ── Config ────────────────────────────────────────────────────────────
PROJECT = Path(__file__).resolve().parent

# Local mode: filesystem path. Overridable to ease testing on non-Windows
# dev machines and to match serve.py's SCREENS_MEDIA_DIR convention.
DRIVE_ROOT = Path(os.environ.get(
    "SCREENS_MEDIA_DIR",
    r"G:\Shared drives\Smartech\Screens\Brand Content",
))

# Cloud mode: Drive folder ID. When set, replaces the filesystem walk.
DRIVE_BRANDS_FOLDER_ID = os.environ.get("SCREENS_DRIVE_BRANDS_ID")

# v0.1.46 Phase 2: where we persist the change-token cursor between
# scans. Sibling of LIBRARY_JSON so a single SCREENS_LIBRARY_PATH env
# var pins everything to /data on Cloud Run.
DRIVE_TOKEN_PATH = Path(os.environ.get(
    "SCREENS_DRIVE_TOKEN_PATH",
    str(Path(os.environ.get(
        "SCREENS_LIBRARY_PATH",
        str(Path(__file__).resolve().parent / "app" / "components" / "library.json"),
    )).parent / "drive_change_token.json"),
))

# v0.1.47 Phase 3: cached broad-query inventory snapshot. After every
# full scan we write the whole folder+video list here. On a subsequent
# scan where changes.list returns only a handful of changes, we patch
# this snapshot in place (via parallel files.get for the changed IDs)
# and reclassify, skipping the broad query entirely.
INVENTORY_SNAPSHOT_PATH = Path(os.environ.get(
    "SCREENS_DRIVE_INVENTORY_PATH",
    str(Path(os.environ.get(
        "SCREENS_LIBRARY_PATH",
        str(Path(__file__).resolve().parent / "app" / "components" / "library.json"),
    )).parent / "drive_inventory_snapshot.json"),
))

# Above this many changes since the last cursor we skip the
# incremental apply and fall through to a full broad-query rebuild —
# at that point the per-file metadata fetches are no cheaper than a
# single broad sweep.
INCREMENTAL_CHANGE_THRESHOLD = 50

OUT_FILE = PROJECT / "app" / "components" / "real-data.jsx"
# Library JSON path. Defaults to the in-tree CMS components dir (good
# for local dev) but is overridable so Cloud Run can point it at
# /data/library.json — the FUSE-mounted bucket — so a scan survives
# container restarts. Mirror the same env var in serve.py.
LIBRARY_JSON = Path(os.environ.get(
    "SCREENS_LIBRARY_PATH",
    str(PROJECT / "app" / "components" / "library.json"),
))

# Seed brand records — folders whose names + products we know up front. The
# scanner walks every direct subfolder of `Brand Content/` and uses these as
# overrides so we get nice product lists for the brands we've curated. Any
# folder not listed here is auto-discovered with an empty product list.
SEED_BRANDS: list[dict] = [
    {"folder": "SONOS",          "name": "SONOS",          "id": "sonos",          "products": ["Ace", "Arc", "Era 300", "Move", "Ray", "Sub Mini"]},
    {"folder": "Motorola",       "name": "Motorola",       "id": "motorola",       "products": ["Razr 50", "Edge 70", "G Stylus"]},
    {"folder": "Foreo",          "name": "Foreo",          "id": "foreo",          "products": ["Faq 200", "Luna 4", "Bear", "UFO 3"]},
    {"folder": "DVX",            "name": "DVX",            "id": "dvx",            "products": ["Aurora", "NightStorm X1", "Core", "Lumen"]},
    {"folder": "Ember",          "name": "Ember",          "id": "ember",          "products": ["Travel Mug", "Cup²", "Tumbler"]},
    {"folder": "Bang & Olufsen", "name": "Bang & Olufsen", "id": "bang-olufsen",   "products": ["Beosound", "Beoplay", "Beolab"]},
    {"folder": "Anker",          "name": "Anker",          "id": "anker",          "products": ["PowerCore", "Soundcore", "Eufy"]},
]


def _slugify(name: str) -> str:
    """Stable url-safe id from a folder name."""
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return s or name.lower()


def discover_brands() -> list[dict]:
    """Walk DRIVE_ROOT for top-level subfolders and treat each as a brand.

    Folders matching SEED_BRANDS by name keep their curated metadata.
    Anything else gets a generated id + empty products list. Hidden
    folders, shortcut files, and the SONOS-duplicate `Sonos` folder
    (case variant of `SONOS`) are filtered out.
    """
    if not DRIVE_ROOT.is_dir():
        return []
    seed_by_folder = {b["folder"]: b for b in SEED_BRANDS}
    seed_by_lower  = {b["folder"].lower(): b for b in SEED_BRANDS}
    used_ids: set[str] = set()
    out: list[dict] = []
    for entry in sorted(DRIVE_ROOT.iterdir(), key=lambda p: p.name.lower()):
        if not entry.is_dir():
            continue
        name = entry.name
        if name.startswith(".") or name.startswith("_") or name.lower() == "old":
            continue
        # Drive sometimes leaves shortcut "files" with .lnk extensions which
        # iterdir treats as files anyway, but belt + braces.
        if name.endswith(".lnk"):
            continue
        # Folder name matches a seed exactly — use the curated record.
        seed = seed_by_folder.get(name)
        if seed and seed["id"] not in used_ids:
            out.append(seed)
            used_ids.add(seed["id"])
            continue
        # Folder is a different-case variant of a seed (e.g. "Sonos" vs
        # "SONOS") — skip the duplicate.
        if name.lower() in seed_by_lower and seed_by_lower[name.lower()]["id"] in used_ids:
            continue
        # Auto-discover.
        brand_id = _slugify(name)
        if brand_id in used_ids:
            continue
        used_ids.add(brand_id)
        out.append({
            "folder":   name,
            "name":     name,
            "id":       brand_id,
            "products": [],
        })
    return out


# Live computed list — read once per scan invocation.
BRANDS: list[dict] = []

# Tokens we always want stripped from titles, regardless of position.
# Store names are kept — they help differentiate variants (DVX-Selfridges vs DVX-KaDeWe).
NOISE_TOKENS = {
    "compressed", "h264", "muted", "subtitles",
    "16x9", "9x16", "1x1",
    "us", "uk", "eu", "en",  # language / region tags
}
NOISE_PATTERNS = [
    re.compile(r"^\d{3,4}x\d{3,4}$"),                # 1920x1080, 1536x1006
    re.compile(r"^v\d+$", re.IGNORECASE),             # V2, V3
    re.compile(r"^\d{8}$"),                           # 20251215
    re.compile(r"^\(\d+\)$"),                         # (1), (2)
    re.compile(r"^\(?muted\)?$", re.IGNORECASE),      # (muted), muted
    re.compile(r"^\d+s$", re.IGNORECASE),             # 33S, 53S — duration tags
]


def is_noise(token: str) -> bool:
    if not token:
        return True
    if token.lower() in NOISE_TOKENS:
        return True
    for pat in NOISE_PATTERNS:
        if pat.match(token):
            return True
    return False


# ─────────────────────────────────────────────────────────────────────
# MP4 / MOV atom parser — pure stdlib, no ffprobe.
#
# Walks the atom tree (ISO BMFF) until it finds a `tkhd` (track header)
# atom with non-zero dimensions. tkhd contains width/height as 16.16
# fixed-point. Audio tracks have width/height = 0, so we skip those.
# A 90° / 270° rotation matrix swaps the displayed dimensions, so we
# detect that and swap.
# ─────────────────────────────────────────────────────────────────────

# Container atoms whose body is a list of child atoms (we recurse into
# these). Anything not in this set is treated as opaque and skipped.
_CONTAINER_ATOMS = {
    "moov", "trak", "mdia", "minf", "stbl",
    "edts", "mvex", "udta", "moof", "traf",
}


_PROBE_ATOMS = {"tkhd", "mvhd"}


def _iter_atoms(f, end: int, wanted: set[str]):
    """Yield (type, body) for atoms whose type is in `wanted`, anywhere under the current scope."""
    while f.tell() < end:
        header = f.read(8)
        if len(header) < 8:
            return
        size, atom_type = struct.unpack(">I4s", header)
        body_offset = 8
        if size == 1:
            ext = f.read(8)
            if len(ext) < 8:
                return
            size = struct.unpack(">Q", ext)[0]
            body_offset = 16
        elif size == 0:
            size = end - (f.tell() - 8)

        atom_end = f.tell() - body_offset + size
        atom_type_str = atom_type.decode("ascii", errors="replace")

        if atom_type_str in _CONTAINER_ATOMS:
            yield from _iter_atoms(f, atom_end, wanted)
            f.seek(atom_end)
        elif atom_type_str in wanted:
            body = f.read(size - body_offset)
            yield (atom_type_str, body)
        else:
            f.seek(atom_end)


def _parse_tkhd(body: bytes) -> tuple[int, int] | None:
    """Read width/height from a tkhd body. Returns None on malformed input."""
    if len(body) < 4:
        return None
    version = body[0]
    # version(1) + flags(3) + (ctime + mtime + track_id + reserved + duration)
    if version == 0:
        offset = 4 + 4 + 4 + 4 + 4 + 4
    elif version == 1:
        offset = 4 + 8 + 8 + 4 + 4 + 8
    else:
        return None
    # reserved(8) + layer(2) + alt_group(2) + volume(2) + reserved(2) = 16
    offset += 16
    # Matrix (9 × 4 bytes = 36) — first 4 cells used for rotation detection.
    if len(body) < offset + 36 + 8:
        return None
    a, b, _u, c, d = struct.unpack(">5i", body[offset : offset + 20])
    offset += 36
    w_fixed, h_fixed = struct.unpack(">II", body[offset : offset + 8])
    width = w_fixed >> 16
    height = h_fixed >> 16
    # 90° / 270° rotation → swap the displayed dimensions.
    # Identity has a≈0x10000, d≈0x10000. Rotated has a==0 and (b or c) non-zero.
    if a == 0 and (b != 0 or c != 0):
        width, height = height, width
    return (width, height)


def _parse_mvhd(body: bytes) -> float | None:
    """Read overall movie duration in seconds from the mvhd body."""
    if len(body) < 4:
        return None
    version = body[0]
    # version(1) + flags(3) + ctime + mtime + timescale + duration
    if version == 0:
        if len(body) < 4 + 4 + 4 + 4 + 4:
            return None
        timescale = struct.unpack(">I", body[12:16])[0]
        duration = struct.unpack(">I", body[16:20])[0]
    elif version == 1:
        if len(body) < 4 + 8 + 8 + 4 + 8:
            return None
        timescale = struct.unpack(">I", body[20:24])[0]
        duration = struct.unpack(">Q", body[24:32])[0]
    else:
        return None
    if timescale == 0:
        return None
    return duration / timescale


def probe_mp4(path: Path) -> dict:
    """Return {width, height, durationSec} where extractable. Single file pass."""
    out: dict = {"width": None, "height": None, "durationSec": None}
    try:
        size = path.stat().st_size
        with open(path, "rb") as f:
            for atom_type, body in _iter_atoms(f, size, _PROBE_ATOMS):
                if atom_type == "tkhd" and out["width"] is None:
                    dims = _parse_tkhd(body)
                    if dims and dims[0] > 0 and dims[1] > 0:
                        out["width"], out["height"] = dims
                elif atom_type == "mvhd" and out["durationSec"] is None:
                    secs = _parse_mvhd(body)
                    if secs and secs > 0:
                        out["durationSec"] = round(secs, 1)
                if all(v is not None for v in out.values()):
                    break
    except Exception:
        pass
    return out


def humanise(stem: str) -> str:
    """Turn a filename stem into a presentable title."""
    # Normalise separators (incl. attached parens like "Compressed(1)") then split.
    s = re.sub(r"\(\d+\)", "", stem)               # drop "(1)" etc.
    s = re.sub(r"[\-_]+", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    tokens = [t for t in s.split(" ") if not is_noise(t)]
    if not tokens:
        return stem

    # Title-case, keeping all-caps proper nouns (SONOS, DVX, FAQ) intact.
    titled = []
    for t in tokens:
        if t.isupper() and len(t) >= 3:
            titled.append(t)
        elif re.fullmatch(r"\d+", t):
            titled.append(t)
        else:
            titled.append(t[0].upper() + t[1:].lower() if len(t) > 1 else t.upper())
    return " ".join(titled)


def detect_product(filename: str, brand_products: list[str]) -> str | None:
    lower = filename.lower()
    for product in brand_products:
        token = product.lower().split()[0]
        if token in lower:
            return product
    return None


def collect_videos() -> list[dict]:
    out: list[dict] = []
    skipped_dirs = ("/old/", "/archive/", "/_old/", "/raw/")
    total = len(BRANDS)
    for idx, brand in enumerate(BRANDS, 1):
        # Machine-readable progress line. serve.py parses these to populate
        # the Drive Sync card's live progress display. Format:
        #   PROGRESS: <i>/<n> <brand-name>
        # Anything after PROGRESS is opaque label text.
        print(f"PROGRESS: {idx}/{total} {brand['name']}", flush=True)
        folder = DRIVE_ROOT / brand["folder"]
        if not folder.exists():
            print(f"[skip] {brand['folder']} — folder not on drive")
            continue
        files: list[Path] = []
        for ext in ("*.mp4", "*.mov"):
            files += list(folder.rglob(ext))
        files = [f for f in files if not any(s in str(f).lower().replace("\\", "/") for s in skipped_dirs)]
        files.sort(key=lambda p: (p.parent.name, p.name))

        for idx, f in enumerate(files):
            stem = f.stem
            title = humanise(stem)
            product = detect_product(stem, brand["products"])
            size_mb = round(f.stat().st_size / (1024 * 1024), 1)
            video_id = f"{brand['id']}-{idx + 1}"
            # Synthetic but realistic counts for the "on N screens" badge.
            screens = (idx * 7 + 3) % 22
            # URL the dev server's /media route uses to stream this file.
            # Brand folder + filename are quoted so spaces/ampersands survive.
            media_url = (
                "/media/"
                + urllib.parse.quote(brand["folder"])
                + "/"
                + urllib.parse.quote(f.name)
            )
            probe = probe_mp4(f)
            duration_sec = probe["durationSec"]
            duration_label = (
                f"{int(duration_sec // 60)}:{int(duration_sec % 60):02d}"
                if duration_sec else None
            )
            out.append(
                {
                    "id": video_id,
                    "title": title,
                    "brand": brand["name"],
                    "product": product,
                    "duration": duration_label or "—",
                    "durationSec": duration_sec,
                    "screens": screens,
                    "sizeMb": size_mb,
                    "filename": f.name,
                    "mediaUrl": media_url,
                    "width": probe["width"],
                    "height": probe["height"],
                }
            )
    return out


def emit_jsx(videos: list[dict]) -> str:
    brand_records = []
    # Iterate in alphabetical order by display name (case-insensitive). Both
    # CMS and tablet read this list as-is, so this is the single source of
    # truth for brand ordering.
    for b in sorted(BRANDS, key=lambda x: x["name"].lower()):
        count = sum(1 for v in videos if v["brand"] == b["name"])
        if count == 0:
            continue
        brand_records.append({
            "id": b["id"],
            "name": b["name"],
            "videos": count,
            "products": b["products"],
        })

    body = (
        "/* eslint-disable */\n"
        "// AUTO-GENERATED by scan-videos.py — do not hand-edit.\n"
        "// Real videos pulled from the Smartech Drive. Loaded after data.jsx.\n"
        "//\n"
        "// Why mutate-in-place instead of reassigning?\n"
        "// `const MOCK_VIDEOS = [...]` in data.jsx is declared in the script's\n"
        "// lexical environment. Other scripts read it as a bare identifier — they\n"
        "// won't pick up `window.MOCK_VIDEOS = ...` because that doesn't shadow a\n"
        "// `const`. Splicing the existing arrays does work.\n\n"
        f"const REAL_VIDEOS = {json.dumps(videos, indent=2)};\n\n"
        f"const REAL_BRANDS = {json.dumps(brand_records, indent=2)};\n\n"
        "MOCK_VIDEOS.length = 0;\n"
        "REAL_VIDEOS.forEach((v) => MOCK_VIDEOS.push(v));\n\n"
        "MOCK_BRANDS.length = 0;\n"
        "REAL_BRANDS.forEach((b) => MOCK_BRANDS.push(b));\n\n"
        "// Mirror to window for any console-driven debugging.\n"
        "window.MOCK_VIDEOS = MOCK_VIDEOS;\n"
        "window.MOCK_BRANDS = MOCK_BRANDS;\n"
    )
    return body


def discover_brands_drive(root_id: str) -> list[dict]:
    """Drive-API equivalent of discover_brands().

    Lists direct subfolders of root_id and treats each as a brand. The
    `_drive_id` extra field is preserved so collect_videos_drive() can
    list files inside without a second lookup.
    """
    if drive_client is None:
        return []
    seed_by_folder = {b["folder"]: b for b in SEED_BRANDS}
    seed_by_lower  = {b["folder"].lower(): b for b in SEED_BRANDS}
    used_ids: set[str] = set()
    out: list[dict] = []
    folders = drive_client.list_subfolders(root_id)
    # Match the local mode's case-insensitive sort by name.
    folders.sort(key=lambda f: f["name"].lower())
    for folder in folders:
        name = folder["name"]
        if name.startswith(".") or name.startswith("_") or name.lower() == "old":
            continue
        seed = seed_by_folder.get(name)
        if seed and seed["id"] not in used_ids:
            out.append({**seed, "_drive_id": folder["id"]})
            used_ids.add(seed["id"])
            continue
        if name.lower() in seed_by_lower and seed_by_lower[name.lower()]["id"] in used_ids:
            continue
        brand_id = _slugify(name)
        if brand_id in used_ids:
            continue
        used_ids.add(brand_id)
        out.append({
            "folder":   name,
            "name":     name,
            "id":       brand_id,
            "products": [],
            "_drive_id": folder["id"],
        })
    return out


def collect_videos_drive() -> list[dict]:
    """Drive-API equivalent of collect_videos().

    Each brand record carries `_drive_id` from discover_brands_drive().
    For every MP4/MOV under that subtree we emit a library entry whose
    mediaUrl points at the Drive file ID — serve.py routes
    /media/<drive_file_id> through Drive's alt=media endpoint.

    No MP4 atom probe in cloud mode: width/height/duration are left null
    so we don't pay Drive egress for metadata. The CMS gracefully shows
    '—' for those fields when null.
    """
    if drive_client is None:
        return []
    out: list[dict] = []
    skipped_dirs = ("/old/", "/archive/", "/_old/", "/raw/")
    total = len(BRANDS)
    for idx, brand in enumerate(BRANDS, 1):
        # PROGRESS: tag is parsed by serve.py to drive the Drive Sync card.
        print(f"PROGRESS: {idx}/{total} {brand['name']}", flush=True)
        folder_id = brand.get("_drive_id")
        if not folder_id:
            continue
        try:
            files = drive_client.list_videos_recursive(folder_id)
        except Exception as e:
            print(f"[skip] {brand['folder']} — Drive API error: {e}")
            continue
        # Stable order so re-runs produce the same library.json.
        files.sort(key=lambda f: f.get("name", "").lower())

        for file_idx, f in enumerate(files):
            name = f.get("name", "")
            stem = Path(name).stem
            # Apply the same "_old", "raw" filename heuristic as filesystem
            # mode — though we already excluded those folders, defensive.
            joined_lower = name.lower()
            if any(s.strip("/") in joined_lower for s in skipped_dirs):
                continue

            title = humanise(stem)
            product = detect_product(stem, brand["products"])
            try:
                size_mb = round(int(f.get("size", "0") or 0) / (1024 * 1024), 1)
            except ValueError:
                size_mb = 0.0
            video_id = f"{brand['id']}-{file_idx + 1}"
            screens = (file_idx * 7 + 3) % 22
            # mediaUrl points at the Drive file ID. serve.py's
            # _serve_media detects that shape (no slash after /media/)
            # and routes to the Drive streaming path.
            media_url = "/media/" + urllib.parse.quote(f["id"])
            out.append({
                "id":          video_id,
                "title":       title,
                "brand":       brand["name"],
                "product":     product,
                "duration":    "—",
                "durationSec": None,
                "screens":     screens,
                "sizeMb":      size_mb,
                "filename":    name,
                "mediaUrl":    media_url,
                "width":       None,
                "height":      None,
            })
    return out


# ── v0.1.46 Phase 1: broad-query whole-drive sweep ──────────────────
#
# The pre-v0.1.46 cloud path made ~one Drive API call per brand folder
# (discover_brands_drive) plus one per nested subfolder of each brand
# (list_videos_recursive). Sequential and slow — 70+ round-trips on a
# typical seven-brand fleet, each ~200-400 ms from Cloud Run.
#
# This rewrite uses Drive's `corpora=drive` query mode to pull every
# folder + video file in the entire shared drive in a single paginated
# sweep, then attaches each video to its brand via the `parents` chain
# client-side. Same library.json output shape; ~5-10× faster wall-clock.
#
# Falls back to the old per-folder walker when the brand-content folder
# isn't inside a shared drive (My Drive content has no driveId).


def _has_skipped_ancestor(
    file_obj: dict,
    folder_by_id: dict,
    stop_at: str,
    skipped_names: set[str],
) -> bool:
    """Walk `file_obj` upward via parents until we hit `stop_at`; if any
    folder name along the way matches `skipped_names` (case-insensitive),
    return True. Mirrors the per-folder skip logic the old recursive
    walker applied via `name in {"old", "_old", ...}`."""
    cur = (file_obj.get("parents") or [None])[0]
    depth = 0
    while cur and cur != stop_at and depth < 30:
        f = folder_by_id.get(cur)
        if not f:
            return False
        if f.get("name", "").lower() in skipped_names:
            return True
        cur = (f.get("parents") or [None])[0]
        depth += 1
    return False


def _classify_inventory(brands_id: str, inventory: list[dict]) -> list[dict]:
    """Pure-function classifier — turns a list of `{id, name, mimeType,
    parents, size, ...}` Drive records into a list of library.json
    video entries, and populates the module-global BRANDS along the
    way. Used by both the broad-query path and the v0.1.47 incremental-
    apply path (which feeds in a patched cached inventory).
    """
    folders = [f for f in inventory if f.get("mimeType") == "application/vnd.google-apps.folder"]
    files = [f for f in inventory if f.get("mimeType") != "application/vnd.google-apps.folder"]
    folder_by_id: dict[str, dict] = {f["id"]: f for f in folders}
    print(f"[classify] {len(folders)} folders + {len(files)} videos in inventory", flush=True)

    # Direct children of BRANDS_ID = brand folders.
    brand_folders = [
        f for f in folders
        if brands_id in (f.get("parents") or [])
        and not f["name"].startswith(("_", "."))
        and f["name"].lower() != "old"
    ]
    brand_folders.sort(key=lambda f: f["name"].lower())

    seed_by_folder = {b["folder"]: b for b in SEED_BRANDS}
    seed_by_lower = {b["folder"].lower(): b for b in SEED_BRANDS}
    used_ids: set[str] = set()
    folder_to_brand: dict[str, dict] = {}
    for folder in brand_folders:
        name = folder["name"]
        seed = seed_by_folder.get(name)
        if seed and seed["id"] not in used_ids:
            rec = {**seed, "_drive_id": folder["id"]}
        elif name.lower() in seed_by_lower and seed_by_lower[name.lower()]["id"] in used_ids:
            continue
        else:
            brand_id = _slugify(name)
            if brand_id in used_ids:
                continue
            rec = {"folder": name, "name": name, "id": brand_id, "products": [], "_drive_id": folder["id"]}
        used_ids.add(rec["id"])
        BRANDS.append(rec)
        folder_to_brand[folder["id"]] = rec

    # For each video, walk up via parents until we hit a known brand
    # folder (i.e. a direct child of BRANDS_ID). The first such match
    # is the brand. Videos outside the brand subtree get dropped.
    def brand_for(video: dict) -> dict | None:
        cur = (video.get("parents") or [None])[0]
        depth = 0
        while cur and depth < 30:
            if cur in folder_to_brand:
                return folder_to_brand[cur]
            f = folder_by_id.get(cur)
            if not f:
                return None
            cur = (f.get("parents") or [None])[0]
            depth += 1
        return None

    skipped_names = {"old", "_old", "archive", "raw"}
    # Stable order — same key as v1 path so the rolling sticky-flag
    # diff stays meaningful: brand folder, then filename.
    files.sort(key=lambda v: (v.get("name", "").lower()))

    per_brand_count: dict[str, int] = {}
    out: list[dict] = []
    total = len(BRANDS)
    # Emit a single PROGRESS line per brand so serve.py's UI updates.
    # We don't have per-folder progress here — the whole inventory is
    # already in memory.
    for idx, brand in enumerate(BRANDS, 1):
        print(f"PROGRESS: {idx}/{total} {brand['name']}", flush=True)
    for vf in files:
        name = vf.get("name", "")
        if not (name.lower().endswith(".mp4") or name.lower().endswith(".mov")):
            continue
        brand_rec = brand_for(vf)
        if not brand_rec:
            continue
        if _has_skipped_ancestor(vf, folder_by_id, brand_rec["_drive_id"], skipped_names):
            continue
        try:
            size_mb = round(int(vf.get("size", "0") or 0) / (1024 * 1024), 1)
        except ValueError:
            size_mb = 0.0
        file_idx = per_brand_count.get(brand_rec["id"], 0)
        per_brand_count[brand_rec["id"]] = file_idx + 1
        screens = (file_idx * 7 + 3) % 22
        media_url = "/media/" + urllib.parse.quote(vf["id"])
        title = humanise(Path(name).stem)
        product = detect_product(Path(name).stem, brand_rec["products"])
        out.append({
            "id":          f"{brand_rec['id']}-{file_idx + 1}",
            "title":       title,
            "brand":       brand_rec["name"],
            "product":     product,
            "duration":    "—",
            "durationSec": None,
            "screens":     screens,
            "sizeMb":      size_mb,
            "filename":    name,
            "mediaUrl":    media_url,
            "width":       None,
            "height":      None,
        })
    return out


def collect_videos_drive_v2(brands_id: str) -> tuple[list[dict] | None, list[dict] | None]:
    """v0.1.46 broad-query entry point. Resolves the shared-drive id,
    pulls the full inventory in one paginated sweep, classifies. Returns
    `(videos, inventory)` so the caller can persist the raw inventory
    for the v0.1.47 incremental-apply path. Returns `(None, None)` when
    the content isn't in a shared drive — caller falls back to v1.
    """
    if drive_client is None:
        return (None, None)
    drive_id = drive_client.get_parent_drive_id(brands_id)
    if not drive_id:
        print("[broad-query] brand folder isn't in a shared drive — falling back to recursive walk", flush=True)
        return (None, None)
    print(f"[broad-query] fetching whole-drive inventory for drive {drive_id}", flush=True)
    inventory = drive_client.list_drive_inventory(drive_id)
    videos = _classify_inventory(brands_id, inventory)
    return (videos, inventory)


# ── v0.1.47 Phase 3: incremental change apply ───────────────────────
#
# After every successful full scan we persist the inventory list to
# `drive_inventory_snapshot.json`. On a subsequent scan where
# `changes.list` returns a manageable number of file IDs, we can skip
# the broad query entirely:
#
#   1. Concurrently `files.get` metadata for every changed ID.
#   2. Drop removed/trashed/inaccessible IDs from the snapshot.
#   3. Upsert metadata for the rest.
#   4. Re-classify against the patched snapshot.
#
# Cost scales with `num_changes`, not `inventory_size`. A typical
# "uploaded one new video" day becomes 1 changes.list + 1 files.get
# round-trip instead of a 1-3 s broad sweep.
#
# Anything that fails along the way (snapshot missing, drive id
# mismatch, too many changes, parallel fetch error) falls through to
# the broad-query rebuild. Belt-and-braces — the fast path is purely
# an optimization on top of the v0.1.46 baseline.


def _load_inventory_snapshot() -> tuple[str | None, list[dict] | None]:
    """Read the persisted inventory snapshot. Returns (driveId,
    inventory) or (None, None) if absent / unreadable."""
    if not INVENTORY_SNAPSHOT_PATH.is_file():
        return (None, None)
    try:
        data = json.loads(INVENTORY_SNAPSHOT_PATH.read_text(encoding="utf-8"))
        items = data.get("items")
        if not isinstance(items, list):
            return (None, None)
        return (data.get("driveId"), items)
    except Exception as e:
        print(f"[snapshot] couldn't read snapshot: {e}", flush=True)
        return (None, None)


def _save_inventory_snapshot(drive_id: str, inventory: list[dict]) -> None:
    """Atomic write of the inventory snapshot. Trims to just the
    fields the incremental-apply path needs so the file stays small
    (~100-300 KB for a few-thousand-file drive)."""
    try:
        trimmed = [
            {
                "id":           f.get("id"),
                "name":         f.get("name"),
                "mimeType":     f.get("mimeType"),
                "parents":      f.get("parents"),
                "size":         f.get("size"),
                "modifiedTime": f.get("modifiedTime"),
            }
            for f in inventory
            if f.get("id")
        ]
        INVENTORY_SNAPSHOT_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = INVENTORY_SNAPSHOT_PATH.with_suffix(INVENTORY_SNAPSHOT_PATH.suffix + ".tmp")
        tmp.write_text(
            json.dumps({"driveId": drive_id, "items": trimmed}, indent=2),
            encoding="utf-8",
        )
        tmp.replace(INVENTORY_SNAPSHOT_PATH)
    except Exception as e:
        print(f"[snapshot] couldn't write snapshot: {e}", flush=True)


def try_incremental_apply(
    brands_id: str,
    drive_id: str,
    changes: list[dict],
) -> tuple[list[dict] | None, list[dict] | None]:
    """Attempt the v0.1.47 fast path: patch the cached snapshot with
    the changed file IDs and reclassify. Returns `(videos, patched_inventory)`
    on success, `(None, None)` if we should fall through to broad query.
    """
    if drive_client is None or not changes:
        return (None, None)
    if len(changes) > INCREMENTAL_CHANGE_THRESHOLD:
        print(f"[incremental] {len(changes)} changes > threshold {INCREMENTAL_CHANGE_THRESHOLD} — falling through to broad query", flush=True)
        return (None, None)
    cached_drive, cached_inventory = _load_inventory_snapshot()
    if cached_drive != drive_id or not cached_inventory:
        print("[incremental] snapshot missing or drive mismatch — falling through to broad query", flush=True)
        return (None, None)

    # Fetch metadata for everything that changed, in parallel. We
    # need this regardless of `removed=True` because Drive sometimes
    # flips removed→reachable when an ACL changes back.
    changed_ids = [c["fileId"] for c in changes]
    print(f"[incremental] fetching metadata for {len(changed_ids)} changed file(s) in parallel", flush=True)
    try:
        metadata = drive_client.fetch_files_metadata(changed_ids)
    except Exception as e:
        print(f"[incremental] parallel metadata fetch failed ({e}); falling through to broad query", flush=True)
        return (None, None)

    # Patch the snapshot. Build an id-keyed index of the current
    # inventory, then apply additions/removals/updates in place.
    by_id: dict[str, dict] = {f["id"]: f for f in cached_inventory if f.get("id")}
    removed_count = 0
    added_count = 0
    updated_count = 0
    for fid in changed_ids:
        meta = metadata.get(fid)
        if meta is None:
            # Inaccessible (deleted, trashed, ACL lost). Drop if present.
            if fid in by_id:
                del by_id[fid]
                removed_count += 1
            continue
        # We only track folders + video files in the snapshot. If a
        # changed file is something else (a .txt, an image, etc.),
        # skip the upsert — the broad-query inventory wouldn't have
        # included it either.
        mt = meta.get("mimeType", "")
        name = (meta.get("name") or "").lower()
        is_folder = mt == "application/vnd.google-apps.folder"
        is_video = ("video/" in mt) or name.endswith(".mp4") or name.endswith(".mov")
        if not (is_folder or is_video):
            if fid in by_id:
                del by_id[fid]
                removed_count += 1
            continue
        if fid in by_id:
            updated_count += 1
        else:
            added_count += 1
        by_id[fid] = meta

    patched = list(by_id.values())
    print(
        f"[incremental] snapshot patched — +{added_count} added, "
        f"~{updated_count} updated, -{removed_count} removed; "
        f"new size {len(patched)} items",
        flush=True,
    )
    videos = _classify_inventory(brands_id, patched)
    return (videos, patched)


def _load_token() -> tuple[str | None, str | None]:
    """Read the persisted change-token. Returns (driveId, token) or
    (None, None) if absent / unreadable."""
    if not DRIVE_TOKEN_PATH.is_file():
        return (None, None)
    try:
        data = json.loads(DRIVE_TOKEN_PATH.read_text(encoding="utf-8"))
        return (data.get("driveId"), data.get("token"))
    except Exception as e:
        print(f"[token] couldn't read token file: {e}", flush=True)
        return (None, None)


def _save_token(drive_id: str, token: str) -> None:
    """Atomic write of {driveId, token} to the cursor file."""
    try:
        DRIVE_TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = DRIVE_TOKEN_PATH.with_suffix(DRIVE_TOKEN_PATH.suffix + ".tmp")
        tmp.write_text(json.dumps({"driveId": drive_id, "token": token}, indent=2), encoding="utf-8")
        tmp.replace(DRIVE_TOKEN_PATH)
    except Exception as e:
        print(f"[token] couldn't write token file: {e}", flush=True)


def main():
    # Replace the placeholder list with real folder discovery on every run.
    BRANDS.clear()

    # Preserve per-video flags the CMS may have set on a prior scan
    # (e.g. defaultUnmute, set via PATCH /api/library/videos/<id>).
    # Without this, the next scan-videos.py run wipes user choices.
    sticky_flags: dict[str, dict] = {}
    if LIBRARY_JSON.is_file():
        try:
            prev = json.loads(LIBRARY_JSON.read_text(encoding="utf-8"))
            for v in prev.get("videos") or []:
                vid = v.get("id")
                if not vid:
                    continue
                kept: dict = {}
                if v.get("defaultUnmute"):
                    kept["defaultUnmute"] = True
                if kept:
                    sticky_flags[vid] = kept
        except Exception as e:
            print(f"Couldn't read previous library.json for sticky flags: {e}", flush=True)

    cloud_mode = bool(DRIVE_BRANDS_FOLDER_ID and drive_client and drive_client.is_configured())
    if cloud_mode:
        print(f"Cloud mode: scanning Drive folder {DRIVE_BRANDS_FOLDER_ID}", flush=True)
        # v0.1.46 Phase 2: change-token short-circuit. If we already
        # have a cursor AND the resolved drive id matches what's
        # persisted AND Drive reports zero changes since the cursor,
        # skip the scan entirely. `library.json` stays as-is — the
        # last successful scan's output is still authoritative.
        force_full = os.environ.get("SCREENS_FORCE_FULL_SCAN") == "1"
        try:
            drive_id_for_token = drive_client.get_parent_drive_id(DRIVE_BRANDS_FOLDER_ID)
        except Exception as e:
            drive_id_for_token = None
            print(f"[token] couldn't resolve drive id: {e}", flush=True)
        cached_drive, cached_token = _load_token()
        # v0.1.47: hold onto the changes list (not just count) so the
        # incremental-apply path below can use it. None means "we
        # didn't run changes.list" (forced full, or no cursor yet).
        changes_to_apply: list[dict] | None = None
        if (
            not force_full
            and drive_id_for_token
            and cached_drive == drive_id_for_token
            and cached_token
        ):
            try:
                changes, next_token = drive_client.changes_since(
                    drive_id_for_token, cached_token,
                )
                if not changes:
                    print("[token] no Drive changes since last cursor — skipping full scan", flush=True)
                    # Persist the same token explicitly so the next call
                    # starts from the same anchor; cheap and avoids any
                    # weird "token expired" edge cases later.
                    _save_token(drive_id_for_token, next_token)
                    return
                print(f"[token] {len(changes)} change(s) since last scan", flush=True)
                changes_to_apply = changes
            except Exception as e:
                print(f"[token] changes.list failed ({e}); falling through to full scan", flush=True)

        # v0.1.47 Phase 3: incremental apply when we have a usable
        # change list. Patches the cached inventory in place via
        # parallel files.get for the changed IDs only. Falls through
        # to the broad query on any failure or when too many changes
        # have piled up to make this worthwhile.
        videos: list[dict] | None = None
        used_inventory: list[dict] | None = None
        if changes_to_apply and drive_id_for_token:
            videos, used_inventory = try_incremental_apply(
                DRIVE_BRANDS_FOLDER_ID, drive_id_for_token, changes_to_apply,
            )
            if videos is not None:
                print(f"[incremental] fast path succeeded — {len(videos)} videos via patched snapshot", flush=True)

        # Broad-query rebuild path. Used when there's no cached
        # snapshot, the changes list is too big, or the incremental
        # apply opted out.
        if videos is None:
            videos, used_inventory = collect_videos_drive_v2(DRIVE_BRANDS_FOLDER_ID)
            if videos is None:
                print("[broad-query] falling back to recursive walker", flush=True)
                BRANDS.extend(discover_brands_drive(DRIVE_BRANDS_FOLDER_ID))
                print(f"Discovered {len(BRANDS)} brand folders via Drive API")
                videos = collect_videos_drive()
                # Legacy walker doesn't build an inventory — leave
                # used_inventory as None so we skip snapshot save.
            else:
                print(f"Discovered {len(BRANDS)} brand folders via broad-query inventory")

        # v0.1.47: persist the inventory snapshot so the next scan's
        # incremental-apply path can reuse it. Only when we have one
        # (the legacy recursive walker doesn't build one).
        if drive_id_for_token and used_inventory is not None:
            _save_inventory_snapshot(drive_id_for_token, used_inventory)

        # After a successful scan, capture a fresh cursor pointing at
        # "now" so the next tick can short-circuit if nothing changes
        # in the meantime.
        if drive_id_for_token:
            try:
                fresh_token = drive_client.get_start_page_token(drive_id_for_token)
                _save_token(drive_id_for_token, fresh_token)
                print(f"[token] persisted new start-page-token for drive {drive_id_for_token}", flush=True)
            except Exception as e:
                print(f"[token] couldn't fetch start-page-token: {e}", flush=True)
    else:
        BRANDS.extend(discover_brands())
        print(f"Discovered {len(BRANDS)} brand folders under {DRIVE_ROOT}")
        videos = collect_videos()
    print(f"Collected {len(videos)} videos across {len({v['brand'] for v in videos})} brands")

    # Re-apply the sticky flags from the previous scan.
    if sticky_flags:
        preserved = 0
        for v in videos:
            kept = sticky_flags.get(v.get("id"))
            if kept:
                for key, val in kept.items():
                    v[key] = val
                preserved += 1
        print(f"Preserved {preserved} per-video flag(s) from previous library.json")
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(emit_jsx(videos), encoding="utf-8")
    print(f"Wrote {OUT_FILE}")

    # Library JSON for serve.py /api/library — consumed by the player's staff
    # overlay so it can list brands and videos pulled from this same scan.
    # Same alphabetical ordering as the JSX list.
    brand_records = []
    for b in sorted(BRANDS, key=lambda x: x["name"].lower()):
        count = sum(1 for v in videos if v["brand"] == b["name"])
        if count == 0:
            continue
        brand_records.append({
            "id": b["id"],
            "name": b["name"],
            "videos": count,
            "products": b["products"],
        })
    library = {"brands": brand_records, "videos": videos}
    # Atomic write: stage to a temp file in the same dir, then rename.
    # On gcsfuse this is a copy + delete (not POSIX-atomic) but
    # crucially a partial-write reader sees the *old* library.json,
    # not a half-written one. Important for the CMS poller that hits
    # /api/library every 10s.
    LIBRARY_JSON.parent.mkdir(parents=True, exist_ok=True)
    tmp = LIBRARY_JSON.with_suffix(LIBRARY_JSON.suffix + ".tmp")
    tmp.write_text(json.dumps(library, indent=2), encoding="utf-8")
    tmp.replace(LIBRARY_JSON)
    print(f"Wrote {LIBRARY_JSON}")


if __name__ == "__main__":
    main()
