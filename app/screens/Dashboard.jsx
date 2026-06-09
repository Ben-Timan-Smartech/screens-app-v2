/* eslint-disable */
// Dashboard artboard — fleet health + activity + quick actions.

// DashStat — single value in the stats band. On compact viewports
// the divider becomes a bottom border instead of a right border so
// the band can stack vertically.
const DashStat = ({ label, value, sub, tone, lastInRow }) => (
  <div style={{
    padding: '20px 22px',
    flex: '1 1 50%',
    minWidth: 0,
    // Use background overlay for the divider instead of an explicit
    // border so we can let CSS handle it adaptively via inset shadow
    // — keeps things clean on the wrap.
    boxShadow: lastInRow ? 'none' : 'inset -1px 0 0 0 var(--bone-line)',
  }}>
    <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 12 }}>{label}</div>
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
      <span className="tnum" style={{ fontSize: 30, fontWeight: 500, color: tone === 'err' ? 'var(--err)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink-1)', letterSpacing: -0.8, lineHeight: 1 }}>{value}</span>
    </div>
    {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 8 }}>{sub}</div>}
  </div>
);

const StoreHealthRow = ({ store }) => {
  const onlineRatio = store.online / store.total;
  const health = store.offline > 0 ? 'err' : store.warn > 0 ? 'warn' : 'ok';
  return (
    <button onClick={() => navigate(`/screens/${store.id}`)} style={{
      display: 'flex', alignItems: 'center', gap: 14, width: '100%',
      padding: '11px 20px',
      borderBottom: 'var(--border-faint)',
      textAlign: 'left', cursor: 'pointer',
    }}>
      <StatusDot status={health === 'ok' ? 'online' : health === 'warn' ? 'warn' : 'offline'} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{store.name}</div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{store.city} · {store.country} · {store.region}</div>
      </div>
      {/* Per-status count chips hide on mobile; the progress bar on
          the right already conveys "online / total" at a glance. */}
      <div className="scr-mobile-hide" style={{ display: 'flex', gap: 12, fontSize: 12, color: 'var(--ink-3)' }}>
        <span className="tnum"><span style={{ color: 'var(--ok)' }}>●</span> {store.online} online</span>
        {store.warn > 0 && <span className="tnum"><span style={{ color: 'var(--warn)' }}>●</span> {store.warn}</span>}
        {store.offline > 0 && <span className="tnum"><span style={{ color: 'var(--err)' }}>●</span> {store.offline}</span>}
      </div>
      <div style={{ width: 76, display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-4)', flexShrink: 0 }}>
        <div style={{ flex: 1, height: 3, background: 'var(--ink-8)', borderRadius: 2, overflow: 'hidden' }}>
          <div style={{ width: `${onlineRatio * 100}%`, height: '100%', background: health === 'err' ? 'var(--err)' : health === 'warn' ? 'var(--warn)' : 'var(--ok)' }}/>
        </div>
        <span className="tnum" style={{ fontSize: 11, minWidth: 28, textAlign: 'right' }}>{store.total}</span>
      </div>
      <Icon.chevR size={13} />
    </button>
  );
};

const ActivityRow = ({ item }) => {
  const iconMap = { upload: Icon.upload, schedule: Icon.schedule, offline: Icon.offline, check: Icon.check, sync: Icon.sync };
  const Ic = iconMap[item.icon] || Icon.activity;
  const toneColor = { err: 'var(--err)', ok: 'var(--ok)' }[item.tone] || 'var(--ink-4)';
  return (
    <div style={{ display: 'flex', gap: 12, padding: '10px 20px', alignItems: 'flex-start', borderBottom: 'var(--border-faint)' }}>
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
};

const QuickAction = ({ icon, label, desc, to }) => (
  <button onClick={() => to && navigate(to)} style={{
    flex: 1, padding: '14px 16px',
    border: 'var(--border)', borderRadius: 10,
    textAlign: 'left', background: 'var(--ink-10)',
    display: 'flex', flexDirection: 'column', gap: 8, cursor: 'pointer',
  }}>
    <span style={{ color: 'var(--ink-2)', display: 'flex' }}>{icon}</span>
    <div>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{label}</div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>{desc}</div>
    </div>
  </button>
);

// Recompute store rollups straight from registered tablets. No mock fleet:
// each store's totals come from however many real tablets have registered
// against that storeId.
const useFleetRollup = () => {
  const live = useLiveScreens();
  const fleet = useFleet();
  return React.useMemo(() => {
    const stores = MOCK_STORES.map((s) => {
      const screensInStore = fleet.filter(x => x.storeId === s.id);
      const counts = screensInStore.reduce((acc, x) => {
        acc[x.status === 'online' ? 'online' : x.status === 'warn' ? 'warn' : 'offline']++;
        return acc;
      }, { online: 0, warn: 0, offline: 0 });
      return { ...s, total: screensInStore.length, ...counts };
    });
    const total = fleet.length;
    const online = fleet.filter(s => s.status === 'online').length;
    const warn = fleet.filter(s => s.status === 'warn').length;
    const offline = fleet.filter(s => s.status === 'offline').length;
    return { stores, total, online, warn, offline, live, fleet };
  }, [live, fleet]);
};

const Dashboard = () => {
  const { stores, total, online, warn, offline, live } = useFleetRollup();
  const liveScreen = live.screens.find((s) => s.online);
  // Live activity from /api/activity. Empty list when nothing's happened
  // since boot — we render an empty-state row instead of fake data.
  const activity = useActivity();
  const vp = useViewport();
  const compact = vp.isCompact;
  const isMobile = vp.tier === 'mobile';

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
        // Tighter padding on mobile so cards have real estate.
        padding: isMobile ? '16px 14px 32px' : compact ? '20px 18px 36px' : '28px 32px 40px',
      }}>
        {/* Greeting */}
        <div style={{ marginBottom: compact ? 20 : 26 }}>
          <div style={{ fontSize: compact ? 20 : 24, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: -0.5, marginBottom: 4 }}>Good morning, Alex</div>
          <div style={{ fontSize: 13, color: 'var(--ink-4)' }}>
            {online === 0
              ? 'No screens online yet — install the player to bring the demo screen up.'
              : `${online} of ${total} screens online. Pushing content from the library lands within seconds.`}
          </div>
        </div>

        {/* Stats band — 4-up on laptop+, 2x2 on mobile/tablet via
            flex-wrap. The DashStat divider becomes a right-edge
            shadow that's inactive on wrap-end items. */}
        <div style={{
          display: 'flex', flexWrap: 'wrap',
          border: 'var(--border)', borderRadius: 12,
          marginBottom: 24, background: 'var(--ink-10)',
          overflow: 'hidden',
        }}>
          <DashStat label="Total screens" value={total} sub={`across ${stores.length} stores · 2 regions`} lastInRow={compact} />
          <DashStat label="Online now" value={online} sub={total > 0 ? `${Math.round(online/total*100)}% of fleet` : '—'} />
          <DashStat label="Needs attention" value={warn + offline} tone="warn" sub={`${warn} warning · ${offline} offline`} lastInRow={compact} />
          <DashStat label="Library" value={(window.MOCK_VIDEOS || []).length} sub={`videos across ${(window.MOCK_BRANDS || []).length} brands`} lastInRow />
        </div>

        {/* Quick actions — wrap on smaller viewports. */}
        <div style={{
          display: 'grid',
          // 4 on laptop+, 2 on tablet, 1 on mobile.
          gridTemplateColumns: isMobile ? '1fr' : compact ? 'repeat(2, 1fr)' : 'repeat(4, 1fr)',
          gap: 12, marginBottom: compact ? 22 : 28,
        }}>
          <QuickAction icon={<Icon.library size={18} />} label="Browse content" desc={`${(window.MOCK_VIDEOS || []).length} videos across ${(window.MOCK_BRANDS || []).length} brands`} to="/library" />
          <QuickAction icon={<Icon.screens size={18} />} label="Manage screens" desc={`${total} screens in ${stores.length} stores`} to="/screens" />
          <QuickAction icon={<Icon.schedule size={18} />} label="Create schedule" desc="Time-based content rotation" to="/schedules/new" />
          <QuickAction icon={<Icon.settings size={18} />} label="Settings" desc="Users, brands, Drive sync" to="/settings" />
        </div>

        {/* Live demo banner — appears when a tablet has registered. */}
        {liveScreen && (
          <div style={{
            border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)',
            padding: '14px 18px', marginBottom: 24,
            display: 'flex', alignItems: 'center', gap: 14,
          }}>
            <StatusDot status="online" pulse />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Demo screen connected</div>
              <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>
                {liveScreen.name || liveScreen.deviceId} · last heartbeat {liveScreen.secondsSinceHeartbeat != null ? `${Math.round(liveScreen.secondsSinceHeartbeat)}s` : 'now'}
                {live.items?.length ? ` · playing ${live.items.length} item${live.items.length === 1 ? '' : 's'} (rev ${live.revision})` : ' · waiting for content'}
              </div>
            </div>
            <Button variant="primary" size="sm" iconRight={<Icon.arrowR size={12} />} onClick={() => navigate('/library')}>Push content</Button>
          </div>
        )}

        {/* Two columns on laptop+, single stacked column on
            mobile / tablet. */}
        <div style={{
          display: 'grid',
          gridTemplateColumns: compact ? '1fr' : '1.4fr 1fr',
          gap: 16, alignItems: 'start',
        }}>
          {/* Stores */}
          <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)' }}>
            <div style={{ display: 'flex', alignItems: 'center', padding: '14px 20px 12px', borderBottom: 'var(--border)' }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Stores</div>
                <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>Health across all {stores.length} locations</div>
              </div>
              <Button variant="ghost" size="sm" iconRight={<Icon.chevR size={12} />}>View all</Button>
            </div>
            <div>
              {stores.map((s) => <StoreHealthRow key={s.id} store={s} />)}
            </div>
          </div>

          {/* Activity */}
          <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)' }}>
            <div style={{ display: 'flex', alignItems: 'center', padding: '14px 20px 12px', borderBottom: 'var(--border)' }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Recent activity</div>
                <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>Last 24 hours</div>
              </div>
              <Button variant="ghost" size="sm" iconRight={<Icon.chevR size={12} />}>Log</Button>
            </div>
            <div>
              {activity.length === 0 ? (
                <div style={{ padding: '20px', textAlign: 'center', fontSize: 12, color: 'var(--ink-4)' }}>
                  No activity yet · push content or register a screen to populate this
                </div>
              ) : (
                activity.slice(0, 8).map((a) => <ActivityRow key={a.id} item={a} />)
              )}
            </div>
          </div>
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { Dashboard });
