/* eslint-disable */
// Shared Screens CMS primitives.
// Extends the design-system prototype with hash-based navigation.

// ─────────────────────────────────────────────────────────────
// Tiny hash router
// ─────────────────────────────────────────────────────────────
const getRoute = () => {
  const h = (window.location.hash || '#/dashboard').replace(/^#/, '');
  const [path, query = ''] = h.split('?');
  const parts = path.split('/').filter(Boolean);
  const params = Object.fromEntries(new URLSearchParams(query));
  return { path, parts, params };
};

const navigate = (to) => { window.location.hash = to.startsWith('#') ? to : `#${to}`; };

const useRoute = () => {
  const [route, setRoute] = React.useState(getRoute());
  React.useEffect(() => {
    const onChange = () => setRoute(getRoute());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
};

// ─────────────────────────────────────────────────────────────
// Icons — minimal 16px set, 1.5 stroke
// ─────────────────────────────────────────────────────────────
const I = ({ d, size = 16, stroke = 1.5, fill = 'none' }) => (
  <svg width={size} height={size} viewBox="0 0 16 16" fill={fill} stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round">
    {typeof d === 'string' ? <path d={d} /> : d}
  </svg>
);

const Icon = {
  home: (p) => <I {...p} d="M2.5 6.5L8 2l5.5 4.5V13a1 1 0 01-1 1H9.5v-4h-3v4H3.5a1 1 0 01-1-1V6.5z" />,
  library: (p) => <I {...p} d={<><rect x="2" y="2.5" width="12" height="11" rx="1.5"/><path d="M2 6h12M5 2.5v3M10 2.5v3"/></>} />,
  screens: (p) => <I {...p} d={<><rect x="2" y="3" width="12" height="8" rx="1"/><path d="M6 13.5h4M8 11v2.5"/></>} />,
  schedule: (p) => <I {...p} d={<><rect x="2" y="3.5" width="12" height="10" rx="1.5"/><path d="M2 6.5h12M5.5 2v3M10.5 2v3"/></>} />,
  activity: (p) => <I {...p} d="M2 8h2.5L6 4l4 8 1.5-4H14" />,
  settings: (p) => <I {...p} d={<><circle cx="8" cy="8" r="2"/><path d="M8 1.5v2M8 12.5v2M14.5 8h-2M3.5 8h-2M12.6 3.4l-1.4 1.4M4.8 11.2l-1.4 1.4M12.6 12.6l-1.4-1.4M4.8 4.8L3.4 3.4"/></>} />,
  search: (p) => <I {...p} d={<><circle cx="7" cy="7" r="4.5"/><path d="M13.5 13.5l-3.2-3.2"/></>} />,
  plus: (p) => <I {...p} d="M8 3v10M3 8h10" />,
  upload: (p) => <I {...p} d="M8 11V2.5M4.5 6L8 2.5 11.5 6M2.5 13h11" />,
  download: (p) => <I {...p} d="M8 2.5V11M4.5 7.5L8 11l3.5-3.5M2.5 13h11" />,
  more: (p) => <I {...p} d={<><circle cx="3" cy="8" r="1" fill="currentColor" stroke="none"/><circle cx="8" cy="8" r="1" fill="currentColor" stroke="none"/><circle cx="13" cy="8" r="1" fill="currentColor" stroke="none"/></>} />,
  chevR: (p) => <I {...p} d="M6 3l5 5-5 5" />,
  chevL: (p) => <I {...p} d="M10 3L5 8l5 5" />,
  chevD: (p) => <I {...p} d="M3 6l5 5 5-5" />,
  chevU: (p) => <I {...p} d="M3 10l5-5 5 5" />,
  check: (p) => <I {...p} d="M3 8l3.5 3.5L13 5" />,
  close: (p) => <I {...p} d="M4 4l8 8M12 4l-8 8" />,
  play: (p) => <I {...p} d="M5 3.5l7 4.5-7 4.5V3.5z" fill="currentColor" stroke="currentColor" strokeWidth="0.5" />,
  pause: (p) => <I {...p} d={<><rect x="4.5" y="3" width="2" height="10" rx="0.3" fill="currentColor" stroke="none"/><rect x="9.5" y="3" width="2" height="10" rx="0.3" fill="currentColor" stroke="none"/></>} />,
  folder: (p) => <I {...p} d="M2 4.5A1 1 0 013 3.5h3l1.5 1.5H13a1 1 0 011 1V12a1 1 0 01-1 1H3a1 1 0 01-1-1V4.5z" />,
  video: (p) => <I {...p} d={<><rect x="1.5" y="3.5" width="9" height="9" rx="1"/><path d="M10.5 6.5L14 4.5v7l-3.5-2v-3z" fill="currentColor"/></>} />,
  trash: (p) => <I {...p} d="M3 4.5h10M6 4.5V3a.5.5 0 01.5-.5h3a.5.5 0 01.5.5v1.5M4.5 4.5l.5 8a1 1 0 001 1h4a1 1 0 001-1l.5-8" />,
  edit: (p) => <I {...p} d="M11 2.5l2.5 2.5-7 7H4v-2.5l7-7zM10 3.5l2.5 2.5" />,
  grip: (p) => <I {...p} d={<><circle cx="6" cy="4" r="0.8" fill="currentColor" stroke="none"/><circle cx="10" cy="4" r="0.8" fill="currentColor" stroke="none"/><circle cx="6" cy="8" r="0.8" fill="currentColor" stroke="none"/><circle cx="10" cy="8" r="0.8" fill="currentColor" stroke="none"/><circle cx="6" cy="12" r="0.8" fill="currentColor" stroke="none"/><circle cx="10" cy="12" r="0.8" fill="currentColor" stroke="none"/></>} />,
  refresh: (p) => <I {...p} d="M13.5 8a5.5 5.5 0 11-1.6-3.9M13.5 2v2.5H11" />,
  sync: (p) => <I {...p} d="M3 7.5A5 5 0 0112 5M13 8.5A5 5 0 014 11M11.5 2.5L13 5l-2.5 1M4.5 13.5L3 11l2.5-1" />,
  store: (p) => <I {...p} d={<><path d="M2.5 6h11l-.8-3H3.3L2.5 6z"/><path d="M3 6v7h10V6"/><path d="M6 13V9h4v4"/></>} />,
  globe: (p) => <I {...p} d={<><circle cx="8" cy="8" r="5.5"/><path d="M2.5 8h11M8 2.5c1.5 2 2.3 3.8 2.3 5.5S9.5 13.5 8 13.5 5.7 11.7 5.7 8 6.5 4.5 8 2.5z"/></>} />,
  device: (p) => <I {...p} d={<><rect x="3" y="2" width="10" height="12" rx="1.5"/><path d="M7 11.5h2"/></>} />,
  deviceLand: (p) => <I {...p} d={<><rect x="2" y="3" width="12" height="10" rx="1.5"/><path d="M11.5 7v2"/></>} />,
  bell: (p) => <I {...p} d="M4 12h8l-1-1.5V7a3 3 0 10-6 0v3.5L4 12zM6.5 13a1.5 1.5 0 003 0" />,
  warning: (p) => <I {...p} d={<><path d="M8 2.5l6 10.5H2L8 2.5z"/><path d="M8 6.5v3" strokeWidth="1.6"/><circle cx="8" cy="11.3" r="0.6" fill="currentColor" stroke="none"/></>} />,
  offline: (p) => <I {...p} d="M3 3l10 10M4 7.5a6 6 0 018 0M6.5 9.5a3 3 0 013 0M8 11.5v.1" />,
  filter: (p) => <I {...p} d="M2.5 3.5h11l-4 5v4l-3 1v-5l-4-5z" />,
  grid: (p) => <I {...p} d={<><rect x="2" y="2" width="5" height="5" rx="0.5"/><rect x="9" y="2" width="5" height="5" rx="0.5"/><rect x="2" y="9" width="5" height="5" rx="0.5"/><rect x="9" y="9" width="5" height="5" rx="0.5"/></>} />,
  list: (p) => <I {...p} d="M5 4.5h9M5 8h9M5 11.5h9M2 4.5h.1M2 8h.1M2 11.5h.1" />,
  arrowR: (p) => <I {...p} d="M3 8h10M9 4l4 4-4 4" />,
  arrowUp: (p) => <I {...p} d="M8 3v10M4 7l4-4 4 4" />,
  arrowDown: (p) => <I {...p} d="M8 13V3M4 9l4 4 4-4" />,
  drive: (p) => <I {...p} d="M5.5 2.5L2 9l2.5 4.5M5.5 2.5h5L14 9l-2.5 4.5h-9M5.5 2.5l3 5.5M10.5 2.5l-3 5.5M14 9H5.5" strokeWidth="1.2" />,
  user: (p) => <I {...p} d={<><circle cx="8" cy="5.5" r="2.5"/><path d="M3 13.5a5 5 0 0110 0"/></>} />,
  users: (p) => <I {...p} d={<><circle cx="6" cy="6" r="2.3"/><path d="M2 13a4 4 0 018 0"/><path d="M10.5 4.5a2 2 0 010 4M11 13.5a4 4 0 012-3.5" opacity="0.6"/></>} />,
  logout: (p) => <I {...p} d="M6 3.5h-3a1 1 0 00-1 1v7a1 1 0 001 1h3M9 10.5l3-2.5-3-2.5M12 8H6" />,
  star: (p) => <I {...p} d="M8 2l1.8 4 4.2.4-3.2 2.9 1 4.2L8 11l-3.8 2.5 1-4.2-3.2-2.9L6.2 6L8 2z" />,
  tablet: (p) => <I {...p} d={<><rect x="2" y="3" width="12" height="10" rx="1.5"/><circle cx="8" cy="11" r="0.6" fill="currentColor" stroke="none"/></>} />,
  moon: (p) => <I {...p} d="M13 9.5A5.5 5.5 0 016.5 3a5.5 5.5 0 100 11c1.7 0 3.2-.8 4.2-2A5.5 5.5 0 0013 9.5z" />,
  sun: (p) => <I {...p} d={<><circle cx="8" cy="8" r="3"/><path d="M8 1.5v1.5M8 13v1.5M14.5 8H13M3 8H1.5M12.5 3.5l-1 1M4.5 11.5l-1 1M12.5 12.5l-1-1M4.5 4.5l-1-1"/></>} />,
};

// ─────────────────────────────────────────────────────────────
// Toast — bottom-right, auto-dismiss after 3s, success / err / info.
// Uses a simple event bus so any screen can fire one without prop-drilling.
// ─────────────────────────────────────────────────────────────
const TOAST_EVT = 'screens-toast';
const showToast = (message, tone = 'info') => {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(TOAST_EVT, { detail: { message, tone } }));
};

const ToastHost = () => {
  const [items, setItems] = React.useState([]);
  React.useEffect(() => {
    let i = 0;
    const onToast = (e) => {
      const id = ++i;
      const t = { id, ...e.detail };
      setItems((cur) => [...cur, t]);
      setTimeout(() => setItems((cur) => cur.filter((x) => x.id !== id)), 3200);
    };
    window.addEventListener(TOAST_EVT, onToast);
    return () => window.removeEventListener(TOAST_EVT, onToast);
  }, []);
  if (items.length === 0) return null;
  const tones = {
    info: { bg: 'var(--ink-0)', fg: 'var(--on-accent)', accent: 'var(--info)' },
    ok:   { bg: 'var(--ink-0)', fg: 'var(--on-accent)', accent: 'var(--ok-dot)' },
    err:  { bg: 'var(--ink-0)', fg: 'var(--on-accent)', accent: 'var(--err-dot)' },
  };
  return (
    <div style={{
      position: 'fixed', bottom: 24, right: 24, zIndex: 100,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      {items.map((t) => {
        const c = tones[t.tone] || tones.info;
        return (
          <div key={t.id} style={{
            padding: '10px 16px 10px 12px',
            background: c.bg, color: c.fg,
            borderRadius: 10, display: 'flex', alignItems: 'center', gap: 10,
            boxShadow: '0 8px 24px rgba(9,9,11,0.18)',
            fontSize: 13, fontWeight: 500,
            minWidth: 220, maxWidth: 360,
          }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: c.accent, flexShrink: 0 }} />
            <span>{t.message}</span>
          </div>
        );
      })}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// useLiveScreens — polls /api/screens every 3s and exposes the
// list of registered tablets and the current revision. Drives the
// "demo screen comes online" beat across the dashboard + screens views.
// ─────────────────────────────────────────────────────────────
const useLiveScreens = () => {
  const [data, setData] = React.useState({ screens: [], revision: 0 });
  React.useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const res = await fetch('/api/screens', { cache: 'no-store' });
        const body = await res.json();
        const stateRes = await fetch('/api/state', { cache: 'no-store' });
        const stateBody = await stateRes.json();
        if (!cancelled) setData({ screens: body.screens || [], revision: stateBody.revision || 0, items: stateBody.items || [] });
      } catch (_) {
        if (!cancelled) setData((d) => d);  // keep last
      }
    };
    tick();
    const id = setInterval(tick, 3000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);
  return data;
};

// useActivity — polls /api/activity every 5s. Server keeps an in-memory
// ring buffer (last 200 events) populated by registration / push /
// command / sync handlers; this hook reads them, decorates with a
// human-readable relative `time`, and exposes to the activity log + the
// dashboard's recent-activity panel. Empty array on first call means
// the server is up but hasn't recorded anything yet.
// ─────────────────────────────────────────────────────────────
const formatAt = (atSeconds) => {
  if (!atSeconds) return '';
  const diff = Math.max(0, Date.now() / 1000 - atSeconds);
  if (diff < 5) return 'just now';
  if (diff < 60) return `${Math.round(diff)}s ago`;
  if (diff < 3600) return `${Math.round(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.round(diff / 3600)} hr ago`;
  return `${Math.round(diff / 86400)} d ago`;
};
const useActivity = () => {
  const [items, setItems] = React.useState([]);
  React.useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const res = await fetch('/api/activity', { cache: 'no-store' });
        const body = await res.json();
        if (!cancelled) {
          // Decorate with a relative time string using the server's `at`
          // (unix seconds). Re-rendered every poll so 'just now' decays
          // to '5s ago' to '1 min ago' without manual refresh.
          setItems((body.items || []).map((it) => ({ ...it, time: formatAt(it.at) })));
        }
      } catch (_) {
        if (!cancelled) setItems((cur) => cur);
      }
    };
    tick();
    const id = setInterval(tick, 5000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);
  return items;
};

// useLibrary — keeps the in-memory MOCK_VIDEOS / MOCK_BRANDS globals
// in sync with /api/library. The CMS originally loaded library.json
// once at page boot via a <script> tag (real-data.jsx), so a Drive
// sync that wrote a new library.json wouldn't reflect until the user
// reloaded the tab. This hook polls /api/library every 10s and, when
// the response shape changes, mutates the existing arrays in place
// AND increments a version counter that subscribers can return as
// state to trigger re-renders.
//
// Call this once near the top of the app (the Router) so the
// mutations are global, then any component that reads MOCK_VIDEOS
// will pick up new data on its next render.
// ─────────────────────────────────────────────────────────────
const useLibrary = () => {
  const [version, setVersion] = React.useState(0);
  const lastSigRef = React.useRef('');
  // Server-issued ETag we send back as If-None-Match so the next poll
  // returns 304 + zero body when the library hasn't changed. Without
  // this we re-download ~450 kB every 60 s.
  const etagRef = React.useRef(null);
  React.useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const headers = {};
        if (etagRef.current) headers['If-None-Match'] = etagRef.current;
        const res = await fetch('/api/library', { cache: 'no-store', headers });
        // 304 means the library is unchanged since the last successful
        // fetch — fast-path out, no parse, no rerender.
        if (res.status === 304) return;
        const newEtag = res.headers.get('ETag');
        if (newEtag) etagRef.current = newEtag;
        const body = await res.json();
        const videos = Array.isArray(body.videos) ? body.videos : [];
        const brands = Array.isArray(body.brands) ? body.brands : [];
        // Lightweight signature — count + first/last id. Catches grow,
        // shrink, and reorder without diffing the whole array.
        const sig = `${videos.length}:${brands.length}:${videos[0]?.id || ''}:${videos[videos.length - 1]?.id || ''}`;
        if (sig !== lastSigRef.current && !cancelled) {
          lastSigRef.current = sig;
          if (videos.length > 0 && Array.isArray(window.MOCK_VIDEOS)) {
            window.MOCK_VIDEOS.length = 0;
            window.MOCK_VIDEOS.push(...videos);
          }
          if (brands.length > 0 && Array.isArray(window.MOCK_BRANDS)) {
            window.MOCK_BRANDS.length = 0;
            window.MOCK_BRANDS.push(...brands);
          }
          setVersion((v) => v + 1);
        }
      } catch (_) {
        /* keep last */
      }
    };
    tick();
    // 60 s instead of 10 s. The library changes only when Drive Sync
    // runs (daily background scan + on-demand from Settings); polling
    // every 10 s burned ~150 MB/h on every open tab.
    const id = setInterval(tick, 60000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);
  return version;
};

