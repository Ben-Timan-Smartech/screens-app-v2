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
          {s.appVersion && (
            <div className="tnum" title="App version" style={{ background: 'rgba(9,9,11,0.55)', color: '#fff', borderRadius: 4, padding: '2px 6px', fontSize: 10, display: 'inline-flex', alignItems: 'center' }}>
              v{s.appVersion}
            </div>
          )}
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
      {/* v0.1.81: concept shown as its own line (removed from the name). */}
      {s.concept && (
        <div style={{ fontSize: 10.5, color: 'var(--ink-4)', marginTop: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {s.concept}
        </div>
      )}
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

// v0.1.56: row layout for the new default list view. Same data as
// ScreenCard, presented as a dense table-style row so an operator
// can scan 20+ screens without scrolling through a sparse grid.
const ScreenRow = ({ s, selected, onToggle, onOpen }) => {
  const statusLabel = { online: 'Online', offline: 'Offline', warn: 'Needs attention', updating: `Updating ${s.progress || 0}%` }[s.status] || s.status;
  const handleClick = (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey) { onToggle && onToggle(); return; }
    onOpen && onOpen();
  };
  return (
    <button onClick={handleClick}
      onContextMenu={(e) => { e.preventDefault(); onToggle && onToggle(); }}
      style={{
        display: 'flex', alignItems: 'center', gap: 12, width: '100%',
        padding: '12px 16px', textAlign: 'left', cursor: 'pointer',
        background: selected ? 'var(--ink-9)' : 'transparent',
        borderBottom: 'var(--border-faint)',
        borderLeft: selected ? '2px solid var(--ink-1)' : '2px solid transparent',
      }}>
      <StatusDot status={s.status === 'warn' ? 'warn' : s.status === 'offline' ? 'offline' : s.status === 'updating' ? 'updating' : 'online'} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {s.name}
        </div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {s.playing ? `Playing · ${s.playing}` : statusLabel}
          {s.status !== 'online' && s.status !== 'updating' && s.lastSeen ? ` · ${s.lastSeen}` : ''}
        </div>
      </div>
      {/* v0.1.81: concept as its own column (no longer glued onto the name). */}
      <span className="scr-mobile-hide" title="Concept" style={{ fontSize: 11, color: 'var(--ink-3)', width: 96, textAlign: 'right', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        {s.concept || '—'}
      </span>
      <div className="scr-mobile-hide" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--ink-4)' }}>
        {s.orient === 'portrait' ? <Icon.device size={11} /> : <Icon.deviceLand size={11} />}
        <span className="tnum">{s.tier}</span>
      </div>
      {/* v0.1.81: app version per screen, so you can spot which boxes are
          behind without opening each one. '—' until the tablet has checked in. */}
      <span className="tnum scr-mobile-hide" title="App version"
        style={{ fontSize: 11, color: 'var(--ink-3)', width: 52, textAlign: 'right' }}>
        {s.appVersion ? `v${s.appVersion}` : '—'}
      </span>
      <span className="tnum scr-mobile-hide" style={{ fontSize: 11, color: 'var(--ink-3)', width: 70, textAlign: 'right' }}>{s.brand || '—'}</span>
      <Icon.chevR size={13} />
    </button>
  );
};

const ScreensStoreView = ({ storeId }) => {
  const stores = allStores();
  const baseStore = stores.find(s => s.id === storeId) || stores[0];
  const screens = useScreensInStore(baseStore.id);
  const live = useLiveScreens();
  const fleetLoading = !!live.loading;
  const counts = rollupStore(screens);
  const store = { ...baseStore, ...counts };
  const [selected, setSelected] = React.useState(new Set());
  const toggle = (id) => { const n = new Set(selected); n.has(id) ? n.delete(id) : n.add(id); setSelected(n); };
  // v0.1.56: list view is now the default. Persist the choice across
  // navigations so an operator who prefers the card grid doesn't get
  // bounced back to list every visit.
  const [viewMode, setViewMode] = React.useState(() => {
    try { return localStorage.getItem('screens.viewMode') || 'list'; } catch { return 'list'; }
  });
  const setView = (m) => {
    setViewMode(m);
    try { localStorage.setItem('screens.viewMode', m); } catch {}
  };
  // v0.1.81: sortable list. 'name' (A–Z), 'added' (newest registration
  // first), 'updated' (most-recently-seen first). Persisted like viewMode.
  const [sortBy, setSortBy] = React.useState(() => {
    try { return localStorage.getItem('screens.sortBy') || 'name'; } catch { return 'name'; }
  });
  const setSort = (m) => {
    setSortBy(m);
    try { localStorage.setItem('screens.sortBy', m); } catch {}
  };
  const sortedScreens = React.useMemo(() => {
    const arr = [...screens];
    if (sortBy === 'added') {
      arr.sort((a, b) => (b.registeredAt || 0) - (a.registeredAt || 0));
    } else if (sortBy === 'updated') {
      // smaller secondsSinceHeartbeat = seen more recently; never-seen last
      const r = (x) => (x.secondsSinceHeartbeat == null ? Infinity : x.secondsSinceHeartbeat);
      arr.sort((a, b) => r(a) - r(b));
    } else {
      arr.sort((a, b) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base', numeric: true }));
    }
    return arr;
  }, [screens, sortBy]);
  // v0.1.81: filter by concept. Distinct concepts present in this store drive
  // the dropdown; 'all' shows everything. Kept in-memory (resets per visit) so
  // a stale filter can't silently hide screens on a later navigation.
  const concepts = React.useMemo(() => {
    const set = new Set(screens.map(s => s.concept).filter(Boolean));
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }, [screens]);
  const [conceptFilter, setConceptFilter] = React.useState('all');
  React.useEffect(() => {
    if (conceptFilter !== 'all' && !concepts.includes(conceptFilter)) setConceptFilter('all');
  }, [concepts, conceptFilter]);
  // Free-text search over name / concept / currently-playing content.
  // Kept in-memory (resets per visit) like the concept filter above.
  const [query, setQuery] = React.useState('');
  const visibleScreens = React.useMemo(() => {
    const byConcept = conceptFilter === 'all' ? sortedScreens : sortedScreens.filter(s => s.concept === conceptFilter);
    const q = query.trim().toLowerCase();
    if (!q) return byConcept;
    return byConcept.filter(s =>
      [s.name, s.concept, s.playing].some(f => (f || '').toLowerCase().includes(q)),
    );
  }, [sortedScreens, conceptFilter, query]);
  // v0.1.57: read viewport so the in-store grid + stats strip
  // collapse on mobile (≤640 px). Previously the grid hard-coded
  // 4 columns, which produced 80-px-wide tiles on a phone.
  const vp = useViewport();
  const isMobile = vp.tier === 'mobile';

  return (
    <AppShell current="screens">
      <PageHeader
        crumbs={[{ label: 'Screens', href: '/screens' }, store.name]}
        title={store.name}
        subtitle={`${store.city} · ${store.country} · ${store.region}`}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: isMobile ? '14px 12px 32px' : '20px 24px 40px' }}>
        {/* Store stats strip */}
        <div style={{ display: 'flex', gap: 10, marginBottom: 18, flexWrap: 'wrap' }}>
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
          <Input placeholder="Search screens or content…" value={query} onChange={(e) => setQuery(e.target.value)} leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 160, maxWidth: 260 }} />
          {/* v0.1.81: concept filter — only shown when this store has concepts. */}
          {concepts.length > 0 && (
            <select
              value={conceptFilter}
              onChange={(e) => setConceptFilter(e.target.value)}
              title="Filter by concept"
              style={{
                height: 28, fontSize: 12, color: 'var(--ink-1)',
                background: 'var(--ink-10)', border: 'var(--border-strong)',
                borderRadius: 2, padding: '0 8px', cursor: 'pointer', flexShrink: 0,
              }}>
              <option value="all">All concepts</option>
              {concepts.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          )}
          {/* v0.1.81: sort control. Native select for robustness/accessibility. */}
          <select
            value={sortBy}
            onChange={(e) => setSort(e.target.value)}
            title="Sort screens"
            style={{
              height: 28, fontSize: 12, color: 'var(--ink-1)',
              background: 'var(--ink-10)', border: 'var(--border-strong)',
              borderRadius: 2, padding: '0 8px', cursor: 'pointer', flexShrink: 0,
            }}>
            <option value="name">Sort: Name (A–Z)</option>
            <option value="added">Sort: Recently added</option>
            <option value="updated">Sort: Recently updated</option>
          </select>
          {/* v0.1.56: view toggle is now functional + remembers the choice. */}
          <Button
            variant={viewMode === 'grid' ? 'secondary' : 'ghost'}
            size="sm"
            icon={<Icon.grid size={13} />}
            onClick={() => setView('grid')}
            title="Grid view"
          />
          <Button
            variant={viewMode === 'list' ? 'secondary' : 'ghost'}
            size="sm"
            icon={<Icon.list size={13} />}
            onClick={() => setView('list')}
            title="List view"
          />
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
        ) : visibleScreens.length === 0 ? (
          <div style={{ padding: '40px 16px', border: 'var(--border)', borderRadius: 12, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
            {query.trim()
              ? <>No screens match “{query.trim()}”.
                  <button onClick={() => setQuery('')} style={{ marginLeft: 8, color: 'var(--ink-2)', textDecoration: 'underline', cursor: 'pointer' }}>Clear search</button></>
              : <>No screens with concept “{conceptFilter}”.
                  <button onClick={() => setConceptFilter('all')} style={{ marginLeft: 8, color: 'var(--ink-2)', textDecoration: 'underline', cursor: 'pointer' }}>Clear filter</button></>}
          </div>
        ) : viewMode === 'list' ? (
          <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)', overflow: 'hidden' }}>
            {visibleScreens.map(s => (
              <ScreenRow
                key={s.id}
                s={s}
                selected={selected.has(s.id)}
                onToggle={() => toggle(s.id)}
                onOpen={() => navigate(`/screens/${store.id}/${s.id}`)}
              />
            ))}
          </div>
        ) : (
          <div style={{
            display: 'grid',
            // v0.1.57: was hard-coded 4-col which produced ~80px
            // tiles on a phone. auto-fill with a 150px minimum gives
            // 2 columns at 380 px, 4 columns at desktop.
            gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
            gap: 12,
          }}>
            {visibleScreens.map(s => <ScreenCard key={s.id} s={s} selected={selected.has(s.id)} onToggle={() => toggle(s.id)} onOpen={() => navigate(`/screens/${store.id}/${s.id}`)} />)}
          </div>
        )}

        {selected.size > 0 && (
          <div style={{
            // v0.1.57: sidebar collapses to a drawer at ≤1024 px,
            // so the selection bar is centred on the full viewport
            // there. Above the tablet breakpoint the original offset
            // still applies so the bar doesn't sit on top of the
            // sidebar.
            position: 'fixed', bottom: 24,
            left: vp.isCompact ? '50%' : 'calc(var(--sidebar-w) + 50%)',
            transform: 'translateX(-50%)',
            maxWidth: 'calc(100% - 24px)',
            background: 'var(--ink-0)', color: 'var(--on-accent)',
            borderRadius: 10, padding: '8px 8px 8px 16px',
            display: 'flex', alignItems: 'center', gap: 12,
            boxShadow: '0 8px 24px rgba(9,9,11,0.18)',
            zIndex: 5,
          }}>
            <span style={{ fontSize: 12, fontWeight: 500 }} className="tnum">{selected.size} selected</span>
            <button onClick={() => setSelected(new Set())} style={{ fontSize: 12, color: 'rgba(250,250,250,0.7)', padding: '4px 6px', cursor: 'pointer' }}>Clear</button>
            {/* Content is chosen in the library, then pushed to screens via the
                PushPicker. Jump there so the operator can tick videos and push. */}
            <button onClick={() => navigate('/library')} style={{ background: 'var(--ink-10)', color: 'var(--ink-0)', padding: '6px 12px', borderRadius: 6, fontSize: 12, fontWeight: 500, cursor: 'pointer' }}>Push content →</button>
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
  const stores = allStores().map((s) => {
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
  // Free-text search over store name / city / region, case-insensitive.
  const [query, setQuery] = React.useState('');
  const visibleStores = React.useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return stores;
    return stores.filter(s =>
      [s.name, s.city, s.region].some(f => (f || '').toLowerCase().includes(q)),
    );
  }, [stores, query]);
  const vp = useViewport();
  const isMobile = vp.tier === 'mobile';
  return (
    <AppShell current="screens">
      <PageHeader
        title="Screens"
        subtitle={subtitleParts.join(' · ')}
        actions={<Button variant="primary" size="sm" icon={<Icon.plus size={13} />} onClick={() => navigate('/settings?tab=locations')}>Add store</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: isMobile ? '14px 12px 32px' : '20px 24px 40px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18, flexWrap: 'wrap' }}>
          <Input placeholder="Search stores or cities…" value={query} onChange={(e) => setQuery(e.target.value)} leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 180, maxWidth: 300 }} />
          <span style={{ flex: 1 }} className="scr-mobile-hide" />
        </div>
        {visibleStores.length === 0 ? (
          <div style={{ padding: '40px 16px', border: 'var(--border)', borderRadius: 12, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
            No stores match “{query.trim()}”.
            <button onClick={() => setQuery('')} style={{ marginLeft: 8, color: 'var(--ink-2)', textDecoration: 'underline', cursor: 'pointer' }}>Clear search</button>
          </div>
        ) : (
        <div style={{ border: 'var(--border)', borderRadius: 12, background: 'var(--ink-10)', overflow: 'hidden' }}>
          {visibleStores.map((store) => {
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
        )}
      </div>
    </AppShell>
  );
};

Object.assign(window, { ScreensStoreView, StoresIndex });
