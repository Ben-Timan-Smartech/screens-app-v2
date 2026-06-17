"""
Drive API client used by serve.py and scan-videos.py when running in cloud
mode.

Cloud mode is detected per-call: callers check `is_configured()`. If true,
the module is wired up to a service-account-authenticated Drive client and
both scan + media streaming go through Drive. If false, callers fall back
to the local filesystem (Brand Content/ on G:\\).

What "configured" means:
  • GOOGLE_APPLICATION_CREDENTIALS env var points at a service-account
    JSON key file. (Cloud Run mounts a Secret here; locally you'd point
    at a file you downloaded from GCP Console.)
  • At least one of SCREENS_DRIVE_BRANDS_ID / SCREENS_DRIVE_SPLASHES_ID
    is set — the Drive folder ID(s) the service account has been granted
    Viewer access to.

Imports of `googleapiclient` and `google.auth` are deferred until the
first real call so a laptop without those packages installed can still
run serve.py in local mode without errors.
"""

from __future__ import annotations

import os
import re
import threading
from typing import Iterator, Optional

# Drive API URL/regex for sniffing whether a `/media/<...>` URL path is a
# Drive file ID vs. a filesystem path. Drive file IDs are typically a long
# string of letters/digits/hyphens/underscores with no slashes. We use
# this to route requests in serve.py without keeping mode state per-route.
DRIVE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{20,}$")


def is_configured() -> bool:
    """True if the env says we should talk to Drive at all."""
    return bool(
        os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
        and (
            os.environ.get("SCREENS_DRIVE_BRANDS_ID")
            or os.environ.get("SCREENS_DRIVE_SPLASHES_ID")
        )
    )


def looks_like_drive_id(path_segment: str) -> bool:
    """Heuristic: does this URL segment look like a Drive file ID?

    Used by serve.py to decide whether `/media/<x>` should hit Drive
    (cloud mode) or the filesystem (local mode). Drive IDs are long
    random tokens with no slashes; filesystem paths inside /media/ have
    a brand-folder/file.mp4 shape. Tested by checking for absence of
    path-separator characters and presence of typical ID alphabet.
    """
    return bool(DRIVE_ID_RE.match(path_segment))


# Module-level service singleton — Drive's google-api-python-client is
# thread-safe per-instance for read-only ops, so we share one. Lazy-init
# under a lock to avoid double-build on first concurrent request.
_service = None
_service_lock = threading.Lock()


def _get_service():
    """Lazily build and cache the Drive v3 service client.

    Errors fast if called when env isn't configured — callers should
    gate with is_configured() first.
    """
    global _service
    if _service is not None:
        return _service
    with _service_lock:
        if _service is not None:
            return _service
        # Imports deferred so local dev without google libs installed
        # doesn't crash importing this module.
        from google.oauth2 import service_account
        from googleapiclient.discovery import build

        creds_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
        if not creds_path or not os.path.isfile(creds_path):
            raise RuntimeError(
                f"GOOGLE_APPLICATION_CREDENTIALS not set or file missing: {creds_path!r}"
            )
        creds = service_account.Credentials.from_service_account_file(
            creds_path,
            scopes=["https://www.googleapis.com/auth/drive.readonly"],
        )
        # cache_discovery=False suppresses a noisy warning about the
        # built-in disk cache being unwritable in container environments.
        _service = build("drive", "v3", credentials=creds, cache_discovery=False)
        return _service


# ── Listing ──────────────────────────────────────────────────────────

def list_subfolders(parent_id: str) -> list[dict]:
    """Direct subfolders of `parent_id`. Returns list of {id, name}.

    Used by scan-videos.py to enumerate brand folders. Doesn't recurse
    — recursion happens in list_videos_recursive().
    """
    svc = _get_service()
    out: list[dict] = []
    page_token: Optional[str] = None
    while True:
        # `mimeType='application/vnd.google-apps.folder'` filters to
        # folders only. trashed=false skips items in the bin.
        # supportsAllDrives + includeItemsFromAllDrives are required when
        # the parent folder lives in a shared drive (which "Smartech /
        # Screens" does).
        resp = svc.files().list(
            q=(
                f"'{parent_id}' in parents and "
                f"mimeType='application/vnd.google-apps.folder' and "
                f"trashed=false"
            ),
            fields="nextPageToken, files(id,name)",
            pageSize=200,
            pageToken=page_token,
            supportsAllDrives=True,
            includeItemsFromAllDrives=True,
        ).execute()
        out.extend(resp.get("files", []))
        page_token = resp.get("nextPageToken")
        if not page_token:
            break
    return out


