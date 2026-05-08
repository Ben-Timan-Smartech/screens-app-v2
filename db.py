"""
SQLite persistence layer.

Single-file DB at $SCREENS_DB_PATH (defaults to ./screens.db locally, or
/data/screens.db inside Cloud Run when a Cloud Storage FUSE volume is
mounted at /data). Stdlib sqlite3 only — no ORM.

Concurrency model: ONE process-wide connection, every operation
serialised under a single RLock. Why: Cloud Storage FUSE doesn't
provide read-after-write consistency across separate file handles —
two threads each opening their own SQLite connection see different
cached views of the same .db file, so writes on one thread are
invisible to reads on another. A single shared handle sidesteps that
entirely, at the cost of some throughput. Fine for our scale; the
auth / users tables see at most a few QPS.

This also rules out WAL mode (which keeps separate -wal/-shm files
that FUSE caches independently) — we use journal_mode=DELETE so the
on-disk state after every commit is a single self-contained .db file
that FUSE can flush atomically.

Schema migrations are forward-only and run on import. Each migration is
idempotent (CREATE TABLE IF NOT EXISTS, etc.).
"""

from __future__ import annotations

import contextlib
import os
import sqlite3
import threading
from pathlib import Path
from typing import Iterator

# Default to a sibling file in dev. On Cloud Run we mount a GCS bucket
# at /data and point this env var at /data/screens.db so the file
# survives container restarts.
DB_PATH = Path(os.environ.get(
    "SCREENS_DB_PATH",
    str(Path(__file__).resolve().parent / "screens.db"),
))

# Single global lock for all DB access (reads and writes). Re-entrant so
# nested calls (e.g. transaction() that uses execute() internally) don't
# self-deadlock.
_db_lock = threading.RLock()
_conn: sqlite3.Connection | None = None


def _connect() -> sqlite3.Connection:
    """Lazy-init the single shared connection. Caller must hold _db_lock."""
    global _conn
    if _conn is not None:
        return _conn
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(
        str(DB_PATH),
        timeout=10.0,
        isolation_level=None,             # autocommit; we manage txns explicitly
        check_same_thread=False,          # we serialise via _db_lock instead
    )
    conn.row_factory = sqlite3.Row
    # journal_mode=DELETE: stick to the classic rollback journal. FUSE
    # handles "write file, sync, close" atomically per file; WAL's
    # auxiliary -wal/-shm files don't survive that pattern reliably.
    # synchronous=FULL: every commit fsyncs before returning. On FUSE,
    # this makes the local cache flush to GCS so writes are durable
    # across container restarts. Slow, but correct.
    conn.execute("PRAGMA journal_mode=DELETE")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA synchronous=FULL")
    _conn = conn
    return conn


def query(sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
    with _db_lock:
        cur = _connect().execute(sql, params)
        return cur.fetchall()


def query_one(sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
    with _db_lock:
        cur = _connect().execute(sql, params)
        return cur.fetchone()


def execute(sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
    """Single statement, autocommit."""
    with _db_lock:
        return _connect().execute(sql, params)


@contextlib.contextmanager
def transaction() -> Iterator[sqlite3.Connection]:
    """Bracket a block of statements in BEGIN/COMMIT under the shared lock."""
    with _db_lock:
        conn = _connect()
        conn.execute("BEGIN")
        try:
            yield conn
        except Exception:
            conn.execute("ROLLBACK")
            raise
        else:
            conn.execute("COMMIT")


# ── Schema migrations ────────────────────────────────────────────────
# Forward-only. Each entry is a (version, sql) tuple. On import we check
# schema_meta('version') and apply anything newer. To add a migration:
# bump the version number, append the SQL, and ship — old DBs auto-upgrade.

_MIGRATIONS: list[tuple[int, str]] = [
    (1, """
        CREATE TABLE IF NOT EXISTS schema_meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS users (
            id            TEXT PRIMARY KEY,
            email         TEXT NOT NULL UNIQUE,
            display_name  TEXT NOT NULL,
            role          TEXT NOT NULL,
            status        TEXT NOT NULL DEFAULT 'active',
            scope_json    TEXT,
            google_sub    TEXT UNIQUE,
            picture_url   TEXT,
            created_at    INTEGER NOT NULL,
            last_login_at INTEGER,
            invited_by    TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
        CREATE INDEX IF NOT EXISTS idx_users_role  ON users(role);

        CREATE TABLE IF NOT EXISTS sessions (
            id           TEXT PRIMARY KEY,
            user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at   INTEGER NOT NULL,
            expires_at   INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            user_agent   TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_sessions_user    ON sessions(user_id);
        CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
    """),
]


def _current_version(conn: sqlite3.Connection) -> int:
    try:
        row = conn.execute(
            "SELECT value FROM schema_meta WHERE key='version'"
        ).fetchone()
    except sqlite3.OperationalError:
        return 0
    return int(row[0]) if row else 0


def init() -> None:
    """Run any pending migrations. Idempotent — safe to call on every boot."""
    with _db_lock:
        conn = _connect()
        current = _current_version(conn)
        for version, sql in _MIGRATIONS:
            if version <= current:
                continue
            conn.executescript(sql)
            conn.execute(
                "INSERT OR REPLACE INTO schema_meta(key,value) VALUES('version',?)",
                (str(version),),
            )
            current = version


# Run on import so anything that imports db gets a ready-to-query connection.
init()
