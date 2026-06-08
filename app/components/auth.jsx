/* eslint-disable */
// Auth context, login screen, and permission helpers.
// Loaded before ui.jsx so the Sidebar can read the current user.
//
// Shape exposed on window:
//   AuthProvider, AuthGate, useAuth(), can(user, perm), LoginScreen
//
// The server's /api/auth/me is the source of truth for both the user
// and the GIS client ID. We don't bake the client ID into the HTML —
// it lives in env so we can deploy a different one per environment.

const { useState, useEffect, useContext, useCallback, useRef, createContext } = React;

// Default value matches the loading state — if a component reads useAuth
// before AuthProvider mounts (shouldn't happen, but defensive), it sees
// loading: true and renders the loading UI rather than crashing.
const AuthContext = createContext({
  loading: true,
  user: null,
  googleClientId: null,
  allowedDomains: [],
  refresh: () => {},
  logout: () => {},
});

// Same-origin fetch helpers — `credentials: 'include'` so the
// HttpOnly session cookie is sent and received. Without this the
// frontend would never see the cookie set by /api/auth/login.
const apiGet = (path) =>
  fetch(path, { credentials: 'include' }).then((r) => r.json());

const apiPost = (path, body) =>
  fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  }).then(async (r) => {
    const data = await r.json().catch(() => ({}));
    return { ok: r.ok, status: r.status, data };
  });

const AuthProvider = ({ children }) => {
  const [state, setState] = useState({
    loading: true,
    user: null,
    googleClientId: null,
    allowedDomains: [],
    appVersion: null,
  });

  const refresh = useCallback(() => {
    return apiGet('/api/auth/me')
      .then((data) =>
        setState({
          loading: false,
          user: data.user || null,
          googleClientId: data.googleClientId || null,
          allowedDomains: data.allowedDomains || [],
          appVersion: data.appVersion || null,
        }),
      )
      .catch(() =>
        setState((s) => ({ ...s, loading: false, user: null })),
      );
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const logout = useCallback(async () => {
    await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
    setState((s) => ({ ...s, loading: false, user: null }));
    // Hop back to the dashboard hash so a subsequent login lands somewhere sensible.
    if (window.location.hash !== '#/dashboard') window.location.hash = '#/dashboard';
  }, []);

  return (
    <AuthContext.Provider value={{ ...state, refresh, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

const useAuth = () => useContext(AuthContext);

// Permission check mirrors the server's PERMISSIONS dict. The list of
// granted permissions for the current user is sent down on /api/auth/me,
// so we only need to do an Array.includes here.
const can = (user, perm) =>
  Array.isArray(user && user.permissions) && user.permissions.includes(perm);

// ── Login screen ────────────────────────────────────────────────────
// Renders the official Google "Sign in" button via Google Identity
// Services. The script is loaded in index.html with `async defer`, so
// we poll briefly on mount until window.google is available.

const FullPage = ({ children }) => (
  <div style={{
    height: '100%', width: '100%', display: 'flex', alignItems: 'center',
    justifyContent: 'center', flexDirection: 'column', gap: 22,
    padding: 24, background: 'var(--bone)',
  }}>
    {children}
  </div>
);

const LoginScreen = () => {
  const { googleClientId, allowedDomains, refresh } = useAuth();
  const buttonRef = useRef(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const handleCredential = useCallback(async (response) => {
    setError(null);
    setBusy(true);
    const { ok, status, data } = await apiPost('/api/auth/login', {
      credential: response.credential,
    });
    setBusy(false);
    if (ok) { refresh(); return; }
    const messages = {
      not_invited: "Your email isn't on the workspace yet. Ask the owner to invite you.",
      domain_blocked: "That domain isn't allowed.",
      disabled: 'Your account is disabled.',
      no_email: 'Google didn’t return an email address.',
      server_misconfigured: 'Sign-in is not configured on the server.',
      missing_credential: 'No Google credential received.',
    };
    setError(messages[data.error] || `Sign-in failed (${data.error || status}).`);
  }, [refresh]);

  useEffect(() => {
    if (!googleClientId) return;
    let cancelled = false;
    const init = () => {
      if (cancelled || !window.google || !window.google.accounts || !window.google.accounts.id) return;
      window.google.accounts.id.initialize({
        client_id: googleClientId,
        callback: handleCredential,
      });
      if (buttonRef.current) {
        buttonRef.current.innerHTML = '';
        window.google.accounts.id.renderButton(buttonRef.current, {
          theme: 'outline', size: 'large', shape: 'rectangular',
          logo_alignment: 'center', width: 280,
        });
      }
    };
    if (window.google && window.google.accounts && window.google.accounts.id) {
      init();
    } else {
      const i = setInterval(() => {
        if (window.google && window.google.accounts && window.google.accounts.id) {
          clearInterval(i);
          init();
        }
      }, 50);
      return () => { cancelled = true; clearInterval(i); };
    }
    return () => { cancelled = true; };
  }, [googleClientId, handleCredential]);

  return (
    <FullPage>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'var(--ink-0)' }}>
        <svg viewBox="0 0 96 96" width="28" height="28" aria-label="Screens">
          <rect x="10" y="6"  width="76" height="14" fill="currentColor"/>
          <circle cx="48" cy="42" r="6" fill="currentColor"/>
          <circle cx="48" cy="54" r="6" fill="currentColor"/>
          <rect x="10" y="76" width="76" height="14" fill="currentColor"/>
        </svg>
        <span style={{
          fontFamily: 'var(--font-display)', fontSize: 22, fontWeight: 600,
          letterSpacing: '-0.02em',
        }}>Screens</span>
      </div>
      <div style={{ textAlign: 'center', maxWidth: 360 }}>
        <h2 style={{ fontFamily: 'var(--font-display)', fontSize: 16, fontWeight: 600, color: 'var(--ink-0)', marginBottom: 6 }}>
          Sign in to continue
        </h2>
        <p style={{ fontSize: 12, color: 'var(--ink-4)', lineHeight: 1.6 }}>
          Use your{' '}
          {allowedDomains && allowedDomains.length
            ? allowedDomains.join(' / ')
            : 'workspace'}
          {' '}Google account.
        </p>
      </div>
      {googleClientId ? (
        <div ref={buttonRef} style={{ minHeight: 44 }} />
      ) : (
        <div style={{
          fontSize: 12, color: 'var(--err)', maxWidth: 360, textAlign: 'center',
          lineHeight: 1.5, border: 'var(--border)', padding: 16, borderRadius: 6,
          background: 'var(--ink-10)',
        }}>
          Sign-in isn't configured. Set <code>SCREENS_GOOGLE_CLIENT_ID</code> in the server environment.
        </div>
      )}
      {busy && (
        <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>Signing you in…</div>
      )}
      {error && (
        <div style={{
          fontSize: 12, color: 'var(--err)', maxWidth: 360, textAlign: 'center', lineHeight: 1.5,
        }}>{error}</div>
      )}
    </FullPage>
  );
};

const AuthGate = ({ children }) => {
  const { loading, user } = useAuth();
  if (loading) {
    return (
      <FullPage>
        <div style={{ fontSize: 12, color: 'var(--ink-4)' }}>Loading…</div>
      </FullPage>
    );
  }
  if (!user) return <LoginScreen />;
  return children;
};

Object.assign(window, {
  AuthContext, AuthProvider, AuthGate, useAuth, can,
  LoginScreen, apiGet, apiPost,
});
