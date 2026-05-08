"""
Tiny persistence layer.

- Users: durable, persisted to a JSON file at $SCREENS_USERS_PATH
  (defaults to /data/users.json on Cloud Run, ./users.json locally).
  In-memory cache mirrors disk; reads come from cache, writes update
  cache + atomically replace the file.
- Sessions: in-memory dict only. Container restart wipes them; users
  re-sign-in (one Google click — not worth persisting through restarts).

Why not SQLite: this used to be a SQLite layer, but Cloud Storage FUSE
doesn't honour SQLite's locking + journal lifecycle reliably. Sessions
written by one request weren't visible on the next, so the CMS never
moved past the login screen. JSON-file writes use the
write-temp-then-rename pattern which FUSE handles atomically per object,
and in-memory caching means reads don't depend on FUSE consistency at
all once the process is up.

Concurrency: the HTTP server is multi-threaded so all access is
serialised under module-level locks. Single Cloud Run instance
(min/max-instances=1) means this lock is sufficient — we'd swap to a
proper backend (Firestore / Cloud SQL) before scaling out.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path

# Path resolution. SCREENS_USERS_PATH is the new explicit knob;
# SCREENS_DB_PATH is the legacy one (the SQLite file path) — we still
# honour its parent directory so existing deploys with /data mounted at
# SCREENS_DB_PATH=/data/screens.db get users.json sitting next to it.
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
_users_cache: list[dict] | None = None  # None until first load


# ── Disk I/O ────────────────────────────────────────────────────────

def _load() -> list[dict]:
    """Return the user list, loading from disk on first call. Caller
    must hold _lock before mutating the returned list."""
    global _users_cache
    if _users_cache is not None:
        return _users_cache
    if not USERS_PATH.exists():
        _users_cache = []
        return _users_cache
    try:
        text = USERS_PATH.read_text(encoding="utf-8")
        data = json.loads(text)
        users = data.get("users") if isinstance(data, dict) else None
        _users_cache = list(users) if isinstance(users, list) else []
    except (OSError, json.JSONDecodeError):
        # Corrupt or unreadable file → start with empty list. The next
        # write replaces the corrupt one. Owner is re-seeded on import.
        _users_cache = []
    return _users_cache


def _save() -> None:
    """Atomically replace the users file with the current cache."""
    USERS_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps({"users": _users_cache}, indent=2)
    # Write to a sibling temp file, then replace. On gcsfuse this is
    # implemented as a copy + delete (not POSIX-atomic) but it's still
    # safer than overwriting in place: a partial-write reader sees the
    # old file, not a half-written one.
    tmp = USERS_PATH.with_suffix(USERS_PATH.suffix + ".tmp")
    tmp.write_text(payload, encoding="utf-8")
    tmp.replace(USERS_PATH)


def init() -> None:
    """Load the cache. Idempotent — safe to call on every boot."""
    with _lock:
        _load()


# ── Users ───────────────────────────────────────────────────────────

def list_users() -> list[dict]:
    with _lock:
        return [dict(u) for u in _load()]


def find_user_by_email(email: str) -> dict | None:
    target = (email or "").lower()
    with _lock:
        for u in _load():
            if (u.get("email") or "").lower() == target:
                return dict(u)
        return None


def find_user_by_id(user_id: str) -> dict | None:
    with _lock:
        for u in _load():
            if u.get("id") == user_id:
                return dict(u)
        return None


def has_role(role: str) -> bool:
    with _lock:
        return any(u.get("role") == role for u in _load())


def insert_user(user: dict) -> None:
    with _lock:
        _load().append(dict(user))
        _save()


def update_user(user_id: str, patch: dict) -> dict | None:
    """Apply patch to the user with id=user_id. None values in patch
    are skipped (treated as 'don't touch'). Returns updated user, or
    None if not found."""
    with _lock:
        for u in _load():
            if u.get("id") == user_id:
                for k, v in patch.items():
                    if v is None:
                        continue
                    u[k] = v
                _save()
                return dict(u)
        return None


def delete_user(user_id: str) -> bool:
    with _lock:
        users = _load()
        before = len(users)
        users[:] = [u for u in users if u.get("id") != user_id]
        if len(users) == before:
            return False
        _save()
        return True


# ── Sessions (in-memory only) ────────────────────────────────────────

_sessions: dict[str, dict] = {}
_sessions_lock = threading.Lock()


def insert_session(token: str, user_id: str, ttl_seconds: int, user_agent: str | None = None) -> None:
    now = int(time.time())
    with _sessions_lock:
        _sessions[token] = {
            "user_id":      user_id,
            "created_at":   now,
            "expires_at":   now + ttl_seconds,
            "last_seen_at": now,
            "user_agent":   (user_agent or "")[:500],
        }


def get_session(token: str) -> dict | None:
    """Return the session record (or None if missing/expired). Expired
    rows are pruned on access."""
    with _sessions_lock:
        s = _sessions.get(token)
        if not s:
            return None
        if s["expires_at"] < int(time.time()):
            _sessions.pop(token, None)
            return None
        return dict(s)


def touch_session(token: str) -> None:
    with _sessions_lock:
        s = _sessions.get(token)
        if s:
            s["last_seen_at"] = int(time.time())


def delete_session(token: str) -> None:
    with _sessions_lock:
        _sessions.pop(token, None)


def delete_sessions_for_user(user_id: str) -> None:
    with _sessions_lock:
        for t in [k for k, v in _sessions.items() if v["user_id"] == user_id]:
            _sessions.pop(t, None)


# Run on import so any caller can immediately query.
init()
