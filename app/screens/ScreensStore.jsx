/* eslint-disable */
// Screens — store view. Grid of screen cards with health + currently playing.

const ScreenCard = ({ s, selected, onToggle, onOpen }) => {
  const [hover, setHover] = React.useState(false);
  const statusLabel = { online: 'Online', offline: 'Offline', warn: 'Needs attention', updating: 'Updating' }[s.status];
  const handleClick = (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey) { onToggle && onToggle(); return; }
    onOpen && onOpen();
  };
  return (
    <div onClick={handleClick}
      onContextMenu={(e) => { e.preventDefault(); onToggle && onToggle(); }}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        border: selected ? '1.5px solid var(--ink-1)' : 'var(--border)',
        borderRadius: 10, padding: 10, cursor: 'pointer',
        background: 'var(--ink-10)',
        transform: hover && !selected ? 'translateY(-1px)' : 'none',
        transition: 'transform .12s, border-color .12s',
      }}>
      <div style={{ position: 'relative', marginBottom: 10 }}>
        {/* Always render the card thumbnail at 16:9 so the grid stays
            visually consistent regardless of physical device orientation.
            Portrait devices are still indicated by the device icon badge
            at the top-right of this card. */}
        {s.playing ? (
          <Thumbnail title={s.playing} brand={s.brand} aspect="16/9" size="sm" />
        ) : (
          <div className="placeholder-tile" style={{ aspectRatio: '16/9', borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>No content</span>
          </div>
        )}
        {s.status === 'updating' && (
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(9,9,11,0.55)', borderRadius: 8, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 6, color: '#fff' }}>
            <Icon.sync size={16} />
            <span style={{ fontSize: 11, fontWeight: 500 }} className="tnum">Updating · {s.progress}%</span>
          </div>
        )}
        <div style={{ position: 'absolute', top: 8, right: 8, display: 'flex', gap: 4 }}>
          <div style={{ background: 'rgba(9,9,11,0.55)', color: '#fff', borderRadius: 4, padding: '2px 6px', fontSize: 10, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            {s.orient === 'portrait' ? <Icon.device size={10} /> : <Icon.deviceLand size={10} />}
            <span>{s.tier}</span>
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        <StatusDot status={s.status === 'warn' ? 'warn' : s.status === 'offline' ? 'offline' : s.status === 'updating' ? 'updating' : 'online'} />
        <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.name}</span>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {s.playing ? `Playing · ${s.playing}` : statusLabel}
        {s.status !== 'online' && s.status !== 'updating' && ` · ${s.lastSeen}`}
      </div>
    </div>
  );
};

// Just the screens that live in this store, filtered straight off the live
// registry. Returns an empty array when no tablet has registered there yet.
const useScreensInStore = (storeId) => {
  const fleet = useFleet();
  return React.useMemo(
    () => fleet.filter(s => !storeId || s.storeId === storeId),
    [storeId, fleet],
  );
};

// Roll up status counts so the store header reflects reality.
const rollupStore = (screens) => {
  const total = screens.length;
  const online = screens.filter(s => s.status === 'online').length;
  const warn = screens.filter(s => s.status === 'warn').length;
  const offline = screens.filter(s => s.status === 'offline').length;
  return { total, online, warn, offline };
};

const ScreensStoreView = ({ storeId }) => {
  const baseStore = MOCK_STORES.find(s => s.id === storeId) || MOCK_STORES[0];
  const screens = useScreensInStore(baseStore.id);
  const live = useLiveScreens();
  const fleetLoading = !!live.loading;
  const counts = rollupStore(screens);
  const store = { ...baseStore, ...counts };
  const [selected, setSelected] = React.useState(new Set());
  const toggle = (id) => { const n = new Set(selected); n.has(id) ? n.delete(id) : n.add(id); setSelected(n); };

  return (
    <AppShell current="screens">
      <PageHeader
        crumbs={[{ label: 'Screens', href: '/screens' }, store.name]}
        title={store.name}
        subtitle={`${store.city} · ${store.country} · ${store.region}`}
        actions={
          <>
            <Button variant="secondary" size="sm" icon={<Icon.sync size={12} />}>Sync playlist</Button>
            <Button variant="primary" size="sm" icon={<Icon.plus size={13} />}>Add screen</Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
        {/* Store stats strip */}
        <div style={{ display: 'flex', gap: 10, marginBottom: 18 }}>
          <div style={{ padding: '10px 14px', border: 'var(--border)', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 10 }}>
            <StatusDot status="online" />
            <span className="tnum" style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{store.online}</span>
            <span style={{ fontSize: 12, color: 'var(--ink-4)' }}>online</span>
          </div>
          <div style={{ padding: '10px 14px', border: 'var(--border)', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 10 }}>
            <StatusDot status="warn" />
            <span className="tnum" style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{store.warn}</span>
            <span style={{ fontSize: 12, color: 'var(--ink-4)' }}>attention</span>
          </div>
          <div style={{ padding: '10px 14px', border: 'var(--border)', borderRadius: 8, display: 'flex', alignItems: 'center', gap: 10 }}>
            <StatusDot status="offline" />
            <span className="tnum" style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{store.offline}</span>
            <span style={{ fontSize: 12, color: 'var(--ink-4)' }}>offline</span>
          </div>
          <span style={{ flex: 1 }} className="scr-mobile-hide" />
          <Input placeholder="Search screens or content…" leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 160, maxWidth: 260 }} />
          <Button variant="ghost" size="sm" icon={<Icon.grid size={13} />} />
          <Button variant="ghost" size="sm" icon={<Icon.list size={13} />} />
        </div>

        {screens.length === 0 ? (
          <div style={{ padding: '60px 16px', border: 'var(--border)', borderRadius: 12, textAlign: 'center', color: 'var(--ink-4)' }}>
            {fleetLoading ? (
              <div style={{ fontSize: 13, color: 'var(--ink-3)' }}>Loading screens…</div>
            ) : (
              <>
                <div style={{ fontSize: 14, color: 'var(--ink-2)', marginBottom: 6 }}>No screens registered at this store yet</div>
                <div style={{ fontSize: 12 }}>Install the player on a tablet, point it at this server, and pick {store.name} as the store during onboarding.</div>
              </>
            )}
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
            {screens.map(s => <ScreenCard key={s.id} s={s} selected={selected.has(s.id)} onToggle={() => toggle(s.id)} onOpen={() => navigate(`/screens/${store.id}/${s.id}`)} />)}
          </div>
        )}

        {selected.size > 0 && (
          <div style={{
            position: 'fixed', bottom: 24, left: 'calc(var(--sidebar-w) + 50%)', transform: 'translateX(-50%)',
            background: 'var(--ink-0)', color: 'var(--on-accent)',
            borderRadius: 10, padding: '8px 8px 8px 16px',
            display: 'flex', alignItems: 'center', gap: 12,
            boxShadow: '0 8px 24px rgba(9,9,11,0.18)',
            zIndex: 5,
          }}>
            <span style={{ fontSize: 12, fontWeight: 500 }} className="tnum">{selected.size} selected</span>
            <button style={{ fontSize: 12, color: 'rgba(250,250,250,0.7)', padding: '4px 6px' }}>Sync content</button>
            <button style={{ fontSize: 12, color: 'rgba(250,250,250,0.7)', padding: '4px 6px' }}>Apply schedule</button>
            <button style={{ background: 'var(--ink-10)', color: 'var(--ink-0)', padding: '6px 12px', borderRadius: 6, fontSize: 12, fontWeight: 500 }}>Push content →</button>
          </div>
        )}
      </div>
    </AppShell>
  );
};

