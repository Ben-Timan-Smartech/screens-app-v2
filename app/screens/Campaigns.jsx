/* eslint-disable */
// Campaigns — the operator surface for scheduled, location-targeted
// playlist overlays. Each campaign has a time window + optional daypart
// + a store scope; clips uploaded to _campaigns/<slug>/ in Drive carry
// the campaign reference, and the server's /api/state resolver injects
// them into the matching screens' loop during live windows.
//
// Routed at /campaigns; sidebar entry gated on campaigns.view.

const { useState, useEffect, useMemo } = React;

// ─────────────────────────────────────────────────────────────
// useCampaigns — polls /api/campaigns every 30s. Also triggers a
// manual refresh after mutations so the list reflects the write
// without waiting for the next tick.
// ─────────────────────────────────────────────────────────────
const useCampaigns = () => {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const r = await fetch('/api/campaigns', { credentials: 'include' });
        if (!r.ok) throw new Error('http ' + r.status);
        const data = await r.json();
        if (!cancelled) setItems(data.campaigns || []);
      } catch (_) { /* keep last */ }
      finally { if (!cancelled) setLoading(false); }
    };
    load();
    const id = setInterval(load, 30000);
    return () => { cancelled = true; clearInterval(id); };
  }, [tick]);
  return { items, loading, refresh: () => setTick((t) => t + 1) };
};

// Status chip — upcoming / live / past, derived purely from
// startAt/endAt against now. isActiveNow from the server also factors
// daypart; we read it as the authoritative "live" bit.
const statusOf = (c) => {
  if (c.isActiveNow) return 'live';
  const now = Date.now();
  const start = c.startAt ? Date.parse(c.startAt) : 0;
  const end   = c.endAt   ? Date.parse(c.endAt)   : 0;
  if (end && now > end) return 'past';
  if (start && now < start) return 'upcoming';
  return 'inactive';
};

const StatusChip = ({ status }) => {
  const map = {
    live:     { bg: 'var(--warn-bg)', fg: 'var(--warn)',     label: 'Live now', pulse: true  },
    upcoming: { bg: 'var(--info-bg)', fg: 'var(--info)',     label: 'Upcoming', pulse: false },
    past:     { bg: 'var(--ink-9)',   fg: 'var(--ink-4)',    label: 'Past',     pulse: false },
    inactive: { bg: 'var(--ink-9)',   fg: 'var(--ink-4)',    label: 'Idle',     pulse: false },
  };
  const s = map[status] || map.inactive;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      padding: '2px 7px', borderRadius: 2,
      background: s.bg, color: s.fg,
      fontSize: 10, fontWeight: 500, textTransform: 'uppercase', letterSpacing: '0.1em',
      fontFamily: 'var(--font-mono)',
    }}>
      {s.pulse && (
        <span aria-hidden style={{
          width: 5, height: 5, borderRadius: '50%',
          background: s.fg,
          animation: 'sidebar-live-pulse 2s ease-in-out infinite',
        }} />
      )}
      {s.label}
    </span>
  );
};

// Short human summary of a campaign's targeting + schedule — the
// secondary line under each row's name.
const campaignSummary = (c) => {
  const parts = [];
  const fmt = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  };
  parts.push(`${fmt(c.startAt)} → ${fmt(c.endAt)}`);
  if (c.daypart) {
    const toClock = (min) => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
    parts.push(`${toClock(c.daypart.startMin)}–${toClock(c.daypart.endMin)}`);
  }
  if (c.locationScope === 'all') {
    parts.push('All screens');
  } else {
    const n = (c.stores || []).length;
    parts.push(`${n} store${n === 1 ? '' : 's'}`);
  }
  if ((c.brands || []).length) {
    parts.push(`${c.brands.length} brand${c.brands.length === 1 ? '' : 's'}`);
  }
  return parts.join(' · ');
};