// useFleet — derived screen rows from /api/screens. The CMS no longer
// keeps offline placeholders; everything in the screen list is a real
// registered tablet (online or recently disconnected).
const useFleet = () => {
  const live = useLiveScreens();
  return React.useMemo(
    () => (live.screens || []).map(liveScreenToRow),
    [live],
  );
};

// useLibraryCount — periodically reads /api/library so the sidebar's
// "Content library" count tracks the latest scan. Polls once a minute;
// on-demand syncs (Drive sync → Sync now) resolve faster because the next
// poll picks up the new mtime.
const useLibraryCount = () => {
  const [count, setCount] = React.useState(() => (window.MOCK_VIDEOS || []).length);
  React.useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const r = await fetch('/api/library', { cache: 'no-store' });
        const data = await r.json();
        if (!cancelled) setCount((data.videos || []).length);
      } catch (_) { /* keep last */ }
    };
    tick();
    const id = setInterval(tick, 60000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);
  return count;
};

// pushToScreens — POST /api/push with selected videos, target deviceIds, and
// mode ("replace" or "append"). Empty deviceIds means "every registered screen".
const pushToScreens = async (videos, { deviceIds = [], mode = 'replace' } = {}) => {
  const items = videos.map((v) => ({
    id: v.id,
    title: v.title,
    brand: v.brand,
    product: v.product,
    durationSec: typeof v.durationSec === 'number' ? Math.round(v.durationSec) : 15,
    url: v.mediaUrl,                 // server-relative; player prefixes its base URL
    sizeMb: v.sizeMb,
    width: v.width,
    height: v.height,
  }));
  const res = await fetch('/api/push', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, deviceIds, mode }),
  });
  if (!res.ok) throw new Error(`push failed: ${res.status}`);
  return res.json();
};