def list_videos_recursive(folder_id: str) -> list[dict]:
    """All MP4/MOV files under `folder_id` (recursive).

    Returns list of {id, name, size, parents}. Walks the folder tree
    breadth-first so we don't blow the stack on huge libraries.
    """
    svc = _get_service()
    out: list[dict] = []
    queue: list[str] = [folder_id]
    while queue:
        current = queue.pop(0)
        # Pull both video files and any nested folders in one pass.
        page_token: Optional[str] = None
        while True:
            resp = svc.files().list(
                q=(
                    f"'{current}' in parents and "
                    f"trashed=false and "
                    f"(mimeType='application/vnd.google-apps.folder' or "
                    f"mimeType contains 'video/')"
                ),
                fields="nextPageToken, files(id,name,size,mimeType,parents)",
                pageSize=500,
                pageToken=page_token,
                supportsAllDrives=True,
                includeItemsFromAllDrives=True,
            ).execute()
            for f in resp.get("files", []):
                if f.get("mimeType") == "application/vnd.google-apps.folder":
                    # Skip "old" / "_old" / "archive" / "raw" folders to
                    # match scan-videos.py's filesystem-side filter.
                    name = f.get("name", "").lower()
                    if name in {"old", "_old", "archive", "raw"}:
                        continue
                    queue.append(f["id"])
                else:
                    # Only keep mp4 / mov by extension (Drive sometimes
                    # tags weird mimetypes for mov files).
                    n = f.get("name", "").lower()
                    if n.endswith(".mp4") or n.endswith(".mov"):
                        out.append(f)
            page_token = resp.get("nextPageToken")
            if not page_token:
                break
    return out


def list_files_in(folder_id: str) -> list[dict]:
    """Direct video files in folder_id (no recursion). Used for splashes."""
    svc = _get_service()
    out: list[dict] = []
    page_token: Optional[str] = None
    while True:
        resp = svc.files().list(
            q=(
                f"'{folder_id}' in parents and "
                f"trashed=false and "
                f"(mimeType contains 'video/' or "
                f"mimeType='application/vnd.google-apps.folder')"
            ),
            fields="nextPageToken, files(id,name,size,mimeType)",
            pageSize=200,
            pageToken=page_token,
            supportsAllDrives=True,
            includeItemsFromAllDrives=True,
        ).execute()
        out.extend(resp.get("files", []))
        page_token = resp.get("nextPageToken")
        if not page_token:
            break
    return out


# ── Whole-drive broad query (v0.1.46 Phase 1) ──────────────────────
#
# The recursive `list_subfolders` + `list_videos_recursive` pair above is
# correct but slow: every folder costs its own paginated `files.list`
# call (~200-400 ms latency from Cloud Run to Drive), and we walked them
# sequentially across all seven+ brand subtrees. A typical sync was 70+
# round-trips.
#
# When the brand content lives on a shared drive (Smartech's case), we
# can fetch the entire inventory in O(filecount / 1000) calls using
# `corpora='drive'` + `driveId=<shared-drive-id>`. Two queries:
# `is_shared_drive_root` resolves the drive ID from the brand-content
# folder ID; `list_drive_inventory` then sweeps the whole drive.
#
# Callers reconstruct ancestry client-side via the `parents` field on
# each item. Folders that fall outside the brand-content subtree are
# filtered out by scan-videos.py.

def get_parent_drive_id(file_id: str) -> Optional[str]:
    """Return the shared-drive ID that `file_id` lives in, or None if
    it's in My Drive / no drive. Used to find the parent shared-drive
    of the brand-content folder so we can scope a broad query."""
    svc = _get_service()
    meta = svc.files().get(
        fileId=file_id,
        fields="driveId",
        supportsAllDrives=True,
    ).execute()
    return meta.get("driveId")