// Row in the campaigns list.
const CampaignRow = ({ campaign, onEdit, onDelete }) => (
  <div style={{
    display: 'flex', alignItems: 'center', gap: 14,
    padding: '14px 20px',
    borderBottom: 'var(--border-faint)',
    cursor: 'default',
  }}>
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 3 }}>
        <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.01em' }}>
          {campaign.name}
        </span>
        <StatusChip status={statusOf(campaign)} />
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', fontFamily: 'var(--font-mono)', letterSpacing: '0.04em' }}>
        {campaignSummary(campaign)}
      </div>
    </div>
    <Button variant="ghost" size="sm" onClick={() => onEdit(campaign)}>Edit</Button>
    <Button variant="danger" size="sm" onClick={() => onDelete(campaign)}>Delete</Button>
  </div>
);

// ─────────────────────────────────────────────────────────────
// Create / edit modal. One form; `initial` is a full campaign on edit
// or null on create. Server validates on submit; we show inline errors.
// ─────────────────────────────────────────────────────────────
const CampaignModal = ({ initial, stores, brands, onClose, onSaved }) => {
  const isEdit = !!initial;
  const toISODate = (iso) => iso ? new Date(iso).toISOString().slice(0, 10) : '';
  const toISODateTime = (iso) => {
    if (!iso) return '';
    // Trim seconds + TZ for datetime-local input shape (YYYY-MM-DDTHH:mm).
    const d = new Date(iso);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const fromLocalDateTime = (s) => s ? new Date(s).toISOString() : null;

  const [name, setName] = useState(initial?.name || '');
  const [startAt, setStartAt] = useState(toISODateTime(initial?.startAt));
  const [endAt,   setEndAt]   = useState(toISODateTime(initial?.endAt));
  const [daypartOn, setDaypartOn] = useState(!!initial?.daypart);
  const [dpStart, setDpStart] = useState(initial?.daypart ? Math.floor(initial.daypart.startMin / 60) * 60 + (initial.daypart.startMin % 60) : 540);
  const [dpEnd,   setDpEnd]   = useState(initial?.daypart ? Math.floor(initial.daypart.endMin / 60) * 60 + (initial.daypart.endMin % 60) : 1260);
  const [scope, setScope] = useState(initial?.locationScope || 'all');
  const [selectedStores, setSelectedStores] = useState(initial?.stores || []);
  const [selectedBrands, setSelectedBrands] = useState(initial?.brands || []);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  const toggle = (list, setList, id) => () => {
    setList(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);
  };

  const toClockInput = (totalMin) => {
    const h = Math.floor(totalMin / 60), m = totalMin % 60;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  };
  const fromClockInput = (s) => {
    const [h, m] = (s || '00:00').split(':').map((x) => parseInt(x, 10) || 0);
    return h * 60 + m;
  };

  const submit = async () => {
    setError(null);
    if (!name.trim()) { setError('Name is required'); return; }
    if (!startAt || !endAt) { setError('Start and end dates are required'); return; }
    if (scope === 'stores' && selectedStores.length === 0) {
      setError('Pick at least one store, or switch to All screens');
      return;
    }
    const payload = {
      name: name.trim(),
      startAt: fromLocalDateTime(startAt),
      endAt:   fromLocalDateTime(endAt),
      daypart: daypartOn ? { startMin: dpStart, endMin: dpEnd } : null,
      locationScope: scope,
      stores: scope === 'stores' ? selectedStores : [],
      brands: selectedBrands,
    };
    setSaving(true);
    try {
      const url = isEdit ? `/api/campaigns/${encodeURIComponent(initial.id)}` : '/api/campaigns';
      const method = isEdit ? 'PATCH' : 'POST';
      const r = await fetch(url, {
        method, credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) {
        setError(data.detail || data.error || `Save failed (${r.status})`);
        return;
      }
      onSaved(data.campaign);
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const field = (label, children) => (
    <div style={{ marginBottom: 14 }}>
      <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: '0.14em', marginBottom: 6 }}>{label}</div>
      {children}
    </div>
  );

  const inputStyle = {
    width: '100%', padding: '8px 10px', fontSize: 13,
    background: 'var(--ink-10)', color: 'var(--ink-1)',
    border: 'var(--border-strong)', borderRadius: 3, fontFamily: 'inherit',
  };

  const checkRow = (checked, onChange, label) => (
    <label style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px',
      borderRadius: 3, cursor: 'pointer',
      background: checked ? 'var(--ink-9)' : 'transparent',
      fontSize: 13, color: 'var(--ink-2)',
    }}>
      <input type="checkbox" checked={checked} onChange={onChange} style={{ margin: 0 }} />
      <span>{label}</span>
    </label>
  );

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 100,
      background: 'rgba(7, 9, 11, 0.6)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 20,
    }} onClick={onClose}>
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 'min(560px, 100%)', maxHeight: '90vh', overflow: 'auto',
          background: 'var(--ink-10)', border: 'var(--border-strong)',
          borderRadius: 8, boxShadow: '0 24px 64px -12px rgba(0,0,0,0.5)',
        }}>
        <div style={{
          padding: '16px 20px', borderBottom: 'var(--border)',
          display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
        }}>
          <h2 style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.02em', margin: 0 }}>
            {isEdit ? `Edit "${initial.name}"` : 'New campaign'}
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--ink-3)', cursor: 'pointer', fontSize: 18 }}>×</button>
        </div>

        <div style={{ padding: '18px 20px' }}>
          {field('Name', (
            <input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Black Friday 2026" style={inputStyle} />
          ))}

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            {field('Starts', (
              <input type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} style={inputStyle} />
            ))}
            {field('Ends', (
              <input type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} style={inputStyle} />
            ))}
          </div>

          {field('Daypart', (
            <div>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ink-2)', marginBottom: 8 }}>
                <input type="checkbox" checked={daypartOn} onChange={(e) => setDaypartOn(e.target.checked)} style={{ margin: 0 }} />
                Only run during a specific time of day
              </label>
              {daypartOn && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <input type="time" value={toClockInput(dpStart)} onChange={(e) => setDpStart(fromClockInput(e.target.value))} style={inputStyle} />
                  <input type="time" value={toClockInput(dpEnd)}   onChange={(e) => setDpEnd(fromClockInput(e.target.value))}   style={inputStyle} />
                </div>
              )}
            </div>
          ))}

          {field('Locations', (
            <div>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ink-2)', marginBottom: 6 }}>
                <input type="radio" checked={scope === 'all'} onChange={() => setScope('all')} style={{ margin: 0 }} />
                All screens globally
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ink-2)', marginBottom: 6 }}>
                <input type="radio" checked={scope === 'stores'} onChange={() => setScope('stores')} style={{ margin: 0 }} />
                Specific stores
              </label>
              {scope === 'stores' && (
                <div style={{
                  marginTop: 6, maxHeight: 180, overflow: 'auto',
                  border: 'var(--border)', borderRadius: 3, padding: 6,
                  display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 2,
                }}>
                  {stores.length === 0
                    ? <div style={{ padding: 10, fontSize: 12, color: 'var(--ink-4)' }}>No stores available.</div>
                    : stores.map((st) => checkRow(
                        selectedStores.includes(st.id),
                        toggle(selectedStores, setSelectedStores, st.id),
                        st.name || st.id,
                      ))}
                </div>
              )}
            </div>
          ))}

          {field('Related brands (optional)', (
            <div style={{
              maxHeight: 140, overflow: 'auto',
              border: 'var(--border)', borderRadius: 3, padding: 6,
              display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 2,
            }}>
              {brands.length === 0
                ? <div style={{ padding: 10, fontSize: 12, color: 'var(--ink-4)' }}>No brands yet.</div>
                : brands.map((b) => checkRow(
                    selectedBrands.includes(b.id),
                    toggle(selectedBrands, setSelectedBrands, b.id),
                    b.name || b.id,
                  ))}
            </div>
          ))}

          {error && (
            <div style={{
              padding: '10px 12px', background: 'var(--err-bg)', color: 'var(--err)',
              borderRadius: 3, fontSize: 12, marginBottom: 12,
            }}>{error}</div>
          )}
        </div>

        <div style={{
          padding: '12px 20px', borderTop: 'var(--border)',
          display: 'flex', justifyContent: 'flex-end', gap: 8,
        }}>
          <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
          <Button variant="primary" size="sm" onClick={submit} disabled={saving}>
            {saving ? 'Saving…' : (isEdit ? 'Save changes' : 'Create campaign')}
          </Button>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Campaigns page.
