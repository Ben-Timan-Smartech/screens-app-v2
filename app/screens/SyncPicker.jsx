/* eslint-disable */
// Sync-to-screens modal — nested tree: stores → screens. Deep spec screen.

const SyncPicker = ({ onClose }) => {
  const [expanded, setExpanded] = React.useState(new Set(['sf-london', 'saks-fifth']));
  const [checked, setChecked] = React.useState(new Set(['sf-london/s1', 'sf-london/s2', 'sf-london/s3', 'saks-fifth']));
  const [query, setQuery] = React.useState('');

  // Generate 3 screens per store for the picker
  const storeScreens = (storeId) => [
    { id: `${storeId}/s1`, name: 'Entrance · left', orient: 'landscape', brand: 'SONOS' },
    { id: `${storeId}/s2`, name: 'Sonos · Era wall', orient: 'portrait', brand: 'SONOS' },
    { id: `${storeId}/s3`, name: 'Sonos · Arc bar', orient: 'landscape', brand: 'SONOS' },
    { id: `${storeId}/s4`, name: 'Bose · end cap', orient: 'portrait', brand: 'Bose' },
  ];

  const toggleStore = (sid) => {
    const n = new Set(expanded);
    n.has(sid) ? n.delete(sid) : n.add(sid);
    setExpanded(n);
  };
  const toggleCheck = (id) => {
    const n = new Set(checked);
    n.has(id) ? n.delete(id) : n.add(id);
    setChecked(n);
  };

  const totalScreens = Array.from(checked).reduce((acc, c) => {
    if (!c.includes('/')) return acc + 4; // whole store
    return acc + 1;
  }, 0);

  // Conflict: mixed orientations in selection
  const conflicts = 2;

  return (
    <div style={{
      position: 'absolute', inset: 0, background: 'rgba(9,9,11,0.4)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 20, padding: 20,
    }}>
      <div style={{
        width: 560, maxHeight: '85%', background: 'var(--ink-10)',
        borderRadius: 14, border: 'var(--border)',
        display: 'flex', flexDirection: 'column',
        boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
      }}>
        <div style={{ padding: '16px 20px 14px', borderBottom: 'var(--border)', display: 'flex', alignItems: 'flex-start', gap: 12 }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 15, fontWeight: 500, color: 'var(--ink-1)' }}>Sync playlist to other screens</div>
            <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>Copy 6 videos from Sonos · Era wall to selected screens</div>
          </div>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} />
        </div>

        <div style={{ padding: '12px 20px', borderBottom: 'var(--border)', display: 'flex', gap: 8 }}>
          <Input placeholder="Search by store, screen, or brand…" leadingIcon={<Icon.search size={13} />} size="sm" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1 }} />
          <Button variant="secondary" size="sm" icon={<Icon.filter size={12} />}>Filter</Button>
        </div>

        <div style={{ flex: 1, overflow: 'auto', padding: '4px 8px 8px' }}>
          {MOCK_STORES.slice(0, 6).map((store) => {
            const isOpen = expanded.has(store.id);
            const wholeStoreChecked = checked.has(store.id);
            return (
              <div key={store.id} style={{ padding: '2px 0' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderRadius: 6 }}>
                  <button onClick={() => toggleStore(store.id)} style={{ color: 'var(--ink-4)', display: 'flex', padding: 2 }}>
                    {isOpen ? <Icon.chevD size={12} /> : <Icon.chevR size={12} />}
                  </button>
                  <div onClick={() => toggleCheck(store.id)} style={{
                    width: 16, height: 16, borderRadius: 4,
                    border: wholeStoreChecked ? 'none' : '1.5px solid var(--ink-6)',
                    background: wholeStoreChecked ? 'var(--ink-0)' : 'var(--ink-10)',
                    color: 'var(--on-accent)', cursor: 'pointer',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}>{wholeStoreChecked && <Icon.check size={11} />}</div>
                  <Icon.store size={14} />
                  <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', flex: 1 }}>{store.name}</span>
                  <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{store.total} screens · {store.region}</span>
                </div>
                {isOpen && (
                  <div style={{ paddingLeft: 46, display: 'flex', flexDirection: 'column', gap: 1 }}>
                    {storeScreens(store.id).map((scr) => {
                      const sel = checked.has(scr.id) || wholeStoreChecked;
                      const isConflict = scr.orient === 'landscape' && store.id === 'sf-london' && scr.id.endsWith('s1');
                      return (
                        <div key={scr.id} onClick={() => toggleCheck(scr.id)} style={{
                          display: 'flex', alignItems: 'center', gap: 8, padding: '5px 10px', borderRadius: 5, cursor: 'pointer',
                          opacity: wholeStoreChecked ? 0.65 : 1,
                        }}>
                          <div style={{
                            width: 14, height: 14, borderRadius: 3,
                            border: sel ? 'none' : '1.5px solid var(--ink-6)',
                            background: sel ? 'var(--ink-0)' : 'var(--ink-10)',
                            color: 'var(--on-accent)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                          }}>{sel && <Icon.check size={10} />}</div>
                          {scr.orient === 'portrait' ? <Icon.device size={12} /> : <Icon.deviceLand size={12} />}
                          <span style={{ fontSize: 12, color: 'var(--ink-2)', flex: 1 }}>{scr.name}</span>
                          {isConflict && <Chip tone="warn">Conflict</Chip>}
                          <Chip>{scr.brand}</Chip>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {conflicts > 0 && (
          <div style={{ padding: '10px 20px', background: 'var(--warn-bg)', borderTop: 'var(--border)', display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <span style={{ color: 'var(--warn)', marginTop: 1 }}><Icon.warning size={14} /></span>
            <div style={{ flex: 1, fontSize: 12, color: 'var(--warn)', lineHeight: 1.45 }}>
              <span style={{ fontWeight: 500 }}>{conflicts} screens have conflicts.</span> 1 has a different orientation · 1 has an overlapping schedule. You can continue and these will still be updated.
            </div>
            <Button variant="ghost" size="sm">Review</Button>
          </div>
        )}

        <div style={{ padding: '14px 20px', borderTop: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ flex: 1, fontSize: 12, color: 'var(--ink-4)' }}>
            <span className="tnum" style={{ color: 'var(--ink-1)', fontWeight: 500 }}>{totalScreens}</span> screen{totalScreens !== 1 ? 's' : ''} selected across <span className="tnum">{checked.size}</span> {checked.size === 1 ? 'location' : 'locations'}
          </div>
          <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
          <Button variant="primary" size="sm" icon={<Icon.sync size={12} />}>Copy to {totalScreens} screens</Button>
        </div>
      </div>
    </div>
  );
};

Object.assign(window, { SyncPicker });