// ─────────────────────────────────────────────────────────────
// Stores index — list of all stores, drills into store view
// ─────────────────────────────────────────────────────────────
const StoresIndex = () => {
  const fleet = useFleet();
  const live = useLiveScreens();
  const stores = MOCK_STORES.map((s) => {
    const inStore = fleet.filter(x => x.storeId === s.id);
    const online = inStore.filter(x => x.status === 'online').length;
    const offline = inStore.filter(x => x.status === 'offline').length;
    return { ...s, total: inStore.length, online, warn: 0, offline };
  });
  const totalScreens = fleet.length;
  // Region count comes from the actual taxonomy values present in
  // the registered fleet, not a hardcoded "2".
  const regionCount = new Set(
    (live.screens || []).map((s) => s.location?.region).filter(Boolean)
  ).size;
  const subtitleParts = [
    `${stores.length} store${stores.length === 1 ? '' : 's'}`,
    `${totalScreens} screen${totalScreens === 1 ? '' : 's'}`,
  ];
  if (regionCount > 0) {
    subtitleParts.push(`${regionCount} region${regionCount === 1 ? '' : 's'}`);
  }
  return (
    <AppShell current="screens">
      <PageHeader
        title="Screens"
        subtitle={subtitleParts.join(' · ')}
        actions={<Button variant="primary" size="sm" icon={<Icon.plus size={13} />}>Add store</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18, flexWrap: 'wrap' }}>
          <Input placeholder="Search stores or cities…" leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 180, maxWidth: 300 }} />
          <span style={{ flex: 1 }} className="scr-mobile-hide" />
          <Button variant="ghost" size="sm" icon={<Icon.filter size={12} />}>Region</Button>
        </div>
        <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)', overflow: 'hidden' }}>
          {stores.map((store) => {
            const health = store.offline > 0 ? 'err' : store.warn > 0 ? 'warn' : (store.online > 0 ? 'ok' : 'idle');
            const ratio = store.total > 0 ? store.online / store.total : 0;
            return (
              <button key={store.id} onClick={() => navigate(`/screens/${store.id}`)} style={{
                display: 'flex', alignItems: 'center', gap: 14, width: '100%',
                padding: '14px 20px',
                borderBottom: 'var(--border-faint)',
                textAlign: 'left', cursor: 'pointer',
              }}>
                <StatusDot status={health === 'ok' ? 'online' : health === 'warn' ? 'warn' : 'offline'} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{store.name}</div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>{store.city} · {store.country} · {store.region}</div>
                </div>
                <div className="scr-mobile-hide" style={{ display: 'flex', gap: 12, fontSize: 12, color: 'var(--ink-3)' }}>
                  <span className="tnum"><span style={{ color: 'var(--ok)' }}>●</span> {store.online} online</span>
                  {store.warn > 0 && <span className="tnum"><span style={{ color: 'var(--warn)' }}>●</span> {store.warn}</span>}
                  {store.offline > 0 && <span className="tnum"><span style={{ color: 'var(--err)' }}>●</span> {store.offline}</span>}
                </div>
                <div style={{ width: 86, display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-4)', flexShrink: 0 }}>
                  <div style={{ flex: 1, height: 3, background: 'var(--ink-8)', borderRadius: 2, overflow: 'hidden' }}>
                    <div style={{ width: `${ratio * 100}%`, height: '100%', background: health === 'err' ? 'var(--err)' : health === 'warn' ? 'var(--warn)' : 'var(--ok)' }}/>
                  </div>
                  <span className="tnum" style={{ fontSize: 11, minWidth: 28, textAlign: 'right' }}>{store.total}</span>
                </div>
                <Icon.chevR size={13} />
              </button>
            );
          })}
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { ScreensStoreView, StoresIndex });
