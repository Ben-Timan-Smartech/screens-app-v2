"""
Authentication + authorisation.

- Google Sign-In: frontend uses Google Identity Services (GIS) to get an
  ID token (a JWT). We POST it to /api/auth/login. This module verifies
  the JWT against Google's public keys, checks the email domain is one
  of ours, then either matches it to an existing `users` row by email or
  rejects the sign-in (we don't auto-create — the Owner has to invite
  first).
- Sessions: opaque random token in an HttpOnly cookie. Server-side
  table holds expiry + user_id. No JWTs for app sessions — easier to
  revoke and inspect.
- RBAC: PERMISSIONS dict is the single source of truth. Routes call
  has_permission(user, "thing.action").

Cookie name: `screens_session`. Path /, HttpOnly, SameSite=Lax. Secure
flag is set when SCREENS_PUBLIC_URL starts with https:// (Cloud Run).
"""

from __future__ import annotations

import http.cookies
import os
import secrets
import time
from typing import Optional

import db

# ── Configuration ────────────────────────────────────────────────────

# Allowed Google Workspace domains — sign-in is rejected if the verified
# email's domain isn't in this set. Owner decision (saved to memory).
ALLOWED_DOMAINS = {
    "smartechworld.com",
    "smartech.buzz",
    "smartechwrld.com",
    "seeyoutmrw.com",
}

# Owner seed — created on first boot if no Owner exists. Decision is
# durable; changing this env var won't change the existing row.
OWNER_EMAIL = os.environ.get("SCREENS_OWNER_EMAIL", "ben@smartechworld.com")
OWNER_NAME  = os.environ.get("SCREENS_OWNER_NAME",  "Ben Timan")

# OAuth client ID from Google Cloud Console. Required for sign-in to
# work; if unset, /api/auth/login returns a clear 503. Kept as an env
# var (not in DB) because it's deploy-config, not app state.
GOOGLE_CLIENT_ID = os.environ.get("SCREENS_GOOGLE_CLIENT_ID", "")

# Public URL — used to decide whether to set the Secure cookie flag.
# https → Secure. Deploy: SCREENS_PUBLIC_URL=https://screens.smartechworld.com
PUBLIC_URL = os.environ.get("SCREENS_PUBLIC_URL", "")

SESSION_COOKIE = "screens_session"
SESSION_TTL = 60 * 60 * 24 * 30   # 30 days


# ── Roles & permissions ──────────────────────────────────────────────

ROLES = (
    "owner",
    "super_admin",
    "admin",
    "manager",
    "user",
    "viewer",
    "brand_partner",
)

# Single source of truth. Add a new permission → add an entry here →
# call has_permission(user, "...") at the route. Frontend mirrors a
# subset for UI gating via /api/auth/me.
PERMISSIONS: dict[str, set[str]] = {
    "users.view":      {"owner", "super_admin", "admin"},
    "users.invite":    {"owner", "super_admin", "admin"},
    "users.edit":      {"owner", "super_admin", "admin"},
    "users.disable":   {"owner", "super_admin", "admin"},
    "users.delete":    {"owner", "super_admin"},

    "library.view":    {"owner", "super_admin", "admin", "manager", "user", "viewer", "brand_partner"},
    "library.edit":    {"owner", "super_admin", "admin", "manager", "user", "brand_partner"},
    "library.approve": {"owner", "super_admin", "admin"},
    "library.sync":    {"owner", "super_admin", "admin"},

    "screens.view":    {"owner", "super_admin", "admin", "manager", "user", "viewer"},
    "screens.push":    {"owner", "super_admin", "admin", "manager", "user"},
    "screens.command": {"owner", "super_admin", "admin", "manager"},

    "schedules.view":  {"owner", "super_admin", "admin", "manager", "user", "viewer"},
    "schedules.edit":  {"owner", "super_admin", "admin", "manager", "user"},

    "settings.view":   {"owner", "super_admin", "admin"},
    "settings.edit":   {"owner", "super_admin"},

    "activity.view":   {"owner", "super_admin", "admin", "manager"},
}


def has_permission(user: dict | None, perm: str) -> bool:
    if user is None:
        return False
    if user.get("status") != "active":
        return False
    allowed = PERMISSIONS.get(perm, set())
    return user.get("role") in allowed


def role_can_be_assigned_by(actor_role: str, target_role: str) -> bool:
    """Owners can assign anything. Super-admins can't create Owners.
    Admins can only assign at-or-below their own level. Nobody can
    create a second Owner (enforced separately at the call site)."""
    if actor_role == "owner":
        return True
    if actor_role == "super_admin":
        return target_role != "owner"
    if actor_role == "admin":
        return target_role in {"admin", "manager", "user", "viewer", "brand_partner"}
    return False


# ── Owner seeding ────────────────────────────────────────────────────

def ensure_owner_seeded() -> None:
    """Create the singular Owner row if no Owner exists. Idempotent."""
    row = db.query_one("SELECT id FROM users WHERE role='owner' LIMIT 1")
    if row:
        return
    # Don't clobber an existing row with the same email — promote it.
    existing = db.query_one("SELECT id FROM users WHERE email=?", (OWNER_EMAIL,))
    now = int(time.time())
    if existing:
        db.execute(
            "UPDATE users SET role='owner', status='active' WHERE id=?",
            (existing["id"],),
        )
        return
    db.execute(
        """INSERT INTO users
           (id, email, display_name, role, status, created_at)
           VALUES (?, ?, ?, 'owner', 'active', ?)""",
        (_new_id(), OWNER_EMAIL, OWNER_NAME, now),
    )


# ── ID generation ────────────────────────────────────────────────────

