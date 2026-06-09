/* eslint-disable */
// App router — maps hash routes to screens. Uses useRoute from ui.jsx.

const { useState, useEffect } = React;

// ─────────────────────────────────────────────────────────────
// Activity log — simple long-form view of all activity
// ─────────────────────────────────────────────────────────────
const ActivityLog = () => {
  // Live events from /api/activity — server's in-memory ring buffer.
  // Empty on a freshly-booted server with no events yet; we show an
  // explicit empty state below rather than falling back to fake data.
  const items = useActivity();
  const vp = useViewport();
  return (
    <AppShell current="activity">
      <PageHeader title="Activity log" subtitle="Every change, every screen event. Newest first." actions={<Button variant="secondary" size="sm" icon={<Icon.filter size={12} />}>Filter</Button>} />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: vp.isCompact ? '16px 14px 32px' : '20px 24px 40px',
      }}>
        {items.length === 0 ? (
          <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)', padding: 32, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
            No activity yet. Push some content, register a screen, or trigger a sync to see events here.
          </div>
        ) : (
        <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)' }}>
          {items.map((item) => {
            const iconMap = { upload: Icon.upload, schedule: Icon.schedule, offline: Icon.offline, check: Icon.check, sync: Icon.sync };
            const Ic = iconMap[item.icon] || Icon.activity;
            const toneColor = { err: 'var(--err)', ok: 'var(--ok)' }[item.tone] || 'var(--ink-4)';
            return (
              <div key={item.id} style={{ display: 'flex', gap: 12, padding: '12px 20px', alignItems: 'flex-start', borderBottom: 'var(--border-faint)' }}>
                <span style={{ color: toneColor, marginTop: 2, display: 'flex' }}><Ic size={14} /></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.45 }}>
                    {item.who && <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>{item.who} </span>}
                    {item.text}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>{item.time}</div>
                </div>
              </div>
            );
          })}
        </div>
        )}
      </div>
    </AppShell>
  );
};

// ─────────────────────────────────────────────────────────────
// Tablet frame — the on-tablet surface, rendered at its native
// 1920×1080 landscape but scaled to fit the current viewport.
// ─────────────────────────────────────────────────────────────
const TabletFrame = () => {
  const [scale, setScale] = useState(1);
  useEffect(() => {
    const fit = () => {
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const s = Math.min(vw / 1920, vh / 1080);
      setScale(s > 1 ? 1 : s);
    };
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);
  return (
    <div style={{
      width: '100%', height: '100%', background: 'var(--ink-9)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      overflow: 'hidden', position: 'relative',
    }}>
      {/* Back to admin chip + "Preview" badge so it's obvious this
          isn't a live tablet — nothing here pushes content to a real
          screen. */}
      <div style={{
        position: 'absolute', top: 16, left: 16, zIndex: 10,
        display: 'inline-flex', alignItems: 'center', gap: 8,
      }}>
        <button onClick={() => navigate('/dashboard')} style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          padding: '6px 10px', borderRadius: 4,
          background: 'var(--ink-10)', border: 'var(--border-strong)',
          color: 'var(--ink-1)', fontSize: 12, fontWeight: 500, cursor: 'pointer',
        }}>
          <Icon.chevL size={12} /> Back to admin
        </button>
        <span style={{
          padding: '4px 9px', borderRadius: 999,
          background: 'rgba(232, 163, 61, 0.15)',
          border: '1px solid rgba(232, 163, 61, 0.45)',
          color: '#B26B0F',
          fontSize: 11, fontWeight: 500,
          textTransform: 'uppercase', letterSpacing: 0.6,
        }}>
          Preview only — picks don't push to real screens
        </span>
      </div>
      <div style={{
        width: 1920, height: 1080,
        transform: `scale(${scale})`, transformOrigin: 'center center',
        background: 'var(--ink-10)', flexShrink: 0,
        boxShadow: scale < 1 ? '0 20px 60px -20px rgba(0,0,0,0.3)' : 'none',
        borderRadius: scale < 1 ? 4 : 0,
        overflow: 'hidden',
      }}>
        <div className="scr" style={{ height: '100%', width: '100%' }}>
          <TabletFlow />
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// 404 for unknown routes — falls back to dashboard
// ─────────────────────────────────────────────────────────────
const NotFound = () => (
  <AppShell current="dashboard">
    <PageHeader title="Not found" />
    <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 12 }}>
      <div style={{ fontSize: 14, color: 'var(--ink-3)' }}>This page doesn't exist.</div>
      <Button variant="primary" size="sm" onClick={() => navigate('/dashboard')}>Back to dashboard</Button>
    </div>
  </AppShell>
);

// ─────────────────────────────────────────────────────────────
// Route resolver
// ─────────────────────────────────────────────────────────────
const Router = () => {
  const route = useRoute();
  const [section, ...rest] = route.parts;
  // Subscribe globally to /api/library so MOCK_VIDEOS / MOCK_BRANDS stay
  // in sync with the server's view (which scan-videos.py rewrites after
  // a Drive sync). Called once at the router level so re-mounts of the
  // page-level components keep seeing the latest snapshot. The version
  // counter that comes back is unused here — its job is to force a
  // re-render when the underlying globals change.
  useLibrary();

  switch (section) {
    case undefined:
    case 'dashboard':
      return <Dashboard />;
    case 'library':
      return <ContentLibrary />;
    case 'screens': {
      const [storeId, screenId] = rest;
      if (!storeId) return <StoresIndex />;
      if (!screenId) return <ScreensStoreView storeId={storeId} />;
      return <ScreenDetail storeId={storeId} screenId={screenId} />;
    }
    case 'schedules':
      return <Schedules initialMode={rest[0] === 'new' ? 'create' : 'list'} />;
    case 'activity':
      return <ActivityLog />;
    case 'settings':
      return <Settings />;
    case 'users':
      return <Users />;
    case 'tablet':
      return <TabletFrame />;
    default:
      return <NotFound />;
  }
};

// ─────────────────────────────────────────────────────────────
// App shell — wires dark-mode toggle, sets default route
// ─────────────────────────────────────────────────────────────
function App() {
  // Seed the theme from localStorage on first paint so the page doesn't
  // flash light. The actual toggle lives in the sidebar (DarkModeToggleRow).
  useEffect(() => {
    if (!window.location.hash) window.location.hash = '#/dashboard';
    try {
      const saved = localStorage.getItem('screens.dark');
      if (saved === '1') document.documentElement.dataset.theme = 'dark';
      else if (saved === '0') document.documentElement.dataset.theme = 'light';
    } catch (_) { /* private mode etc. */ }
  }, []);

  return (
    <AuthProvider>
      <AuthGate>
        <Router />
      </AuthGate>
      <ToastHost />
    </AuthProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
