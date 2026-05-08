"""
Tiny persistence layer.

Both users AND sessions are persisted to a single JSON file at
$SCREENS_USERS_PATH (defaults to /data/users.json on Cloud Run,
./users.json locally).

Every read re-loads the file from disk; every write read-modify-writes
under a process-level lock and atomically replaces the file via
write-temp-then-rename. We deliberately do NOT keep a long-lived
in-memory cache: with Cloud Run rolling revisions and (occasionally)
serving from more than one container instance during deploys, a cache
populated at startup can miss writes made by a sibling container, and
the user bounces back to the login screen with a "valid" cookie whose
session is absent from the local cache. Re-reading on every call costs
a few KB of disk I/O per request — fine at our scale, and FUSE caches
the bytes locally between reads.

Why not SQLite: tried. Cloud Storage FUSE doesn't honour SQLite's
locking + journal lifecycle reliably. JSON write-temp-then-rename is
the FUSE-safe pattern.

Concurrency: the HTTP server is multi-threaded. All access is
serialised under a module-level RLock so the read-modify-write of the
JSON file can't tear.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path


def _default_users_path() -> str:
    explicit = os.environ.get("SCREENS_USERS_PATH")
    if explicit:
        return explicit
    legacy = os.environ.get("SCREENS_DB_PATH")
    if legacy:
        return str(Path(legacy).parent / "users.json")
    return str(Path(__file__).resolve().parent / "users.json")


USERS_PATH = Path(_default_users_path())

_lock = threading.RLock()


# ── Disk I/O ────────────────────────────────────────────────────────

def _empty() -> dict:
    return {"users": [], "sessions": {}}


def _load_data() -> dict:
    """Read the JSON file fresh on every call. Returns a dict with
    "users" (list) and "sessions" (dict) keys, both always present.
    Corrupt / missing files are treated as empty."""
    if not USERS_PATH.exists():
        return _empty()
    try:
        raw = json.loads(USERS_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return _empty()
    if not isinstance(raw, dict):
        return _empty()
    users = raw.get("users")
    sessions = raw.get("sessions")
    return {
        "users":    list(users) if isinstance(users, list) else [],
        "sessions": dict(sessions) if isinstance(sessions, dict) else {},
    }


def _save_data(data: dict) -> None:
    """Atomically replace the file. Caller must hold _lock."""
    USERS_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(data, indent=2)
    tmp = USERS_PATH.with_suffix(USERS_PATH.suffix + ".tmp")
    tmp.write_text(payload, encoding="utf-8")
    tmp.replace(USERS_PATH)


def init() -> None:
    """No-op. Kept for backwards compatibility with the old SQLite-era
    callers (auth.py imports db, db.init() used to apply migrations).
    The file is created on first write; first read of a missing file
    returns the empty shape."""
    pass


def _sessions_count_for_debug() -> int:
    """Read disk and return how many sessions currently exist. Used by
    auth.py log lines so we can tell whether writes are landing.
    Don't call this from hot paths."""
    try:
        return len(_load_data().get("sessions", {}))
    except Exception:
        return -1


# ── Users ───────────────────────────────────────────────────────────

def list_users() -> list[dict]:
    with _lock:
        return [dict(u) for u in _load_data()["users"]]


def find_user_by_email(email: str) -> dict | None:
    target = (email or "").lower()
    with _lock:
        for u in _load_data()["users"]:
            if (u.get("email") or "").lower() == target:
                return dict(u)
        return None


def find_user_by_id(user_id: str) -> dict | None:
    with _lock:
        for u in _load_data()["users"]:
            if u.get("id") == user_id:
                return dict(u)
        return None


def has_role(role: str) -> bool:
    with _lock:
        return any(u.get("role") == role for u in _load_data()["users"])


def insert_user(user: dict) -> None:
    with _lock:
        data = _load_data()
        data["users"].append(dict(user))
        _save_data(data)


def update_user(user_id: str, patch: dict) -> dict | None:
    """Apply patch to the user with id=user_id. None values in patch
    are skipped (treated as "don't touch"). Returns updated user, or
    None if not found."""
    with _lock:
        data = _load_data()
        for u in data["users"]:
            if u.get("id") == user_id:
                for k, v in patch.items():
                    if v is None:
                        continue
                    u[k] = v
                _save_data(data)
                return dict(u)
        return None


def delete_user(user_id: str) -> bool:
    with _lock:
        data = _load_data()
        before = len(data["users"])
        data["users"] = [u for u in data["users"] if u.get("id") != user_id]
        if len(data["users"]) == before:
            return False
        _save_data(data)
        return True


# ── Sessions ────────────────────────────────────────────────────────
# Same disk + lock as users — every read sees what every other
# container just wrote.

def insert_session(token: str, user_id: str, ttl_seconds: int, user_agent: str | None = None) -> None:
    now = int(time.time())
    with _lock:
        data = _load_data()
        data["sessions"][token] = {
            "user_id":      user_id,
            "created_at":   now,
            "expires_at":   now + ttl_seconds,
            "last_seen_at": now,
            "user_agent":   (user_agent or "")[:500],
        }
        _save_data(data)


def get_session(token: str) -> dict | None:
    """Return the session record (or None if missing/expired). Expired
    rows are pruned on access."""
    if not token:
        return None
    with _lock:
        data = _load_data()
        s = data["sessions"].get(token)
        if not s:
            return None
        if int(s.get("expires_at", 0)) < int(time.time()):
            data["sessions"].pop(token, None)
            _save_data(data)
            return None
        return dict(s)


def touch_session(token: str) -> None:
    """Update last_seen_at. Writes the file — slightly more expensive
    than the previous in-memory bump, but every authed request needs
    to call get_session anyway, and write throughput on a tiny JSON
    file is fine at our scale."""
    with _lock:
        data = _load_data()
        s = data["sessions"].get(token)
        if not s:
            return
        s["last_seen_at"] = int(time.time())
        _save_data(data)


def delete_session(token: str) -> None:
    with _lock:
        data = _load_data()
        if data["sessions"].pop(token, None) is not None:
            _save_data(data)


def delete_sessions_for_user(user_id: str) -> None:
    with _lock:
        data = _load_data()
        before = len(data["sessions"])
        data["sessions"] = {
            t: s for t, s in data["sessions"].items()
            if s.get("user_id") != user_id
        }
        if len(data["sessions"]) != before:
            _save_data(data)
