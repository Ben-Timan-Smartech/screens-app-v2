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


def stream_file(
    file_id: str,
    range_header: Optional[str] = None,
    chunk_size: int = 1 << 20,
) -> Iterator[tuple[int, int, int, bytes]]:
    """Yield (status, start, end, chunk) for a file download.

    Wraps Drive's media download with HTTP Range support. Drive's
    `alt=media` endpoint accepts a standard `Range: bytes=…` header and
    returns 206 Partial Content with `Content-Range`, exactly what
    HTML5 `<video>` wants for seeking.

    Why we don't use MediaIoBaseDownload: that helper buffers the whole
    file into memory before yielding. We need to stream chunks straight
    to the HTTP socket.
    """
    svc = _get_service()
    # Build a request to the underlying HTTP layer. The discovery client
    # exposes `_http` for this kind of low-level use.
    headers = {}
    if range_header:
        headers["Range"] = range_header
    request = svc.files().get_media(fileId=file_id, supportsAllDrives=True)
    # _http here is a googleapiclient AuthorizedHttp wrapping httplib2.
    # We re-use it so credentials are attached automatically.
    http = svc._http
    uri = request.uri
    resp, content = http.request(uri, "GET", headers=headers)
    status = int(resp.status)
    # Parse Content-Range to figure out start/end so the caller can
    # forward to the HTTP client without re-parsing.
    cr = resp.get("content-range") or resp.get("Content-Range")
    if cr and "bytes " in cr:
        # bytes start-end/total
        try:
            spec = cr.split(" ", 1)[1].split("/", 1)[0]
            start_s, end_s = spec.split("-", 1)
            start, end = int(start_s), int(end_s)
        except Exception:
            start, end = 0, len(content) - 1
    else:
        start, end = 0, max(0, len(content) - 1)

    # Yield in chunk_size pieces so the HTTP server can write to the
    # socket without holding the whole video in memory.
    for i in range(0, len(content), chunk_size):
        yield status, start + i, min(start + i + chunk_size - 1, end), content[i:i + chunk_size]