def list_drive_inventory(drive_id: str) -> list[dict]:
    """Every folder + video file in a shared drive, in one paginated
    sweep. Returns a list of `{id, name, mimeType, parents, size,
    modifiedTime}` records.

    Page size is 1000 — Drive's max for `files.list`. A drive with 5k
    items is ~5 calls / ~1 s, vs ~70+ calls for the per-folder walk.
    """
    svc = _get_service()
    out: list[dict] = []
    page_token: Optional[str] = None
    while True:
        resp = svc.files().list(
            corpora="drive",
            driveId=drive_id,
            q=(
                "trashed=false and "
                "(mimeType='application/vnd.google-apps.folder' or "
                "mimeType contains 'video/')"
            ),
            fields="nextPageToken, files(id,name,mimeType,parents,size,modifiedTime)",
            pageSize=1000,
            pageToken=page_token,
            supportsAllDrives=True,
            includeItemsFromAllDrives=True,
        ).execute()
        out.extend(resp.get("files", []))
        page_token = resp.get("nextPageToken")
        if not page_token:
            break
    return out


# ── Change-token short-circuit (v0.1.46 Phase 2) ───────────────────
#
# Drive's `changes.list` API returns a delta stream since a cursor. We
# don't try to apply deltas precisely (would need to re-classify each
# changed file into a brand, handle moves/renames/deletes correctly —
# all error-prone). We use the cursor for one thing only: detecting "is
# anything new since last sync?" If yes, run the full broad-query scan.
# If no, exit early without touching library.json.
#
# Most days nothing has changed in the brand content folder, so most
# Sync now / auto-sync ticks become a single API round-trip and an
# early-out. The wall-clock drops from minutes to ~1 second.

def get_start_page_token(drive_id: str) -> str:
    """Return a fresh cursor pointing at "now". Subsequent
    `changes_since(...)` calls will return everything that happened
    after this point. Used right after a full scan to seed the
    incremental cursor."""
    svc = _get_service()
    resp = svc.changes().getStartPageToken(
        driveId=drive_id,
        supportsAllDrives=True,
    ).execute()
    return resp.get("startPageToken", "")


def changes_since(drive_id: str, page_token: str) -> tuple[list[dict], str]:
    """Walk all change pages since `page_token` and return
    `(changes, next_token)`.

    Each change is `{fileId, removed}`. `removed=True` means the file
    is gone (deleted, trashed, or lost ACL visibility — the loss
    cases are gated by `includeCorpusRemovals=True`). `next_token` is
    the cursor to persist for the next call.
    """
    svc = _get_service()
    changes: list[dict] = []
    token = page_token
    while True:
        resp = svc.changes().list(
            pageToken=token,
            driveId=drive_id,
            includeRemoved=True,
            includeItemsFromAllDrives=True,
            supportsAllDrives=True,
            spaces="drive",
            includeCorpusRemovals=True,
            fields="nextPageToken, newStartPageToken, changes(fileId,removed)",
            pageSize=1000,
        ).execute()
        for c in resp.get("changes", []) or []:
            fid = c.get("fileId")
            if not fid:
                continue
            changes.append({"fileId": fid, "removed": bool(c.get("removed"))})
        if resp.get("nextPageToken"):
            token = resp["nextPageToken"]
            continue
        return (changes, resp.get("newStartPageToken", token))


def fetch_files_metadata(file_ids: list[str], max_workers: int = 8) -> dict[str, dict | None]:
    """Concurrent `files.get` for a list of file IDs.

    Returns `{file_id: metadata-or-None}`. `None` means the file is
    inaccessible (deleted, ACL change, transient API error). Used by
    the v0.1.47 incremental-apply path to fetch metadata for just the
    handful of files that `changes.list` flagged, instead of re-running
    the whole broad-query inventory.

    The fields match what `list_drive_inventory` returns so callers
    can drop these straight into the cached inventory snapshot.
    """
    svc = _get_service()
    from concurrent.futures import ThreadPoolExecutor

    def _one(fid: str) -> tuple[str, dict | None]:
        try:
            meta = svc.files().get(
                fileId=fid,
                fields="id,name,mimeType,parents,size,modifiedTime,trashed",
                supportsAllDrives=True,
            ).execute()
            if meta.get("trashed"):
                # Treat trashed-but-not-removed identically to removed
                # from our caller's perspective — drop from cache.
                return (fid, None)
            return (fid, meta)
        except Exception:
            # 404, 403 (permission), 5xx — caller treats as None.
            return (fid, None)

    out: dict[str, dict | None] = {}
    if not file_ids:
        return out
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        for fid, meta in pool.map(_one, file_ids):
            out[fid] = meta
    return out