// ─────────────────────────────────────────────────────────────
const Campaigns = () => {
  const { items, loading, refresh } = useCampaigns();
  const vp = useViewport();
  const compact = vp.isCompact;
  const [editing, setEditing] = useState(null);   // null | {campaign object} | 'new'

  // Stores for the location picker. Prefer the server-sourced list the
  // CMS already keeps in sync; fall back to the hardcoded MOCK_STORES.
  const [stores, setStores] = useState(() => Array.isArray(window.MOCK_STORES) ? window.MOCK_STORES : []);
  useEffect(() => {
    let cancelled = false;
    fetch('/api/stores', { credentials: 'include' })
      .then((r) => r.ok ? r.json() : null)
      .then((data) => { if (data?.stores && !cancelled) setStores(data.stores); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);
  // Brands derived from the live library — real brands only.
  const brands = useMemo(() => {
    const seen = new Map();
    for (const v of (window.MOCK_VIDEOS || [])) {
      const name = v?.brand;
      if (!name) continue;
      if (!seen.has(name)) seen.set(name, { id: name.toLowerCase().replace(/\s+/g, '-'), name });
    }
    return [...seen.values()];
  }, []);

  const confirmDelete = async (camp) => {
    if (!window.confirm(`Delete "${camp.name}"?\n\nClips in _campaigns/${camp.id}/ keep their contentType=campaign tag but stop injecting on screens. Reversible by re-creating a campaign with the same id.`)) return;
    try {
      const r = await fetch(`/api/campaigns/${encodeURIComponent(camp.id)}`, { method: 'DELETE', credentials: 'include' });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      refresh();
      if (typeof showToast === 'function') showToast(`Deleted "${camp.name}"`);
    } catch (e) {
      alert(`Delete failed: ${e}`);
    }
  };

  const grouped = useMemo(() => {
    const live = [], upcoming = [], past = [], idle = [];
    for (const c of items) {
      const s = statusOf(c);
      if (s === 'live') live.push(c);
      else if (s === 'upcoming') upcoming.push(c);
      else if (s === 'past') past.push(c);
      else idle.push(c);
    }
    return { live, upcoming, past, idle };
  }, [items]);

  const section = (label, rows) => rows.length === 0 ? null : (
    <div style={{ marginBottom: 24 }}>
      <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.14em', color: 'var(--ink-4)', fontWeight: 500, marginBottom: 10 }}>
        {label} · {rows.length}
      </div>
      <div style={{ border: 'var(--border)', borderRadius: 8, background: 'var(--ink-10)' }}>
        {rows.map((c, i) => (
          <CampaignRow
            key={c.id}
            campaign={c}
            onEdit={setEditing}
            onDelete={confirmDelete}
          />
        ))}
      </div>
    </div>
  );

  return (
    <AppShell current="campaigns">
      <PageHeader
        title="Campaigns"
        subtitle="Scheduled, location-targeted playlist overlays"
        actions={(
          <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setEditing('new')}>
            New campaign
          </Button>
        )}
      />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: compact ? '16px 14px 32px' : '24px 28px 40px',
      }}>
        {loading && items.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>Loading…</div>
        ) : items.length === 0 ? (
          <div style={{
            padding: 40, textAlign: 'center',
            border: 'var(--border)', borderRadius: 8, background: 'var(--ink-10)',
            color: 'var(--ink-3)', fontSize: 13,
          }}>
            <div style={{ marginBottom: 6, fontWeight: 500, color: 'var(--ink-1)' }}>No campaigns yet</div>
            <div style={{ color: 'var(--ink-4)', fontSize: 12 }}>
              Create a campaign to schedule a timed overlay on top of the brand loop — Black Friday, store openings, seasonal promos.
            </div>
            <div style={{ marginTop: 14 }}>
              <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setEditing('new')}>
                New campaign
              </Button>
            </div>
          </div>
        ) : (
          <div>
            {section('Live now', grouped.live)}
            {section('Upcoming', grouped.upcoming)}
            {section('Idle (no schedule)', grouped.idle)}
            {section('Past', grouped.past)}
          </div>
        )}
      </div>
      {editing && (
        <CampaignModal
          initial={editing === 'new' ? null : editing}
          stores={stores}
          brands={brands}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); refresh(); }}
        />
      )}
    </AppShell>
  );
};

Object.assign(window, { Campaigns });
