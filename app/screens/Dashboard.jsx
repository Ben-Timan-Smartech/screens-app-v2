/* eslint-disable */
// Dashboard artboard — fleet control room.
// Phase 2 of the redesign: stats band with an on-air pulse, a live
// screens grid (16:9 tile per registered tablet), and a right-hand
// activity rail on desktop that folds under the grid on smaller
// viewports. Every visible field is derived from live server data
// (/api/screens, /api/activity, /api/library) — no mock rollups.

// ── Stat tile in the top band. Optional `liveUnderline` renders a
//    2px mint edge on the tile when the value should read as "on
//    air" — used by the "On air" stat when at least one screen is
//    broadcasting.
const DashStat = ({ label, value, sub, tone, liveUnderline }) => (
  <div style={{
    position: 'relative', flex: '1 1 220px', minWidth: 0,
    padding: '18px 20px', background: 'var(--ink-10)',
    border: 'var(--border)', borderRadius: 8,
    overflow: 'hidden',
  }}>
    <div style={{ fontSize: 10, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: '0.14em', marginBottom: 10 }}>{label}</div>
    <div className="tnum" style={{
      fontSize: 28, fontWeight: 500, lineHeight: 1, letterSpacing: -0.6,
      color: tone === 'err' ? 'var(--err)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink-1)',
    }}>{value}</div>
    {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 8 }}>{sub}</div>}
    {liveUnderline && (
      <span aria-hidden style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, height: 2,
        background: 'var(--accent)',
        boxShadow: '0 0 12px var(--accent-glow, transparent)',
      }} />
    )}
  </div>
);