# ── Streaming ────────────────────────────────────────────────────────

def get_metadata(file_id: str) -> dict:
    """Fetch {name, size, mimeType} for a file. Used to set Content-Type
    + Content-Length on /media/ responses."""
    svc = _get_service()
    return svc.files().get(
        fileId=file_id,
        fields="id,name,size,mimeType",
        supportsAllDrives=True,
    ).execute()


# Cached service-account credentials for the streaming path. The
# google-api-python-client Service object holds its own credentials,
# but we need raw access to the Bearer token for urllib calls — see
# stream_file's docstring. Refreshed on demand.
_streaming_creds = None
_streaming_creds_lock = threading.Lock()


def _streaming_token() -> str:
    """Return a valid Bearer token for Drive read access. Refreshes
    silently when the cached token is near expiry."""
    global _streaming_creds
    from google.oauth2 import service_account
    from google.auth.transport import requests as ga_requests

    with _streaming_creds_lock:
        if _streaming_creds is None:
            creds_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
            if not creds_path or not os.path.isfile(creds_path):
                raise RuntimeError(
                    f"GOOGLE_APPLICATION_CREDENTIALS not set or file missing: {creds_path!r}"
                )
            _streaming_creds = service_account.Credentials.from_service_account_file(
                creds_path,
                scopes=["https://www.googleapis.com/auth/drive.readonly"],
            )
        if not _streaming_creds.valid:
            _streaming_creds.refresh(ga_requests.Request())
        return _streaming_creds.token


def stream_file(
    file_id: str,
    range_header: Optional[str] = None,
    chunk_size: int = 1 << 20,
) -> Iterator[tuple[int, int, int, bytes]]:
    """Yield (status, start, end, chunk) for a file download.

    The original implementation called the google-api-python-client
    HTTP wrapper (`svc._http.request()`), which under the hood uses
    httplib2 — and httplib2 *buffers the entire response body into
    memory before returning*. For a 50 MB video that's 5–30 seconds of
    silence on the wire, well past the tablet's 10-second OkHttp read
    timeout. Result: tablet drops the connection, we send a 200 header
    with no body, player thinks the download truncated, retries, loop.

    This version bypasses google-api-python-client for the media
    download and talks to Drive's REST endpoint directly via urllib.
    urllib streams from the socket as you call `.read(n)`, so the
    first chunk reaches the wire within milliseconds of the connection
    opening. Auth comes from a cached service-account token — same
    credentials as the rest of the module.

    Range headers are forwarded verbatim, so HTML5 `<video>` seeking
    keeps working.
    """
    import urllib.request

    token = _streaming_token()
    url = (
        f"https://www.googleapis.com/drive/v3/files/{file_id}"
        f"?alt=media&supportsAllDrives=true"
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "User-Agent": "screens-app-v2-drive-client",
    }
    if range_header:
        headers["Range"] = range_header

    req = urllib.request.Request(url, headers=headers)
    # Long timeout: connection-establish only. Once we're streaming,
    # Python's HTTPResponse handles long reads cleanly.
    resp = urllib.request.urlopen(req, timeout=30)
    try:
        status = int(resp.status)
        cr = resp.headers.get("Content-Range")
        if cr and "bytes " in cr:
            try:
                spec = cr.split(" ", 1)[1].split("/", 1)[0]
                start_s, end_s = spec.split("-", 1)
                start, end = int(start_s), int(end_s)
            except Exception:
                start, end = 0, 0
        else:
            start = 0
            cl = resp.headers.get("Content-Length")
            end = (int(cl) - 1) if cl else 0

        offset = start
        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            yield status, offset, offset + len(chunk) - 1, chunk
            offset += len(chunk)
    finally:
        resp.close()