// setScreenPlaylist — POST /api/screens/<id>/playlist. Used by the screen
// detail page (add content directly, remove a single item) so we can target
// one screen without the multi-screen PushPicker getting in the way.
const setScreenPlaylist = async (deviceId, items, mode = 'replace') => {
  const payload = items.map((v) => ({
    id: v.id,
    title: v.title,
    brand: v.brand,
    product: v.product,
    durationSec: typeof v.durationSec === 'number' ? Math.round(v.durationSec) : v.durationSec,
    url: v.url || v.mediaUrl,
    sizeMb: v.sizeMb,
    width: v.width,
    height: v.height,
  }));
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/playlist`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items: payload, mode }),
  });
  if (!res.ok) throw new Error(`playlist failed: ${res.status}`);
  return res.json();
};

// sendScreenCommand — POST /api/screens/<id>/command. Used by the screen
// detail page's reboot / clear cache / unregister buttons.
const sendScreenCommand = async (deviceId, command) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command }),
  });
  if (!res.ok) throw new Error(`command failed: ${res.status}`);
  return res.json();
};

// setMixSplash — POST /api/screens/<id>/mix-splash to toggle the bundled
// splash being mixed into the playlist.
const setMixSplash = async (deviceId, mixSplash) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/mix-splash`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mixSplash }),
  });
  if (!res.ok) throw new Error(`mix-splash failed: ${res.status}`);
  return res.json();
};

