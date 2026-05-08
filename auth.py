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
    if db.has_role("owner"):
        return
    # Don't clobber an existing row with the same email — promote it.
    existing = db.find_user_by_email(OWNER_EMAIL)
    if existing:
        db.update_user(existing["id"], {"role": "owner", "status": "active"})
        return
    db.insert_user({
        "id":           _new_id(),
        "email":        OWNER_EMAIL,
        "display_name": OWNER_NAME,
        "role":         "owner",
        "status":       "active",
        "created_at":   int(time.time()),
        "google_sub":   None,
        "picture_url":  None,
        "last_login_at": None,
        "invited_by":   None,
    })


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

    user = db.find_user_by_email(email)
    if not user:
        # Owner-only invitation model: unknown emails can't sign up.
        raise ValueError("not_invited")
    if user.get("status") != "active":
        raise ValueError("disabled")

    now = int(time.time())
    google_sub = claims.get("sub")
    picture = claims.get("picture")
    # First sign-in: bind the google sub + picture to this user row.
    # update_user skips None values, so we only stamp google_sub if the
    # user doesn't already have one (mimics the SQL COALESCE).
    patch = {"last_login_at": now}
    if not user.get("google_sub") and google_sub:
        patch["google_sub"] = google_sub
    if picture:
        patch["picture_url"] = picture
    user = db.update_user(user["id"], patch) or user

    token = _new_session_token()
    db.insert_session(token, user["id"], SESSION_TTL, user_agent)
    return user, token


def logout(session_token: str) -> None:
    db.delete_session(session_token)


# ── Session lookup ───────────────────────────────────────────────────

def session_token_from_cookie_header(cookie_header: str | None) -> str | None:
    """Extract our session cookie from the request's Cookie header.

    We can't use http.cookies.SimpleCookie here: it silently fails on
    cookies whose values aren't RFC 6265 compliant, returning *zero*
    parsed keys instead of just skipping the offending one. Google's
    GIS sets a `g_state` cookie containing JSON (with braces, colons,
    quotes), and when that cookie comes BEFORE ours in the header,
    SimpleCookie wipes the whole jar — so screens_session is invisible
    even though the browser sent it correctly.

    Manual `;`-split is safe: cookie values can't contain `;` per
    RFC 6265, and we don't care if other names are malformed.
    """
    if not cookie_header:
        return None
    for part in cookie_header.split(";"):
        name, sep, value = part.strip().partition("=")
        if sep and name.strip() == SESSION_COOKIE:
            return value.strip()
    return None


def current_user_for_token(token: str | None) -> Optional[dict]:
    """Resolve a session cookie to a user row (or None). Bumps last_seen_at
    so we can prune long-idle sessions in the future."""
    if not token:
        return None
    sess = db.get_session(token)
    if not sess:
        return None
    user = db.find_user_by_id(sess["user_id"])
    if not user or user.get("status") != "active":
        return None
    db.touch_session(token)
    return user


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