def _new_id() -> str:
    return secrets.token_urlsafe(12)


def _new_session_token() -> str:
    return secrets.token_urlsafe(32)


# ── Google ID-token verification ─────────────────────────────────────

def verify_google_credential(jwt_credential: str) -> dict:
    """Verify a GIS credential JWT and return its claims.

    Raises ValueError on any verification failure (bad signature,
    expired, wrong audience, untrusted issuer). Caller is expected to
    catch and translate to a 401.
    """
    if not GOOGLE_CLIENT_ID:
        raise RuntimeError("SCREENS_GOOGLE_CLIENT_ID env var not set")
    # Imported lazily so a local-only run that never tries to log in
    # doesn't need google-auth installed at all.
    from google.oauth2 import id_token
    from google.auth.transport import requests as ga_requests

    request = ga_requests.Request()
    claims = id_token.verify_oauth2_token(
        jwt_credential, request, GOOGLE_CLIENT_ID
    )
    # verify_oauth2_token already validates: signature, expiry, audience,
    # and issuer (accounts.google.com or https://accounts.google.com).
    if not claims.get("email_verified"):
        raise ValueError("Email not verified by Google")
    return claims


def email_domain_allowed(email: str) -> bool:
    domain = email.rsplit("@", 1)[-1].lower() if "@" in email else ""
    return domain in ALLOWED_DOMAINS


# ── Login / logout ───────────────────────────────────────────────────

def login_with_google_credential(credential: str, user_agent: str | None) -> tuple[dict, str]:
    """Verify, match user, create session. Returns (user_row, session_token).

    Raises:
      ValueError("not_invited")    — email isn't in `users`
      ValueError("disabled")       — account exists but is disabled
      ValueError("domain_blocked") — email's domain isn't allowlisted
      ValueError(other)            — verification failed
    """
    claims = verify_google_credential(credential)
    email = (claims.get("email") or "").lower()
    if not email:
        raise ValueError("no_email")
    if not email_domain_allowed(email):
        raise ValueError("domain_blocked")

    user = db.query_one("SELECT * FROM users WHERE email=?", (email,))
    if not user:
        # Owner-only invitation model: unknown emails can't sign up.
        raise ValueError("not_invited")
    if user["status"] != "active":
        raise ValueError("disabled")

    now = int(time.time())
    google_sub = claims.get("sub")
    picture = claims.get("picture")
    # First sign-in: bind the google sub + picture to this user row.
    db.execute(
        """UPDATE users
           SET google_sub = COALESCE(google_sub, ?),
               picture_url = COALESCE(?, picture_url),
               last_login_at = ?
           WHERE id = ?""",
        (google_sub, picture, now, user["id"]),
    )

    token = _new_session_token()
    db.execute(
        """INSERT INTO sessions
           (id, user_id, created_at, expires_at, last_seen_at, user_agent)
           VALUES (?, ?, ?, ?, ?, ?)""",
        (token, user["id"], now, now + SESSION_TTL, now, (user_agent or "")[:500]),
    )
    # Re-read so caller sees the freshly-updated last_login_at + picture_url.
    user = db.query_one("SELECT * FROM users WHERE id=?", (user["id"],))
    return dict(user), token


def logout(session_token: str) -> None:
    db.execute("DELETE FROM sessions WHERE id=?", (session_token,))


# ── Session lookup ───────────────────────────────────────────────────

def session_token_from_cookie_header(cookie_header: str | None) -> str | None:
    if not cookie_header:
        return None
    try:
        jar = http.cookies.SimpleCookie(cookie_header)
    except http.cookies.CookieError:
        return None
    morsel = jar.get(SESSION_COOKIE)
    return morsel.value if morsel else None


def current_user_for_token(token: str | None) -> Optional[dict]:
    """Resolve a session cookie to a user row (or None). Bumps last_seen_at
    so we can prune long-idle sessions in the future."""
    if not token:
        return None
    now = int(time.time())
    sess = db.query_one(
        "SELECT user_id, expires_at FROM sessions WHERE id=?",
        (token,),
    )
    if not sess or sess["expires_at"] < now:
        return None
    user = db.query_one("SELECT * FROM users WHERE id=?", (sess["user_id"],))
    if not user or user["status"] != "active":
        return None
    db.execute("UPDATE sessions SET last_seen_at=? WHERE id=?", (now, token))
    return dict(user)


# ── Cookie helpers (used by serve.py to set/clear) ───────────────────

def session_cookie_value(token: str, *, max_age: int = SESSION_TTL) -> str:
    """Build a Set-Cookie header value for the session token."""
    secure = "; Secure" if PUBLIC_URL.startswith("https://") else ""
    return (
        f"{SESSION_COOKIE}={token}; Path=/; HttpOnly; SameSite=Lax;"
        f" Max-Age={max_age}{secure}"
    )


def clear_cookie_value() -> str:
    secure = "; Secure" if PUBLIC_URL.startswith("https://") else ""
    return (
        f"{SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Lax;"
        f" Max-Age=0{secure}"
    )


# ── Public-shape user dict (returned to frontend) ────────────────────

def public_user(user: dict) -> dict:
    """Strip server-only columns (google_sub etc.) for /api/auth/me."""
    return {
        "id":           user["id"],
        "email":        user["email"],
        "displayName":  user["display_name"],
        "role":         user["role"],
        "status":       user["status"],
        "pictureUrl":   user.get("picture_url"),
        "lastLoginAt":  user.get("last_login_at"),
        "permissions": sorted(
            p for p, roles in PERMISSIONS.items() if user["role"] in roles
        ),
    }


# Run owner seed on import so a fresh DB always has an Owner.
ensure_owner_seeded()