// setScreenAudio — POST /api/screens/<id>/audio to flip the global "play
// with sound" flag on a screen. When off (the default), individual
// videos can still play with sound if their library entry has
// defaultUnmute set.
const setScreenAudio = async (deviceId, audioOn) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/audio`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ audioOn }),
  });
  if (!res.ok) throw new Error(`audio failed: ${res.status}`);
  return res.json();
};

// setScreenLowDataMode — POST /api/screens/<id>/low-data-mode. When on,
// the tablet polls every 60s instead of every 3s and skips the
// per-location splash download. Cached videos already on disk are
// unaffected.
const setScreenLowDataMode = async (deviceId, lowDataMode) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/low-data-mode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ lowDataMode }),
  });
  if (!res.ok) throw new Error(`low-data-mode failed: ${res.status}`);
  return res.json();
};

// setScreenSyncGroup — POST /api/screens/<id>/sync-group. Tablets that
// share a syncGroup value get an identical "playback" block from the
// server on every state poll, so they stay aligned to the same item
// + position. Pass null or empty string to detach from any group.
const setScreenSyncGroup = async (deviceId, syncGroup) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/sync-group`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ syncGroup }),
  });
  if (!res.ok) throw new Error(`sync-group failed: ${res.status}`);
  return res.json();
};