// ── Live screen tile. Renders as a 16:9 card with a subtle brand-
//    tinted texture so the grid reads as monitors, not rows.
//    Clicking navigates to the screen's detail page.
const ScreenTile = ({ screen }) => {
  const online = screen.status === 'online';
  const secs = screen.secondsSinceHeartbeat;
  const lastSeen = secs == null
    ? 'never'
    : secs < 60 ? `${Math.round(secs)}s ago`
    : secs < 3600 ? `${Math.round(secs / 60)}m ago`
    : `${Math.round(secs / 3600)}h ago`;
  return (
    <button
      onClick={() => navigate(`/screens/${screen.storeId || 'unassigned'}/${screen.deviceId}`)}
      style={{
        display: 'flex', flexDirection: 'column',
        background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 8,
        overflow: 'hidden', cursor: 'pointer', textAlign: 'left',
        transition: 'border-color var(--dur-fast) var(--ease-standard)',
      }}
      onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--ink-6)'; }}
      onMouseLeave={(e) => { e.currentTarget.style.borderColor = ''; }}
    >
      {/* Thumbnail band — 16:9 aspect. Online screens get a subtle
          mint radial tint (reads as a monitor with signal); offline
          get a diagonal-hatch "no signal" pattern. `background`
          keeps a solid fallback in case the gradient chokes. */}
      <div style={{
        position: 'relative', aspectRatio: '16 / 9', width: '100%',
        borderBottom: 'var(--border)',
        background: 'var(--ink-9)',
        backgroundImage: online
          ? 'radial-gradient(ellipse at 30% 30%, rgba(78, 231, 180, 0.14) 0%, transparent 65%)'
          : 'repeating-linear-gradient(45deg, transparent 0 8px, rgba(0, 0, 0, 0.12) 8px 16px)',
      }}>
        {online ? (
          <span style={{
            position: 'absolute', top: 8, left: 8,
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: '3px 7px', borderRadius: 2,
            background: 'rgba(7, 9, 11, 0.55)', backdropFilter: 'blur(4px)',
            fontFamily: 'var(--font-mono)', fontSize: 9, letterSpacing: '0.12em',
            textTransform: 'uppercase', color: 'var(--accent-amber)',
          }}>
            <span style={{
              width: 5, height: 5, borderRadius: '50%',
              background: 'var(--accent-amber)',
              boxShadow: '0 0 6px var(--signal-glow, rgba(255,184,77,0.6))',
              animation: 'sidebar-live-pulse 2s ease-in-out infinite',
            }} />
            On air
          </span>
        ) : (
          <span style={{
            position: 'absolute', top: 8, left: 8,
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: '3px 7px', borderRadius: 2,
            background: 'rgba(7, 9, 11, 0.55)', backdropFilter: 'blur(4px)',
            fontFamily: 'var(--font-mono)', fontSize: 9, letterSpacing: '0.12em',
            textTransform: 'uppercase', color: 'var(--err)',
          }}>
            <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'var(--err)' }} />
            Offline · {lastSeen}
          </span>
        )}
        {/* Screen code chip, top-right — small monospaced label so
            the operator can match a card to its physical PH.B4 code. */}
        <span style={{
          position: 'absolute', top: 8, right: 8,
          padding: '2px 6px', borderRadius: 2,
          background: 'rgba(7, 9, 11, 0.55)', backdropFilter: 'blur(4px)',
          fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--ink-2)',
          letterSpacing: '0.04em',
        }}>{screen.screenCode}</span>
      </div>
      {/* Info band */}
      <div style={{ padding: '10px 12px 12px', display: 'flex', flexDirection: 'column', gap: 3 }}>
        <div style={{
          fontSize: 13, fontWeight: 500, color: 'var(--ink-1)',
          letterSpacing: '-0.01em',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{screen.name}</div>
        <div style={{
          fontSize: 11, color: 'var(--ink-3)', display: 'flex', alignItems: 'center', gap: 6,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          <span aria-hidden style={{
            width: 3, height: 10, borderRadius: 1,
            background: online ? 'var(--accent)' : 'var(--ink-5)', flexShrink: 0,
          }} />
          {online && screen.playing
            ? <>{screen.brand ? `${screen.brand} · ` : ''}{screen.playing}</>
            : online
              ? 'Waiting for content'
              : 'Last seen ' + lastSeen}
        </div>
      </div>
    </button>
  );
};

// ── Activity row in the right rail. Compact variant of the row on
//    /activity — dot + who + text + timestamp.
const RailActivityRow = ({ item }) => {
  const dot = { err: 'var(--err)', ok: 'var(--ok)', warn: 'var(--warn)' }[item.tone] || 'var(--info)';
  return (
    <div style={{ display: 'flex', gap: 10, padding: '10px 4px', borderBottom: 'var(--border-faint)' }}>
      <span aria-hidden style={{
        width: 6, height: 6, borderRadius: '50%', background: dot,
        marginTop: 6, flexShrink: 0,
      }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12, color: 'var(--ink-2)', lineHeight: 1.4 }}>
          {item.who && <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>{item.who} </span>}
          {item.text}
        </div>
        <div style={{ fontSize: 10, color: 'var(--ink-4)', marginTop: 2, fontFamily: 'var(--font-mono)', letterSpacing: '0.06em' }}>{item.time}</div>
      </div>
    </div>
  );
};

// Time-of-day greeting from local clock, plus a first-name lookup that
// falls back gracefully before auth resolves.
const timeOfDayGreeting = () => {
  const h = new Date().getHours();
  if (h < 5)  return 'evening';
  if (h < 12) return 'morning';
  if (h < 18) return 'afternoon';
  return 'evening';
};
const firstNameFrom = (user) => {
  if (!user) return 'there';
  const candidate = user.displayName || user.name || user.email || '';
  const trimmed = String(candidate).trim();
  if (!trimmed) return 'there';
  if (trimmed.includes('@')) {
    const local = trimmed.split('@')[0].replace(/[._]/g, ' ');
    const word = local.split(/\s+/)[0] || 'there';
    return word.charAt(0).toUpperCase() + word.slice(1);
  }
  return trimmed.split(/\s+/)[0];
};

const Dashboard = () => {
  const live = useLiveScreens();
  const fleet = useFleet();
  const activity = useActivity();
  const vp = useViewport();
  const auth = useAuth();

  const total    = fleet.length;
  const online   = fleet.filter((s) => s.status === 'online').length;
  const offline  = fleet.filter((s) => s.status === 'offline').length;
  // Sort screens so operational ones (online) are shown first, then
  // offline. Within each bucket sort by screen code for stable order.
  const sorted = React.useMemo(() => {
    const rank = (s) => (s.status === 'online' ? 0 : s.status === 'warn' ? 1 : 2);
    return [...fleet].sort((a, b) => rank(a) - rank(b) || String(a.screenCode).localeCompare(String(b.screenCode)));
  }, [fleet]);

  const libraryCount = (window.MOCK_VIDEOS || []).length;
  const brandCount   = (window.MOCK_BRANDS || []).length;
  const firstName    = firstNameFrom(auth?.user);
  const greeting     = `Good ${timeOfDayGreeting()}, ${firstName}`;
  const fleetLoading = !!live.loading;

  const isMobile  = vp.tier === 'mobile';
  const compact   = vp.isCompact;
  // 3-col layout (screens grid + right-rail activity) only on laptop+
  // where there's real estate. Below that we stack.
  const showRail  = vp.tier === 'laptop' || vp.tier === 'desktop';

  return (
    <AppShell current="dashboard">
      <PageHeader
        title="Dashboard"
        actions={
          <>
            <Button variant="ghost" size="sm" icon={<Icon.bell size={14} />} />
            <Button variant="primary" size="sm" icon={<Icon.upload size={13} />}>Upload content</Button>
          </>
        }
      />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: isMobile ? '16px 14px 32px' : compact ? '20px 18px 36px' : '24px 28px 40px',
      }}>
        {/* Greeting */}
        <div style={{ marginBottom: compact ? 18 : 24 }}>
          <div style={{
            fontSize: 11, fontWeight: 500, color: 'var(--ink-4)',
            textTransform: 'uppercase', letterSpacing: '0.14em', marginBottom: 6,
          }}>
            Control room · {new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' })}
          </div>
          <div style={{
            fontSize: compact ? 22 : 28, fontWeight: 500, color: 'var(--ink-1)',
            letterSpacing: '-0.03em', lineHeight: 1.05,
          }}>{greeting}</div>
          <div style={{ fontSize: 13, color: 'var(--ink-3)', marginTop: 6 }}>
            {fleetLoading
              ? 'Checking screens…'
              : total === 0
                ? 'No screens registered yet — install the player on a tablet and complete the onboarding wizard.'
                : online === 0
                  ? `${total} screen${total === 1 ? '' : 's'} registered · none on air right now.`
                  : `${online} of ${total} screens on air. Push from the library and it lands within seconds.`}
          </div>
        </div>

        {/* Stats band — flexes to whatever fits, wraps below. The "On
            air" tile carries a mint underline when the fleet is
            broadcasting so the operator's eye lands on it first. */}
        <div style={{
          display: 'flex', flexWrap: 'wrap', gap: 12,
          marginBottom: compact ? 20 : 24,
        }}>
          <DashStat
            label="On air"
            value={online}
            sub={total > 0 ? `${Math.round(online / total * 100)}% of fleet` : 'No fleet yet'}
            liveUnderline={online > 0}
          />
          <DashStat
            label="Offline"
            value={offline}
            tone={offline > 0 ? 'err' : undefined}
            sub={offline > 0 ? 'Investigate on Screens page' : 'All screens reachable'}
          />
          <DashStat
            label="Content library"
            value={libraryCount}
            sub={brandCount > 0 ? `across ${brandCount} brand${brandCount === 1 ? '' : 's'}` : 'Sync Drive to populate'}
          />
          <DashStat
            label="Fleet total"
            value={total}
            sub={total > 0 ? 'Registered tablets' : 'Install the player on a tablet'}
          />
        </div>

        {/* Main grid: screens live tiles on the left, activity rail on
            the right (laptop+). On smaller viewports the rail folds
            beneath the grid. */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: showRail ? 'minmax(0, 1.6fr) minmax(0, 1fr)' : '1fr',
          gap: showRail ? 20 : 16, alignItems: 'start',
        }}>
          {/* ── Screens grid ── */}
          <section>
            <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
                <h3 style={{ fontSize: 15, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.01em' }}>Live screens</h3>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--ink-4)', letterSpacing: '0.06em' }}>
                  {total > 0 ? `${online} on air · ${offline} offline` : '—'}
                </span>
              </div>
              <button
                onClick={() => navigate('/screens')}
                style={{
                  fontSize: 12, color: 'var(--ink-3)', display: 'inline-flex', alignItems: 'center', gap: 4,
                  padding: 0, background: 'transparent', border: 'none', cursor: 'pointer',
                }}>
                All screens <Icon.chevR size={12} />
              </button>
            </div>

            {fleetLoading && total === 0 ? (
              <div style={{
                padding: 40, textAlign: 'center', border: 'var(--border)', borderRadius: 8,
                background: 'var(--ink-10)', color: 'var(--ink-4)', fontSize: 13,
              }}>Checking screens…</div>
            ) : total === 0 ? (
              <div style={{
                padding: 40, textAlign: 'center', border: 'var(--border)', borderRadius: 8,
                background: 'var(--ink-10)', color: 'var(--ink-4)', fontSize: 13,
              }}>
                No screens registered yet.
                <div style={{ marginTop: 6, fontSize: 12 }}>Install the player on a tablet and complete onboarding — it appears here on first heartbeat.</div>
              </div>
            ) : (
              <div style={{
                display: 'grid',
                gridTemplateColumns: isMobile
                  ? '1fr'
                  : compact
                    ? 'repeat(2, minmax(0, 1fr))'
                    : showRail
                      ? 'repeat(3, minmax(0, 1fr))'
                      : 'repeat(4, minmax(0, 1fr))',
                gap: 12,
              }}>
                {sorted.map((s) => <ScreenTile key={s.deviceId} screen={s} />)}
              </div>
            )}
          </section>

          {/* ── Right rail (or folded section on compact viewports) ── */}
          <aside style={{
            display: 'flex', flexDirection: 'column', gap: 12,
            position: showRail ? 'sticky' : 'static',
            top: showRail ? 0 : undefined,
          }}>
            <div style={{
              border: 'var(--border)', borderRadius: 8, background: 'var(--ink-10)',
              padding: '14px 16px 6px',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <span aria-hidden style={{
                  width: 6, height: 6, borderRadius: '50%',
                  background: 'var(--accent-amber)',
                  boxShadow: '0 0 8px var(--signal-glow, rgba(255,184,77,0.5))',
                  animation: 'sidebar-live-pulse 2s ease-in-out infinite',
                }} />
                <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.14em', color: 'var(--ink-3)', fontWeight: 500 }}>
                  Live activity
                </div>
                <button
                  onClick={() => navigate('/activity')}
                  style={{
                    marginLeft: 'auto', fontSize: 11, color: 'var(--ink-4)',
                    background: 'transparent', border: 'none', cursor: 'pointer',
                    display: 'inline-flex', alignItems: 'center', gap: 3,
                  }}>Full log <Icon.chevR size={10} /></button>
              </div>
              {activity.length === 0 ? (
                <div style={{ padding: '16px 4px 20px', textAlign: 'center', fontSize: 12, color: 'var(--ink-4)' }}>
                  Quiet so far. Push some content or register a screen to see events here.
                </div>
              ) : (
                <div>
                  {activity.slice(0, showRail ? 10 : 6).map((a) => <RailActivityRow key={a.id} item={a} />)}
                </div>
              )}
            </div>
          </aside>
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { Dashboard });
