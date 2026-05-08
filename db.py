"""
Tiny persistence layer.

Both users AND sessions are persisted to a single JSON file at
$SCREENS_USERS_PATH (defaults to /data/users.json on Cloud Run,
./users.json locally). In-memory cache mirrors disk; reads come from
cache, writes update cache + atomically replace the file via
write-temp-then-rename.

We *did* try keeping sessions in process memory only, on the
assumption that Cloud Run pinned to min/max-instances=1 would mean
one container ever holds them. That assumption broke: continuous
deployment redeploys reset the autoscaling settings, and even with
them re-pinned, Cloud Run sometimes routes to a fresh container
mid-session (deploy rollover, instance replacement, etc.). Result:
sign-in succeeds (cookie set), but the next request hits a container
whose `_sessions` dict is empty, and the user bounces back to login.

Persisting sessions to the same FUSE-mounted JSON makes the lookup
container-agnostic. Cost: one GCS object replace per login + per
disable/delete. Both are infrequent.

Why not SQLite: this used to be a SQLite layer, but Cloud Storage FUSE
doesn't honour SQLite's locking + journal lifecycle reliably. JSON
write-temp-then-rename, by contrast, is exactly the pattern FUSE
handles atomically per object.

Concurrency: the HTTP server is multi-threaded so all access is
serialised under module-level locks.
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

# One lock guards both _users_cache and _sessions_cache so saves are
# serialised — they share a file.
_lock = threading.RLock()
_users_cache: list[dict] | None = None        # None until first load
_sessions_cache: dict[str, dict] | None = None  # None until first load


# ── Disk I/O ────────────────────────────────────────────────────────

def _ensure_loaded() -> None:
    """Populate both caches from disk on first call. Caller must hold _lock."""
    global _users_cache, _sessions_cache
    if _users_cache is not None and _sessions_cache is not None:
        return
    data: dict = {}
    if USERS_PATH.exists():
        try:
            data = json.loads(USERS_PATH.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                data = {}
        except (OSError, json.JSONDecodeError):
            # Corrupt or unreadable file → start fresh. Owner is
            # re-seeded on import; sessions are throwaway anyway.
            data = {}
    raw_users = data.get("users") if isinstance(data.get("users"), list) else []
    raw_sessions = data.get("sessions") if isinstance(data.get("sessions"), dict) else {}
    _users_cache = list(raw_users)
    # Drop any expired sessions on load — keeps the file from growing
    # forever with zombie tokens from past sign-ins.
    now = int(time.time())
    _sessions_cache = {
        token: dict(s)
        for token, s in raw_sessions.items()
        if isinstance(s, dict) and s.get("expires_at", 0) > now
    }


def _save() -> None:
    """Atomically replace the file with the current caches."""
    USERS_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(
        {"users": _users_cache, "sessions": _sessions_cache},
        indent=2,
    )
    # Write to a sibling temp file, then replace. On gcsfuse this is
    # implemented as a copy + delete (not POSIX-atomic) but it's still
    # safer than overwriting in place: a partial-write reader sees the
    # old file, not a half-written one.
    tmp = USERS_PATH.with_suffix(USERS_PATH.suffix + ".tmp")
    tmp.write_text(payload, encoding="utf-8")
    tmp.replace(USERS_PATH)


def _load() -> list[dict]:
    """Return the users list (loading caches if needed). Kept as a thin
    accessor for the original users-only callers below."""
    _ensure_loaded()
    return _users_cache  # type: ignore[return-value]


def init() -> None:
    """Load both caches. Idempotent — safe to call on every boot."""
    with _lock:
        _ensure_loaded()


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


# ── Sessions (persisted to disk alongside users) ─────────────────────

def insert_session(token: str, user_id: str, ttl_seconds: int, user_agent: str | None = None) -> None:
    now = int(time.time())
    with _lock:
        _ensure_loaded()
        assert _sessions_cache is not None
        _sessions_cache[token] = {
            "user_id":      user_id,
            "created_at":   now,
            "expires_at":   now + ttl_seconds,
            "last_seen_at": now,
            "user_agent":   (user_agent or "")[:500],
        }
        _save()


def get_session(token: str) -> dict | None:
    """Return the session record (or None if missing/expired). Expired
    rows are pruned on access. Lookup hits the cache; we don't reload
    from disk per-call — login is the only writer and it updates the
    cache before we return."""
    with _lock:
        _ensure_loaded()
        assert _sessions_cache is not None
        s = _sessions_cache.get(token)
        if not s:
            return None
        if s["expires_at"] < int(time.time()):
            _sessions_cache.pop(token, None)
            _save()
            return None
        return dict(s)


def touch_session(token: str) -> None:
    """Update last_seen_at in memory only — flushing to disk on every
    authed request would write users.json on every page load. The
    written-on-disk timestamp will lag, which is fine for an idle
    indicator."""
    with _lock:
        if _sessions_cache is None:
            return
        s = _sessions_cache.get(token)
        if s:
            s["last_seen_at"] = int(time.time())


def delete_session(token: str) -> None:
    with _lock:
        _ensure_loaded()
        assert _sessions_cache is not None
        if _sessions_cache.pop(token, None) is not None:
            _save()


def delete_sessions_for_user(user_id: str) -> None:
    with _lock:
        _ensure_loaded()
        assert _sessions_cache is not None
        to_drop = [k for k, v in _sessions_cache.items() if v.get("user_id") == user_id]
        if not to_drop:
            return
        for t in to_drop:
            _sessions_cache.pop(t, None)
        _save()


# Run on import so any caller can immediately query.
init()