// setVideoDefaultUnmute — PATCH /api/library/videos/<id> to flip the
// per-video "default to unmute" flag. Persists in library.json so it
// survives Drive rescans (scan-videos.py preserves sticky flags).
const setVideoDefaultUnmute = async (videoId, defaultUnmute) => {
  const res = await fetch(`/api/library/videos/${encodeURIComponent(videoId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultUnmute }),
  });
  if (!res.ok) throw new Error(`library patch failed: ${res.status}`);
  return res.json();
};

// ─────────────────────────────────────────────────────────────
// PushPicker — modal that lets the user choose which screens to push
// to (any registered tablet, online or offline; the server queues per
// deviceId and the tablet picks pushes up on next poll), and whether
// to replace the playlist or append to it.
//
// Returns the API response on confirm. The caller wires the toast.
// ─────────────────────────────────────────────────────────────
const PushPicker = ({ videos, onClose }) => {
  const live = useLiveScreens();
  const liveByDevice = React.useMemo(() => {
    const map = new Map();
    (live.screens || []).forEach((s) => map.set(s.deviceId, s));
    return map;
  }, [live]);

  // Build the picker rows by grouping registered tablets by store. No mock
  // placeholders — only screens that have actually registered show up.
  const rows = React.useMemo(() => {
    const fleet = (live.screens || []).map(liveScreenToRow);
    const byStore = new Map();
    fleet.forEach((s) => {
      const key = s.storeId || 'unassigned';
      if (!byStore.has(key)) byStore.set(key, []);
      byStore.get(key).push(s);
    });
    const out = [];
    // Keep the canonical store order; show stores even if they have no
    // screens, so the user can see where everything lives.
    MOCK_STORES.forEach((store) => {
      out.push({ kind: 'header', id: store.id, name: store.name });
      const inStore = byStore.get(store.id) || [];
      if (inStore.length === 0) {
        out.push({ kind: 'empty', id: `${store.id}-empty`, name: 'No screens registered' });
      } else {
        inStore.forEach((s) => {
          out.push({
            kind: 'screen', id: s.id, name: s.name,
            storeName: store.name, deviceId: s.deviceId, online: s.status === 'online',
          });
        });
      }
    });
    // Anything registered against an unknown storeId.
    const orphans = byStore.get('unassigned') || [];
    if (orphans.length > 0) {
      out.push({ kind: 'header', id: 'unassigned', name: 'Unassigned' });
      orphans.forEach((s) => {
        out.push({
          kind: 'screen', id: s.id, name: s.name,
          storeName: 'Unassigned', deviceId: s.deviceId, online: s.status === 'online',
        });
      });
    }
    return out;
  }, [live]);

  // Default selection: every registered screen, online or offline. The
  // server queues per deviceId, so an offline tablet just receives the push
  // on its next poll.
  const [selected, setSelected] = React.useState(() => new Set(
    rows.filter(r => r.kind === 'screen' && r.deviceId).map(r => r.id)
  ));
  const [mode, setMode] = React.useState('replace');
  const [busy, setBusy] = React.useState(false);

  // Re-seed selection when live list arrives.
  React.useEffect(() => {
    setSelected(new Set(rows.filter(r => r.kind === 'screen' && r.deviceId).map(r => r.id)));
  }, [rows.length]);

  const toggle = (id) => {
    const n = new Set(selected);
    n.has(id) ? n.delete(id) : n.add(id);
    setSelected(n);
  };

  const onlineCount = rows.filter(r => r.kind === 'screen' && r.online).length;
  const offlineCount = rows.filter(r => r.kind === 'screen' && !r.online).length;
  const targetedScreens = rows
    .filter(r => r.kind === 'screen' && selected.has(r.id) && r.deviceId);
  const targetedDeviceIds = targetedScreens.map(r => r.deviceId);
  const targetedOfflineCount = targetedScreens.filter(r => !r.online).length;
  const canPush = targetedDeviceIds.length > 0 && !busy;

  const confirm = async () => {
    setBusy(true);
    try {
      const r = await pushToScreens(videos, { deviceIds: targetedDeviceIds, mode });
      const total = r.screensTargeted || 0;
      const verb = mode === 'replace' ? 'Replaced playlist on' : 'Added to';
      const tail = targetedOfflineCount > 0
        ? ` · ${targetedOfflineCount} offline (queued)`
        : '';
      showToast(`${verb} ${total} screen${total === 1 ? '' : 's'}${tail}`, 'ok');
      onClose(true);
    } catch (e) {
      showToast(`Push failed: ${e.message}`, 'err');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div onClick={() => onClose(false)} style={{
      position: 'absolute', inset: 0, zIndex: 30,
      background: 'rgba(9,9,11,0.4)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width: 560, maxHeight: '85%', background: 'var(--ink-10)',
        borderRadius: 14, border: 'var(--border)',
        display: 'flex', flexDirection: 'column',
        boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
      }}>
        {/* Header */}
        <div style={{ padding: '16px 20px 14px', borderBottom: 'var(--border)' }}>
          <div style={{ fontSize: 15, fontWeight: 500, color: 'var(--ink-1)' }}>Push {videos.length} video{videos.length === 1 ? '' : 's'} to screens</div>
          <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>
            {onlineCount} online · {offlineCount} offline (queues for reconnect)
          </div>
        </div>

        {/* Mode toggle */}
        <div style={{ padding: '12px 20px', borderBottom: 'var(--border)', display: 'flex', gap: 8 }}>
          {[
            { v: 'replace', label: 'Replace playlist', desc: 'Wipe what’s playing now and start with these.' },
            { v: 'append',  label: 'Add to playlist',  desc: 'Append after what’s already there.' },
          ].map((opt) => {
            const active = mode === opt.v;
            return (
              <button key={opt.v} onClick={() => setMode(opt.v)} style={{
                flex: 1, padding: 12, borderRadius: 8,
                border: active ? '1.5px solid var(--ink-1)' : 'var(--border-strong)',
                background: 'var(--ink-10)', textAlign: 'left',
              }}>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{opt.label}</div>
                <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>{opt.desc}</div>
              </button>
            );
          })}
        </div>

        {/* Screen list */}
        <div style={{ flex: 1, overflow: 'auto', padding: '4px 8px 8px' }}>
          {rows.map((r, i) => {
            if (r.kind === 'header') {
              return (
                <div key={`h-${i}`} style={{ padding: '10px 12px 4px', display: 'flex', alignItems: 'center' }}>
                  <Icon.store size={13} />
                  <span style={{ marginLeft: 8, fontSize: 12, fontWeight: 500, color: 'var(--ink-2)' }}>{r.name}</span>
                </div>
              );
            }
            if (r.kind === 'empty') {
              return (
                <div key={r.id} style={{ padding: '6px 12px 8px 30px', fontSize: 11, color: 'var(--ink-4)', fontStyle: 'italic' }}>
                  {r.name}
                </div>
              );
            }
            const checked = selected.has(r.id);
            // Only rows without a deviceId (placeholders) are truly disabled.
            // Offline-but-registered tablets are selectable — the push queues
            // server-side and applies on reconnect.
            const disabled = !r.deviceId;
            return (
              <div key={r.id} onClick={() => !disabled && toggle(r.id)} style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '8px 12px 8px 30px', borderRadius: 6,
                cursor: disabled ? 'not-allowed' : 'pointer',
                opacity: disabled ? 0.5 : (r.online ? 1 : 0.85),
              }}>
                <div style={{
                  width: 16, height: 16, borderRadius: 4,
                  border: checked ? 'none' : '1.5px solid var(--ink-6)',
                  background: checked ? 'var(--ink-0)' : 'var(--ink-10)',
                  color: 'var(--on-accent)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>{checked && <Icon.check size={11} />}</div>
                <span style={{ fontSize: 13, color: 'var(--ink-1)', flex: 1 }}>{r.name}</span>
                {r.online ? <Chip tone="ok">Online</Chip> : <Chip tone="outline">Offline · queues</Chip>}
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div style={{ padding: '14px 20px', borderTop: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ flex: 1, fontSize: 12, color: 'var(--ink-4)' }}>
            <span className="tnum" style={{ color: 'var(--ink-1)', fontWeight: 500 }}>{targetedDeviceIds.length}</span> screen{targetedDeviceIds.length === 1 ? '' : 's'} will receive {videos.length} video{videos.length === 1 ? '' : 's'}
            {targetedOfflineCount > 0 && (
              <span> · <span className="tnum" style={{ color: 'var(--ink-2)' }}>{targetedOfflineCount}</span> queued for reconnect</span>
            )}
          </div>
          <Button variant="secondary" size="sm" onClick={() => onClose(false)}>Cancel</Button>
          <Button variant="primary" size="sm" disabled={!canPush} onClick={confirm} icon={<Icon.arrowR size={12} />}>
            {busy ? 'Pushing…' : `Push to ${targetedDeviceIds.length}`}
          </Button>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Dark mode hook — single source of truth, reads/writes
// document.documentElement.dataset.theme so tokens.css picks it up.
// ─────────────────────────────────────────────────────────────
const useDarkMode = () => {
  const [dark, setDark] = React.useState(() => {
    if (typeof document === 'undefined') return false;
    if (document.documentElement.dataset.theme === 'dark') return true;
    if (document.documentElement.dataset.theme === 'light') return false;
    // Fallback to system preference on first load.
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches || false;
  });
  React.useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    try { localStorage.setItem('screens.dark', dark ? '1' : '0'); } catch (_) {}
  }, [dark]);
  return [dark, setDark];
};

// ─────────────────────────────────────────────────────────────
// StatusDot — semantic 8px circle
// ─────────────────────────────────────────────────────────────
const StatusDot = ({ status = 'online', size = 8, pulse = false }) => {
  const color = { online: 'var(--ok-dot)', offline: 'var(--err-dot)', warn: 'var(--warn-dot)', updating: 'var(--info)' }[status];
  return (
    <span style={{
      display: 'inline-block', width: size, height: size, borderRadius: '50%',
      background: color, flexShrink: 0,
      boxShadow: pulse ? `0 0 0 3px ${color}20` : 'none',
    }} />
  );
};

// ─────────────────────────────────────────────────────────────
// Chip — neutral pill
// ─────────────────────────────────────────────────────────────
const Chip = ({ children, tone = 'neutral', size = 'sm', style }) => {
  const tones = {
    neutral: { bg: 'var(--ink-8)', fg: 'var(--ink-2)' },
    ok: { bg: 'var(--ok-bg)', fg: 'var(--ok)' },
    warn: { bg: 'var(--warn-bg)', fg: 'var(--warn)' },
    err: { bg: 'var(--err-bg)', fg: 'var(--err)' },
    info: { bg: 'var(--info-bg)', fg: 'var(--info)' },
    outline: { bg: 'transparent', fg: 'var(--ink-3)', border: 'var(--border-strong)' },
  };
  const t = tones[tone];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: size === 'sm' ? '2px 8px' : '3px 10px',
      fontSize: size === 'sm' ? 11 : 12,
      fontWeight: 500,
      letterSpacing: 0.2,
      borderRadius: 999,
      background: t.bg, color: t.fg,
      border: t.border || 'none',
      lineHeight: 1.4,
      whiteSpace: 'nowrap',
      ...style,
    }}>{children}</span>
  );
};

// ─────────────────────────────────────────────────────────────
// Button — primary / secondary / ghost
// ─────────────────────────────────────────────────────────────
const Button = ({ children, variant = 'secondary', size = 'md', icon, iconRight, onClick, disabled, style, title }) => {
  const sizes = {
    sm: { h: 26, px: 9, fs: 12 },
    md: { h: 32, px: 12, fs: 13 },
    lg: { h: 38, px: 16, fs: 14 },
  };
  const s = sizes[size];
  const variants = {
    primary: { bg: 'var(--accent)', fg: 'var(--on-accent)', border: 'none', hover: 'var(--accent-hover)' },
    secondary: { bg: 'var(--ink-10)', fg: 'var(--ink-1)', border: 'var(--border-strong)', hover: 'var(--ink-8)' },
    ghost: { bg: 'transparent', fg: 'var(--ink-2)', border: 'none', hover: 'var(--ink-8)' },
    danger: { bg: 'var(--ink-10)', fg: 'var(--err)', border: 'var(--border-strong)', hover: 'var(--err-bg)' },
  };
  const v = variants[variant];
  const [hover, setHover] = React.useState(false);
  return (
    <button
      onClick={onClick} disabled={disabled} title={title}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6, justifyContent: 'center',
        height: s.h, padding: icon && !children ? 0 : `0 ${s.px}px`,
        width: icon && !children ? s.h : 'auto',
        fontSize: s.fs, fontWeight: 500,
        letterSpacing: -0.005,
        borderRadius: 2,
        background: hover && !disabled ? v.hover : v.bg,
        color: v.fg,
        border: v.border,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        transition: 'background .1s',
        whiteSpace: 'nowrap',
        ...style,
      }}>
      {icon}
      {children}
      {iconRight}
    </button>
  );
};

// ─────────────────────────────────────────────────────────────
// Input — search / text
// ─────────────────────────────────────────────────────────────
const Input = ({ placeholder, value, onChange, leadingIcon, size = 'md', style, type = 'text' }) => {
  const h = size === 'sm' ? 28 : size === 'lg' ? 40 : 32;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 6,
      height: h, padding: `0 10px`,
      background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 2,
      color: 'var(--ink-4)',
      ...style,
    }}>
      {leadingIcon && <span style={{ display: 'flex', color: 'var(--ink-4)' }}>{leadingIcon}</span>}
      <input type={type} placeholder={placeholder} value={value} onChange={onChange}
        style={{ border: 'none', outline: 'none', background: 'transparent', flex: 1, fontSize: 13, color: 'var(--ink-1)', minWidth: 0 }} />
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Brand / product placeholder — letters on warm tone
// ─────────────────────────────────────────────────────────────
const brandPalettes = {
  DVX: { bg: '#1c1917', fg: '#fafaf9' },
  SONOS: { bg: '#f4f4f5', fg: '#0a0a0a' },
  Motorola: { bg: '#0c4a6e', fg: '#e0f2fe' },
  Foreo: { bg: '#fdf2f8', fg: '#831843' },
  Bose: { bg: '#18181b', fg: '#e4e4e7' },
  Ember: { bg: '#7c2d12', fg: '#fff7ed' },
  Acme: { bg: '#064e3b', fg: '#d1fae5' },
};
const BrandMark = ({ brand = 'DVX', size = 24, radius = 2 }) => {
  const p = brandPalettes[brand] || { bg: 'var(--ink-1)', fg: 'var(--ink-10)' };
  const letter = brand[0];
  return (
    <span style={{
      width: size, height: size, borderRadius: radius,
      background: p.bg, color: p.fg,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      fontSize: size * 0.46, fontWeight: 500, letterSpacing: -0.3,
      flexShrink: 0,
    }}>{letter}</span>
  );
};

// ─────────────────────────────────────────────────────────────
// Video thumbnail placeholder — title overlay on warm block
// ─────────────────────────────────────────────────────────────
const thumbStyles = [
  { bg: 'linear-gradient(135deg, #1c1917 0%, #44403c 100%)', fg: '#fafaf9' },
  { bg: 'linear-gradient(135deg, #18181b 0%, #27272a 100%)', fg: '#e4e4e7' },
  { bg: 'linear-gradient(160deg, #0c4a6e 0%, #082f49 100%)', fg: '#e0f2fe' },
  { bg: 'linear-gradient(150deg, #431407 0%, #7c2d12 100%)', fg: '#fff7ed' },
  { bg: 'linear-gradient(135deg, #3f3f46 0%, #18181b 100%)', fg: '#e4e4e7' },
  { bg: 'linear-gradient(160deg, #064e3b 0%, #022c22 100%)', fg: '#d1fae5' },
  { bg: 'linear-gradient(150deg, #451a03 0%, #78350f 100%)', fg: '#fef3c7' },
  { bg: 'linear-gradient(135deg, #1e1b4b 0%, #0f0a2c 100%)', fg: '#e0e7ff' },
  { bg: 'linear-gradient(140deg, #27272a 0%, #52525b 100%)', fg: '#fafafa' },
];
const seed = (s) => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return Math.abs(h); };

const Thumbnail = ({ title, brand, duration, aspect = '16/9', size = 'md', style, badge }) => {
  const idx = seed(title + (brand || '')) % thumbStyles.length;
  const t = thumbStyles[idx];
  const fontScale = size === 'sm' ? 0.9 : size === 'lg' ? 1.15 : 1;
  return (
    <div style={{
      width: '100%', aspectRatio: aspect,
      background: t.bg, color: t.fg,
      borderRadius: 2, overflow: 'hidden', position: 'relative',
      display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
      padding: 10 * fontScale,
      fontFamily: 'var(--font-sans)',
      ...style,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 6 }}>
        {brand && <span style={{
          fontSize: 9 * fontScale, fontWeight: 500, letterSpacing: 1.2, textTransform: 'uppercase',
          opacity: 0.7,
        }}>{brand}</span>}
        {badge}
      </div>
      <div style={{
        fontSize: 13 * fontScale, fontWeight: 500, lineHeight: 1.15,
        textWrap: 'pretty', letterSpacing: -0.2,
      }}>{title}</div>
      {duration && (
        <div style={{ position: 'absolute', bottom: 6, right: 6,
          background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)', color: '#fff',
          fontSize: 10, padding: '1px 5px', borderRadius: 3,
          fontVariantNumeric: 'tabular-nums', fontWeight: 500,
        }}>{duration}</div>
      )}
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Sidebar — left nav. Wired to hash routes.
// ─────────────────────────────────────────────────────────────
const SidebarItem = ({ icon, label, current, count, onClick, muted }) => {
  const [hover, setHover] = React.useState(false);
  return (
    <button onClick={onClick}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 10,
        width: '100%', padding: '0 10px', height: 30,
        fontSize: 13, fontWeight: current ? 500 : 400,
        color: current ? 'var(--ink-1)' : muted ? 'var(--ink-4)' : 'var(--ink-3)',
        background: current ? 'var(--ink-8)' : hover ? 'var(--bone-soft)' : 'transparent',
        borderRadius: 2,
        textAlign: 'left', cursor: 'pointer',
      }}>
      <span style={{ color: current ? 'var(--ink-1)' : 'var(--ink-4)', display: 'flex' }}>{icon}</span>
      <span style={{ flex: 1 }}>{label}</span>
      {count !== undefined && <span style={{ fontSize: 11, color: 'var(--ink-4)', fontVariantNumeric: 'tabular-nums' }}>{count}</span>}
    </button>
  );
};

// Dark-mode row — looks like a SidebarItem but the right edge holds a small
// pill switch instead of a count. Click anywhere in the row to toggle.
const DarkModeToggleRow = () => {
  const [dark, setDark] = useDarkMode();
  const [hover, setHover] = React.useState(false);
  return (
    <button
      onClick={() => setDark(!dark)}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: 10,
        width: '100%', padding: '0 10px', height: 30,
        fontSize: 13, fontWeight: 400,
        color: 'var(--ink-3)',
        background: hover ? 'var(--bone-soft)' : 'transparent',
        borderRadius: 2,
        textAlign: 'left', cursor: 'pointer',
      }}>
      <span style={{ color: 'var(--ink-4)', display: 'flex' }}>
        {dark ? <Icon.sun /> : <Icon.moon />}
      </span>
      <span style={{ flex: 1 }}>Dark mode</span>
      <span aria-hidden style={{
        width: 24, height: 14, borderRadius: 999,
        background: dark ? 'var(--ink-1)' : 'var(--ink-7)',
        position: 'relative', transition: 'background .12s',
        flexShrink: 0,
      }}>
        <span style={{
          position: 'absolute', top: 2, left: dark ? 12 : 2,
          width: 10, height: 10, borderRadius: '50%',
          background: 'var(--ink-10)',
          transition: 'left .12s',
        }} />
      </span>
    </button>
  );
};

const ROLE_LABELS_SIDEBAR = {
  owner: 'Owner', super_admin: 'Super admin', admin: 'Admin',
  manager: 'Manager', user: 'User', viewer: 'Viewer',
  brand_partner: 'Brand partner',
};

const Sidebar = ({ current = 'dashboard', orgName = 'Smartech Group' }) => {
  // Dynamic counts. Library polls /api/library; Screens reads the live
  // registry; Schedules counts the (currently empty) MOCK_SCHEDULES.
  const libraryCount = useLibraryCount();
  const fleetCount = useFleet().length;
  const scheduleCount = (MOCK_SCHEDULES || []).length;

  // Real user from /api/auth/me, fetched once on mount in AuthProvider.
  // Role-based gating uses the `permissions` array on the user object,
  // which mirrors the server's PERMISSIONS dict for the user's role.
  // appVersion is the canonical VERSION file value, shown in the user
  // chip so admins can tell which release is running at a glance.
  const auth = useAuth();
  const user = auth.user || { displayName: '—', role: 'viewer', permissions: [] };
  const appVersion = auth.appVersion;
  const initials = (user.displayName || user.email || '?')
    .split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase();
  const [menuOpen, setMenuOpen] = React.useState(false);

  return (
  <aside style={{
    width: 'var(--sidebar-w)', height: '100%',
    borderRight: 'var(--border)', background: 'var(--bone)',
    display: 'flex', flexDirection: 'column',
    padding: '14px 10px 12px', flexShrink: 0,
  }}>
    {/* Brand lockup — inline mark (currentColor adapts to dark mode) +
        "Screens" wordmark + organisation context. Mark spec from
        brand/README.md: bars + colon, 96-unit grid, currentColor. */}
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 8px 14px' }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--ink-0)' }}>
        <svg viewBox="0 0 96 96" width="18" height="18" aria-label="Screens">
          <rect x="10" y="6"  width="76" height="14" fill="currentColor"/>
          <circle cx="48" cy="42" r="6" fill="currentColor"/>
          <circle cx="48" cy="54" r="6" fill="currentColor"/>
          <rect x="10" y="76" width="76" height="14" fill="currentColor"/>
        </svg>
        <span style={{ fontFamily: 'var(--font-display)', fontSize: 14, fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1 }}>
          Screens
        </span>
      </span>
      <span style={{ fontSize: 13, fontWeight: 400, color: 'var(--ink-4)', flex: 1, paddingLeft: 8, borderLeft: 'var(--border)', marginLeft: 4, paddingTop: 1, paddingBottom: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{orgName}</span>
      <span style={{ color: 'var(--ink-4)', display: 'flex' }}><Icon.chevD size={12} /></span>
    </div>

    {/* Main nav — items hidden if the role can't access them. */}
    <nav style={{ display: 'flex', flexDirection: 'column', gap: 1, marginTop: 2 }}>
      <SidebarItem icon={<Icon.home />} label="Dashboard" current={current === 'dashboard'} onClick={() => navigate('/dashboard')} />
      {can(user, 'library.view') && (
        <SidebarItem icon={<Icon.library />} label="Content library" current={current === 'library'} count={libraryCount || undefined} onClick={() => navigate('/library')} />
      )}
      {can(user, 'screens.view') && (
        <SidebarItem icon={<Icon.screens />} label="Screens" current={current === 'screens'} count={fleetCount || undefined} onClick={() => navigate('/screens')} />
      )}
      {can(user, 'schedules.view') && (
        <SidebarItem icon={<Icon.schedule />} label="Schedules" current={current === 'schedules'} count={scheduleCount || undefined} onClick={() => navigate('/schedules')} />
      )}
      {can(user, 'activity.view') && (
        <SidebarItem icon={<Icon.activity />} label="Activity log" current={current === 'activity'} onClick={() => navigate('/activity')} />
      )}
    </nav>

    <div style={{ flex: 1 }} />

    {/* Footer nav */}
    <nav style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <SidebarItem icon={<Icon.tablet />} label="On-tablet preview" current={current === 'tablet'} muted onClick={() => navigate('/tablet')} />
      <DarkModeToggleRow />
      {can(user, 'users.view') && (
        <SidebarItem icon={<Icon.users />} label="Users" current={current === 'users'} onClick={() => navigate('/users')} />
      )}
      {can(user, 'settings.view') && (
        <SidebarItem icon={<Icon.settings />} label="Settings" current={current === 'settings'} onClick={() => navigate('/settings')} />
      )}
    </nav>

    {/* User chip — click to open menu (sign out). */}
    <div style={{ padding: '10px 8px 0', marginTop: 10, borderTop: 'var(--border)', position: 'relative' }}>
      <button
        onClick={() => setMenuOpen((v) => !v)}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, paddingTop: 10,
          width: '100%', background: 'transparent', border: 'none', cursor: 'pointer',
          textAlign: 'left',
        }}>
        <div style={{
          width: 24, height: 24, borderRadius: '50%',
          background: 'var(--ink-1)', color: 'var(--ink-10)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 10, fontWeight: 500, overflow: 'hidden',
        }}>
          {user.pictureUrl ? (
            <img src={user.pictureUrl} alt="" width={24} height={24} style={{ display: 'block' }} />
          ) : initials}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {user.displayName}
          </div>
          <div style={{ fontSize: 11, color: 'var(--ink-4)', display: 'flex', gap: 6, alignItems: 'baseline' }}>
            <span>{ROLE_LABELS_SIDEBAR[user.role] || user.role}</span>
            {appVersion && (
              <span style={{ fontFamily: 'var(--font-mono, JetBrains Mono, monospace)', fontSize: 10, color: 'var(--ink-5, var(--ink-4))' }} title="App version">
                · v{appVersion}
              </span>
            )}
          </div>
        </div>
        <span style={{ color: 'var(--ink-4)', display: 'flex' }}><Icon.more /></span>
      </button>
      {menuOpen && (
        <div
          onClick={() => setMenuOpen(false)}
          style={{
            position: 'absolute', bottom: '100%', left: 8, right: 8, marginBottom: 6,
            background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 4,
            boxShadow: '0 8px 24px -10px rgba(0,0,0,0.25)', overflow: 'hidden', zIndex: 5,
          }}>
          <button
            onClick={async () => { await auth.logout(); }}
            style={{
              width: '100%', textAlign: 'left', padding: '10px 12px',
              background: 'transparent', border: 'none', cursor: 'pointer',
              fontSize: 12, color: 'var(--ink-1)',
            }}>Sign out</button>
        </div>
      )}
    </div>
  </aside>
  );
};

// ─────────────────────────────────────────────────────────────
// Page header — top bar with title + actions
// ─────────────────────────────────────────────────────────────
const PageHeader = ({ title, subtitle, crumbs, actions, kicker }) => (
  <header style={{
    height: 'var(--header-h)', minHeight: 'var(--header-h)',
    padding: '0 24px',
    borderBottom: 'var(--border)',
    display: 'flex', alignItems: 'center', gap: 16,
    background: 'var(--ink-10)',
    flexShrink: 0,
  }}>
    <div style={{ flex: 1, minWidth: 0 }}>
      {crumbs && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'var(--ink-4)', marginBottom: 2 }}>
          {crumbs.map((c, i) => {
            const label = typeof c === 'string' ? c : c.label;
            const href = typeof c === 'string' ? null : c.href;
            const isLast = i === crumbs.length - 1;
            return (
              <React.Fragment key={i}>
                {i > 0 && <span style={{ opacity: 0.5 }}><Icon.chevR size={11} /></span>}
                {href && !isLast ? (
                  <button onClick={() => navigate(href)} style={{ color: 'var(--ink-4)', fontWeight: 400, cursor: 'pointer' }}>{label}</button>
                ) : (
                  <span style={{ color: isLast ? 'var(--ink-2)' : 'var(--ink-4)', fontWeight: isLast ? 500 : 400 }}>{label}</span>
                )}
              </React.Fragment>
            );
          })}
        </div>
      )}
      {kicker && <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{kicker}</div>}
      {title && <h1 style={{ fontFamily: 'var(--font-display)', fontSize: 16, fontWeight: 600, color: 'var(--ink-0)', letterSpacing: '-0.02em' }}>{title}</h1>}
      {subtitle && <p style={{ fontSize: 12, color: 'var(--ink-4)' }}>{subtitle}</p>}
    </div>
    {actions && <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>{actions}</div>}
  </header>
);

// ─────────────────────────────────────────────────────────────
// AppShell — composes sidebar + content area
// ─────────────────────────────────────────────────────────────
const AppShell = ({ current, children, sidebarProps }) => (
  <div className="scr" style={{ display: 'flex', width: '100%', height: '100%' }}>
    <Sidebar current={current} {...sidebarProps} />
    <main style={{ flex: 1, minWidth: 0, height: '100%', display: 'flex', flexDirection: 'column', background: 'var(--ink-10)', position: 'relative' }}>
      {children}
    </main>
  </div>
);

// ─────────────────────────────────────────────────────────────
// Card — base container
// ─────────────────────────────────────────────────────────────
const Card = ({ children, padding = 20, style }) => (
  <div style={{
    background: 'var(--ink-10)',
    border: 'var(--border)',
    borderRadius: 4,
    padding,
    ...style,
  }}>{children}</div>
);

// Stat card — big number
const StatCard = ({ label, value, delta, tone, sub }) => (
  <Card padding={18} style={{ flex: 1, minWidth: 0 }}>
    <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 10 }}>
      {label}
    </div>
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
      <span className="tnum" style={{ fontFamily: 'var(--font-display)', fontSize: 32, fontWeight: 600, color: tone === 'err' ? 'var(--err)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink-0)', letterSpacing: '-0.03em', lineHeight: 1 }}>{value}</span>
      {delta && <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{delta}</span>}
    </div>
    {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 6 }}>{sub}</div>}
  </Card>
);

// Export all globally for cross-file use
Object.assign(window, {
  Icon, StatusDot, Chip, Button, Input, BrandMark, Thumbnail,
  Sidebar, SidebarItem, PageHeader, AppShell, Card, StatCard,
  seed, brandPalettes, navigate, getRoute, useRoute, useDarkMode,
  showToast, ToastHost, useLiveScreens, useFleet, useActivity, useLibrary, pushToScreens, sendScreenCommand, setMixSplash,
  setScreenAudio, setScreenLowDataMode, setScreenSyncGroup, setVideoDefaultUnmute,
  setScreenPlaylist, PushPicker,
});
