"""
SQLite persistence layer.

Single-file DB at $SCREENS_DB_PATH (defaults to ./screens.db locally, or
/data/screens.db inside Cloud Run when a Cloud Storage FUSE volume is
mounted at /data). Stdlib sqlite3 only — no ORM.

Concurrency model: one writer, many readers. WAL mode is enabled so reads
don't block the writer. The serve.py HTTP server is multi-threaded
(`ThreadedServer`), so every connection is opened with check_same_thread=False
and a 5-second busy timeout. Writes go through `with transaction()` which
takes a process-level RLock — Cloud Run pinned to one instance means this
lock is sufficient; we'd swap it for proper locking if we ever scale out.

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

_write_lock = threading.RLock()
_local = threading.local()


def _connect() -> sqlite3.Connection:
    """Per-thread connection. SQLite connections aren't safe across threads
    even in WAL, but per-thread + WAL gives us the throughput we need."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(
            str(DB_PATH),
            timeout=5.0,
            isolation_level=None,             # autocommit; we manage txns explicitly
            check_same_thread=False,          # we already gate by per-thread storage
        )
        conn.row_factory = sqlite3.Row
        # WAL: readers don't block writers, writer doesn't block readers.
        # foreign_keys: SQLite has FKs off by default — we want them on.
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA synchronous=NORMAL")
        _local.conn = conn
    return conn


def query(sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
    cur = _connect().execute(sql, params)
    return cur.fetchall()


def query_one(sql: str, params: tuple | dict = ()) -> sqlite3.Row | None:
    cur = _connect().execute(sql, params)
    return cur.fetchone()


def execute(sql: str, params: tuple | dict = ()) -> sqlite3.Cursor:
    """Single statement, autocommit. Use `transaction()` for multi-stmt writes."""
    with _write_lock:
        return _connect().execute(sql, params)


@contextlib.contextmanager
def transaction() -> Iterator[sqlite3.Connection]:
    """Bracket a block of writes in BEGIN/COMMIT. Held under _write_lock so
    concurrent writers serialise cleanly."""
    conn = _connect()
    with _write_lock:
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
    conn = _connect()
    current = _current_version(conn)
    with _write_lock:
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
