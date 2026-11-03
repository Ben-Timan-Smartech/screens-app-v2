/* eslint-disable */
// Events — the operator surface for temporary TAKEOVERS on screens.
// Carries PR #177's data model: event has splashes (portrait + landscape),
// optional per-screen assignments, a master splashOn toggle, and a
// lifecycle: draft -> signed-off (preload begins) -> active -> ended.
//
// Routed at /events. Sidebar entry gated on events.view.

const { useState, useEffect, useMemo } = React;

// ─────────────────────────────────────────────────────────────
// useEvents — polls /api/events every 30s. Manual refresh after
// mutations so the list reflects writes without waiting for the tick.
// ─────────────────────────────────────────────────────────────
const useEvents = () => {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const r = await fetch('/api/events', { credentials: 'include' });
        if (!r.ok) throw new Error('http ' + r.status);
        const data = await r.json();
        if (!cancelled) setItems(data.events || []);
      } catch (_) { /* keep last */ }
      finally { if (!cancelled) setLoading(false); }
    };
    load();
    const id = setInterval(load, 30000);
    return () => { cancelled = true; clearInterval(id); };
  }, [tick]);
  return { items, loading, refresh: () => setTick((t) => t + 1) };
};

// ─────────────────────────────────────────────────────────────
// Status helpers. Four buckets, from most urgent to least:
//   live (state=active)      — broadcast amber pulse
//   signed-off               — info blue, preload advertised
//   draft (incomplete)       — neutral grey
//   past (ended)             — muted
// ─────────────────────────────────────────────────────────────
const eventStatusOf = (e) => {
  const s = e.state;
  if (s === 'active') return 'live';
  if (s === 'signed-off') return 'signed-off';
  if (s === 'ended') return 'past';
  return 'draft';
};

const EventChip = ({ event }) => {
  const s = eventStatusOf(event);
  const map = {
    live:         { bg: 'var(--warn-bg)', fg: 'var(--warn)', label: 'Live', pulse: true  },
    'signed-off': { bg: 'var(--info-bg)', fg: 'var(--info)', label: 'Signed off', pulse: false },
    draft:        { bg: 'var(--ink-9)',   fg: 'var(--ink-4)', label: 'Draft', pulse: false },
    past:         { bg: 'transparent',    fg: 'var(--ink-5)', label: 'Ended', pulse: false },
  };
  const m = map[s];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      padding: '2px 8px', borderRadius: 2,
      background: m.bg, color: m.fg,
      fontSize: 10, fontWeight: 500, textTransform: 'uppercase', letterSpacing: '0.1em',
      fontFamily: 'var(--font-mono)',
      border: s === 'past' || s === 'draft' ? 'var(--border)' : 'none',
    }}>
      {m.pulse && (
        <span aria-hidden style={{
          width: 5, height: 5, borderRadius: '50%',
          background: m.fg,
          animation: 'sidebar-live-pulse 2s ease-in-out infinite',
        }} />
      )}
      {m.label}
    </span>
  );
};

// Readiness flag — the attention line on each row, broadcast amber only
// when the event cannot start as configured.
const readinessOf = (e) => {
  if (e.state === 'active') return { tone: 'ok', text: 'Running' };
  if (e.state === 'ended')  return { tone: 'muted', text: 'Restored cleanly' };
  const sp = e.splashes || {};
  const hasSp = sp.portrait && sp.landscape;
  const targets = e.effectiveScreenCount || 0;
  if (!hasSp && !targets) return { tone: 'warn', text: 'Splashes + targets missing' };
  if (!hasSp)              return { tone: 'warn', text: 'Splashes missing' };
  if (!targets)            return { tone: 'warn', text: 'No screens picked' };
  if (e.state === 'signed-off') return { tone: 'ok', text: 'Preloading to targets' };
  return { tone: 'muted', text: 'Ready to sign off' };
};

// ─────────────────────────────────────────────────────────────
// Row + list
// ─────────────────────────────────────────────────────────────
const EventRow = ({ event, selected, onOpen, onAction }) => {
  const r = readinessOf(event);
  const fmt = (iso) => iso
    ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
  const dayCount = (() => {
    if (!event.startAt || !event.endAt) return null;
    const ms = new Date(event.endAt) - new Date(event.startAt);
    const d = Math.round(ms / 86400000);
    return d > 0 ? `${d} day${d === 1 ? '' : 's'}` : null;
  })();
  const targetLabel = (() => {
    if (event.targetMode === 'screens') {
      const n = (event.screens || []).length;
      return n > 0 ? `${n} screen${n === 1 ? '' : 's'}` : 'No screens picked';
    }
    const stores = event.stores || [];
    if (stores.length === 0) return 'No stores picked';
    if (stores.length === 1) return stores[0];
    return `${stores.length} stores`;
  })();
  return (
    <div
      onClick={() => onOpen(event)}
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1.4fr) 92px minmax(0, 1fr) 160px auto',
        alignItems: 'center', gap: 18,
        padding: '14px 20px',
        borderBottom: 'var(--border-faint)',
        background: selected ? 'var(--ink-8)' : 'transparent',
        boxShadow: selected ? 'inset 2px 0 0 0 var(--accent)' : 'none',
        cursor: 'pointer',
      }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
        <span style={{
          fontSize: 13, fontWeight: 500, color: 'var(--ink-1)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{event.name}</span>
        <span style={{
          fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-5)',
          padding: '2px 6px', border: 'var(--border)', borderRadius: 2, flexShrink: 0,
        }}>{event.id}</span>
      </div>
      <EventChip event={event} />
      <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--ink-3)', fontVariantNumeric: 'tabular-nums' }}>
        <div style={{ color: 'var(--ink-2)' }}>
          {event.startAt && event.endAt
            ? `${fmt(event.startAt)} → ${fmt(event.endAt)}`
            : 'Manual · no window'}
        </div>
        <div style={{ color: 'var(--ink-5)', fontSize: 10, marginTop: 2 }}>
          {dayCount}{event.daypart ? ' · daypart on' : ''}
        </div>
      </div>
      <div style={{ fontSize: 12, color: 'var(--ink-3)', display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        <Icon.screens size={13} />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{targetLabel}</span>
        {event.effectiveScreenCount != null && (
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--ink-2)', marginLeft: 'auto' }}>
            {event.effectiveScreenCount}
          </span>
        )}
      </div>
      <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end', alignItems: 'center' }}>
        {r.tone === 'warn' && (
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 5,
            fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--warn)',
            letterSpacing: '0.05em',
          }}>
            <span style={{ width: 5, height: 5, borderRadius: '50%', background: 'var(--warn)' }} />
            {r.text}
          </span>
        )}
        {r.tone === 'ok' && event.state !== 'active' && (
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-4)' }}>
            {r.text}
          </span>
        )}
        {event.state === 'active' && (
          <Button variant="danger" size="sm" onClick={(e) => { e.stopPropagation(); onAction('stop', event); }}>End now</Button>
        )}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Detail rail — opens inline on the right of the list.
// ─────────────────────────────────────────────────────────────
const DetailRail = ({ event, onClose, onAction, onEditTiming }) => {
  const r = readinessOf(event);
  const fmtFull = (iso) => iso
    ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
  // Starts-in-N countdown — only for signed-off / draft with a window.
  const starts = event.startAt ? new Date(event.startAt) - Date.now() : null;
  const startsInLabel = (() => {
    if (!starts || starts <= 0) return null;
    const h = Math.floor(starts / 3600000);
    const d = Math.floor(h / 24);
    if (d > 0) return `in ${d}d ${h % 24}h`;
    const m = Math.floor((starts % 3600000) / 60000);
    return `in ${h}h ${m}m`;
  })();
  const sp = event.splashes || {};
  const assignments = event.screenAssignments || {};
  const assignedIds = Object.keys(assignments);
  return (
    <aside style={{
      borderLeft: 'var(--border)', background: 'var(--ink-10)',
      padding: '24px 24px 32px', overflowY: 'auto',
      display: 'flex', flexDirection: 'column', gap: 20,
    }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <button onClick={onClose} style={{
          padding: '5px 8px', border: 'var(--border)', borderRadius: 3,
          fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-3)',
          background: 'transparent', cursor: 'pointer', flexShrink: 0,
        }}>← Esc</button>
        <div style={{ minWidth: 0, flex: 1 }}>
          <h2 style={{ fontSize: 17, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.015em', lineHeight: 1.25, margin: 0 }}>
            {event.name}
          </h2>
          <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 3, fontFamily: 'var(--font-mono)', letterSpacing: '0.04em' }}>
            {event.id} · created by {event.createdBy || 'unknown'}
          </div>
        </div>
      </div>

      {/* Status strip */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '10px 12px',
        background: event.state === 'active' ? 'var(--warn-bg)' : 'transparent',
        border: event.state === 'active' ? '1px solid var(--warn)' : 'var(--border)',
        borderRadius: 4,
      }}>
        <EventChip event={event} />
        <span style={{ fontSize: 12, color: 'var(--ink-2)', marginLeft: 'auto', fontVariantNumeric: 'tabular-nums' }}>
          {event.state === 'active'
            ? `Running · ends ${fmtFull(event.endAt)}`
            : startsInLabel
              ? `Starts ${startsInLabel}`
              : r.text}
        </span>
      </div>

      {/* Timing */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <SectionLabel>Window {event.startAt && event.endAt ? `· ${Math.round((new Date(event.endAt) - new Date(event.startAt)) / 86400000)} days` : ''}</SectionLabel>
        <div style={{ fontSize: 12, color: 'var(--ink-2)', lineHeight: 1.55, fontVariantNumeric: 'tabular-nums' }}>
          <div><span style={{ color: 'var(--ink-4)' }}>Starts</span>&nbsp;&nbsp;{fmtFull(event.startAt)}</div>
          <div><span style={{ color: 'var(--ink-4)' }}>Ends</span>&nbsp;&nbsp;&nbsp;&nbsp;{fmtFull(event.endAt)}</div>
          <div style={{ color: 'var(--ink-5)', marginTop: 4, fontSize: 11 }}>
            {event.daypart
              ? `Daypart ${String(Math.floor(event.daypart.startMin/60)).padStart(2,'0')}:${String(event.daypart.startMin%60).padStart(2,'0')}–${String(Math.floor(event.daypart.endMin/60)).padStart(2,'0')}:${String(event.daypart.endMin%60).padStart(2,'0')}`
              : 'Daypart off · plays 24/7 within window'}
          </div>
        </div>
      </div>

      {/* Target */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <SectionLabel right={`${event.effectiveScreenCount || 0} screen${(event.effectiveScreenCount || 0) === 1 ? '' : 's'}`}>Target</SectionLabel>
        <div style={{ fontSize: 12, color: 'var(--ink-2)' }}>
          {event.targetMode === 'screens'
            ? <span>Specific screens — {(event.screens || []).length}</span>
            : <span>Stores · <b style={{ color: 'var(--ink-1)' }}>{(event.stores || []).join(', ') || 'none'}</b></span>}
        </div>
      </div>

      {/* Splashes */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <SectionLabel right={`${(sp.portrait ? 1 : 0) + (sp.landscape ? 1 : 0)} of 2`}>
          Splashes · required
        </SectionLabel>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <SplashSlot orientation="landscape" item={sp.landscape} />
          <SplashSlot orientation="portrait"  item={sp.portrait} />
        </div>
      </div>

      {/* Master toggle */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 12px', border: 'var(--border)', borderRadius: 4,
        background: 'var(--ink-10)',
      }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--ink-2)' }}>Splash plays alongside assigned content</div>
          <div style={{ fontSize: 11, color: 'var(--ink-5)', marginTop: 2 }}>Default for every targeted screen</div>
        </div>
        <span aria-hidden style={{
          width: 28, height: 16, borderRadius: 999,
          background: event.splashOn ? 'var(--accent)' : 'var(--ink-6)',
          position: 'relative', flexShrink: 0,
        }}>
          <span style={{
            position: 'absolute', top: 2,
            left: event.splashOn ? 14 : 2,
            width: 12, height: 12, borderRadius: 999,
            background: 'var(--ink-10)',
          }} />
        </span>
      </div>

      {/* Per-screen assignments summary */}
      {assignedIds.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <SectionLabel right={`${assignedIds.length} custom · ${Math.max(0, (event.effectiveScreenCount || 0) - assignedIds.length)} splash`}>
            Per-screen · what plays
          </SectionLabel>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {assignedIds.slice(0, 6).map((deviceId) => (
              <AssignRow key={deviceId} deviceId={deviceId} entry={assignments[deviceId]} />
            ))}
            {assignedIds.length > 6 && (
              <div style={{ padding: '6px 10px', fontSize: 11, color: 'var(--ink-5)' }}>
                +{assignedIds.length - 6} more
              </div>
            )}
          </div>
        </div>
      )}

      {/* Footer actions */}
      <div style={{
        marginTop: 'auto', paddingTop: 16,
        borderTop: 'var(--border)',
        display: 'flex', gap: 8, flexWrap: 'wrap',
      }}>
        {event.state === 'draft' && (
          <>
            <Button variant="secondary" size="sm" onClick={onEditTiming} style={{ flex: 1, justifyContent: 'center' }}>Edit timing</Button>
            <Button variant="primary" size="sm" disabled={r.tone === 'warn'} onClick={() => onAction('sign-off', event)} style={{ flex: 1, justifyContent: 'center' }}>Sign off →</Button>
          </>
        )}
        {event.state === 'signed-off' && (
          <>
            <Button variant="secondary" size="sm" onClick={() => onAction('un-sign-off', event)} style={{ flex: 1, justifyContent: 'center' }}>Un-sign-off</Button>
            <Button variant="primary" size="sm" onClick={() => onAction('start', event)} style={{ flex: 1, justifyContent: 'center' }}>Start now</Button>
          </>
        )}
        {event.state === 'active' && (
          <Button variant="danger" size="sm" onClick={() => onAction('stop', event)} style={{ flex: 1, justifyContent: 'center' }}>End event</Button>
        )}
        {event.state === 'ended' && (
          <Button variant="secondary" size="sm" style={{ flex: 1, justifyContent: 'center' }}>Duplicate</Button>
        )}
      </div>
    </aside>
  );
};

const SectionLabel = ({ children, right }) => (
  <div style={{
    fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.14em',
    color: 'var(--ink-4)', fontWeight: 500,
    display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
  }}>
    <span>{children}</span>
    {right && <span style={{ color: 'var(--ink-5)', fontFamily: 'var(--font-mono)', fontSize: 10, textTransform: 'none', letterSpacing: '0.02em' }}>{right}</span>}
  </div>
);

const SplashSlot = ({ orientation, item }) => {
  const isPortrait = orientation === 'portrait';
  return (
    <div style={{
      border: item ? 'var(--border)' : '1px dashed var(--border-strong)',
      background: item ? 'var(--ink-9)' : 'transparent',
      borderRadius: 4, padding: 10,
      display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{
        fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--ink-4)',
        letterSpacing: '0.14em', textTransform: 'uppercase',
      }}>
        {isPortrait ? 'Portrait' : 'Landscape'}
      </div>
      <div style={{
        position: 'relative', borderRadius: 2, overflow: 'hidden',
        background: item
          ? 'linear-gradient(135deg, rgba(78, 231, 180, 0.12), var(--ink-8) 80%)'
          : 'var(--ink-9)',
        aspectRatio: isPortrait ? '9 / 16' : '16 / 9',
        maxHeight: isPortrait ? 180 : undefined,
        margin: isPortrait ? '0 auto' : undefined,
        width: isPortrait ? 'auto' : '100%',
        display: 'grid', placeItems: 'center',
      }}>
        {!item && (
          <span style={{
            fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--warn)',
            letterSpacing: '0.12em', textTransform: 'uppercase',
          }}>Not uploaded</span>
        )}
        {item && item.type === 'video' && (
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--accent)', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
            {item.durationSec ? `${item.durationSec}s video` : 'video'}
          </span>
        )}
      </div>
      {item && (
        <div style={{
          fontSize: 11, color: 'var(--ink-3)', fontFamily: 'var(--font-mono)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          letterSpacing: '0.02em',
        }}>{item.title || item.id}</div>
      )}
      <button style={{
        border: '1px dashed var(--border-strong)', background: 'transparent',
        color: 'var(--ink-4)', padding: '7px 10px', borderRadius: 3,
        fontSize: 11, fontFamily: 'inherit', cursor: 'pointer', textAlign: 'center',
      }}>{item ? 'Replace' : '+ Upload or pick'}</button>
    </div>
  );
};

const AssignRow = ({ deviceId, entry }) => (
  <div style={{
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '9px 10px',
    background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 3,
  }}>
    <div style={{
      width: 32, height: 20, borderRadius: 2, flexShrink: 0,
      background: 'linear-gradient(135deg, var(--warn-bg), var(--ink-9) 80%)',
    }} />
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 12, color: 'var(--ink-1)', display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{deviceId}</span>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', fontFamily: 'var(--font-mono)', letterSpacing: '0.02em', marginTop: 1 }}>
        {(entry.items || []).length} clip{(entry.items || []).length === 1 ? '' : 's'} assigned
        {entry.splashOn === true  && ' · splash on'}
        {entry.splashOn === false && ' · splash off'}
      </div>
    </div>
    <span style={{
      fontFamily: 'var(--font-mono)', fontSize: 9, letterSpacing: '0.1em', textTransform: 'uppercase',
      padding: '2px 6px', borderRadius: 2,
      color: 'var(--warn)', background: 'var(--warn-bg)',
    }}>Custom</span>
  </div>
);

// ─────────────────────────────────────────────────────────────
// Create modal — minimum fields to spawn a draft; splashes +
// assignments edited in the detail rail after create.
// ─────────────────────────────────────────────────────────────
const CreateEventModal = ({ stores, onClose, onCreated }) => {
  const [name, setName] = useState('');
  const [startAt, setStartAt] = useState('');
  const [endAt, setEndAt] = useState('');
  const [daypartOn, setDaypartOn] = useState(false);
  const [dpStart, setDpStart] = useState(540);
  const [dpEnd, setDpEnd] = useState(1260);
  const [scope, setScope] = useState('stores');
  const [selectedStores, setSelectedStores] = useState([]);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  const toClock = (m) => `${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
  const fromClock = (s) => { const [h, m] = (s||'00:00').split(':').map(x => parseInt(x,10)||0); return h*60+m; };

  const submit = async () => {
    setError(null);
    if (!name.trim()) { setError('Name is required'); return; }
    const payload = {
      name: name.trim(),
      startAt: startAt ? new Date(startAt).toISOString() : null,
      endAt:   endAt   ? new Date(endAt).toISOString()   : null,
      daypart: daypartOn ? { startMin: dpStart, endMin: dpEnd } : null,
      targetMode: scope,
      stores: scope === 'stores' ? selectedStores : [],
      screens: [],
    };
    setSaving(true);
    try {
      const r = await fetch('/api/events', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) { setError(data.detail || data.error || `Save failed (${r.status})`); return; }
      onCreated(data.event);
    } catch (e) { setError(String(e)); }
    finally { setSaving(false); }
  };

  const inputStyle = {
    width: '100%', padding: '9px 11px', background: 'var(--ink-9)',
    color: 'var(--ink-1)', border: 'var(--border-strong)', borderRadius: 3,
    fontSize: 13, fontFamily: 'inherit',
  };
  const label = (t, r) => (
    <div style={{
      fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.14em',
      color: 'var(--ink-4)', fontWeight: 500,
      display: 'flex', justifyContent: 'space-between',
    }}>
      <span>{t}</span>
      {r && <span style={{ color: 'var(--ink-5)', textTransform: 'none', letterSpacing: 0 }}>{r}</span>}
    </div>
  );

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 100,
        background: 'rgba(7, 9, 11, 0.6)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
      }}>
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 'min(620px, 100%)', maxHeight: '90vh', overflow: 'auto',
          background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 8,
          boxShadow: '0 24px 64px -12px rgba(0,0,0,0.5)',
        }}>
        <div style={{
          padding: '16px 22px 14px', borderBottom: 'var(--border)',
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
        }}>
          <h2 style={{ margin: 0, fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>New event</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--ink-4)', cursor: 'pointer', fontSize: 18 }}>×</button>
        </div>
        <div style={{ padding: '20px 22px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div>
            {label('Name')}
            <input type="text" value={name} onChange={(e) => setName(e.target.value)}
                   placeholder="e.g. Playhouse Berlin pop-up — launch week"
                   style={{ ...inputStyle, marginTop: 7 }} />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div>
              {label('Starts')}
              <input type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} style={{ ...inputStyle, marginTop: 7 }} />
            </div>
            <div>
              {label('Ends')}
              <input type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} style={{ ...inputStyle, marginTop: 7 }} />
            </div>
          </div>
          <div>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ink-2)' }}>
              <input type="checkbox" checked={daypartOn} onChange={(e) => setDaypartOn(e.target.checked)} />
              Only run during a specific time of day
            </label>
            {daypartOn && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 8 }}>
                <input type="time" value={toClock(dpStart)} onChange={(e) => setDpStart(fromClock(e.target.value))} style={inputStyle} />
                <input type="time" value={toClock(dpEnd)}   onChange={(e) => setDpEnd(fromClock(e.target.value))}   style={inputStyle} />
              </div>
            )}
          </div>
          <div>
            {label('Target')}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 7 }}>
              <label style={{
                display: 'flex', alignItems: 'center', gap: 8,
                padding: '8px 11px', fontSize: 12, color: 'var(--ink-2)',
                border: scope === 'stores' ? '1px solid var(--accent)' : 'var(--border)',
                background: scope === 'stores' ? 'var(--warn-bg)' : 'transparent',
                borderRadius: 3, cursor: 'pointer',
              }}>
                <input type="radio" checked={scope === 'stores'} onChange={() => setScope('stores')} />
                Specific stores
              </label>
              <label style={{
                display: 'flex', alignItems: 'center', gap: 8,
                padding: '8px 11px', fontSize: 12, color: 'var(--ink-2)',
                border: scope === 'screens' ? '1px solid var(--accent)' : 'var(--border)',
                background: scope === 'screens' ? 'var(--warn-bg)' : 'transparent',
                borderRadius: 3, cursor: 'pointer',
              }}>
                <input type="radio" checked={scope === 'screens'} onChange={() => setScope('screens')} />
                Specific screens (pick in detail rail after create)
              </label>
            </div>
            {scope === 'stores' && (
              <div style={{
                marginTop: 8, maxHeight: 140, overflow: 'auto', padding: 6,
                border: 'var(--border)', borderRadius: 3,
                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 2,
              }}>
                {stores.length === 0
                  ? <div style={{ padding: 10, fontSize: 12, color: 'var(--ink-4)' }}>No stores available.</div>
                  : stores.map((st) => {
                      const picked = selectedStores.includes(st.id);
                      return (
                        <label key={st.id} style={{
                          display: 'flex', alignItems: 'center', gap: 7,
                          padding: '5px 8px', fontSize: 12, borderRadius: 2, cursor: 'pointer',
                          background: picked ? 'var(--ink-9)' : 'transparent',
                          color: picked ? 'var(--ink-1)' : 'var(--ink-2)',
                        }}>
                          <input type="checkbox" checked={picked}
                                 onChange={() => setSelectedStores(picked ? selectedStores.filter(x => x !== st.id) : [...selectedStores, st.id])} />
                          {st.name || st.id}
                        </label>
                      );
                    })}
              </div>
            )}
            <div style={{ fontSize: 11, color: 'var(--ink-5)', marginTop: 7 }}>
              Splashes + per-screen content set on the next step, after create.
            </div>
          </div>
          {error && (
            <div style={{ padding: '10px 12px', background: 'var(--err-bg)', color: 'var(--err)', borderRadius: 3, fontSize: 12 }}>{error}</div>
          )}
        </div>
        <div style={{
          padding: '12px 22px', borderTop: 'var(--border)',
          background: 'var(--ink-9)',
          display: 'flex', justifyContent: 'flex-end', gap: 8,
        }}>
          <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
          <Button variant="primary" size="sm" onClick={submit} disabled={saving}>
            {saving ? 'Saving…' : 'Create as draft →'}
          </Button>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Events page
// ─────────────────────────────────────────────────────────────
const Events = () => {
  const { items, loading, refresh } = useEvents();
  const vp = useViewport();
  const compact = vp.isCompact;
  const [selected, setSelected] = useState(null);  // event id
  const [creating, setCreating] = useState(false);
  const [stores, setStores] = useState(() => Array.isArray(window.MOCK_STORES) ? window.MOCK_STORES : []);
  useEffect(() => {
    let cancelled = false;
    fetch('/api/stores', { credentials: 'include' })
      .then((r) => r.ok ? r.json() : null)
      .then((data) => { if (data?.stores && !cancelled) setStores(data.stores); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const openEvent = (ev) => setSelected(ev.id);
  const closeDetail = () => setSelected(null);
  const activeEvent = useMemo(
    () => items.find((e) => e.id === selected) || null,
    [items, selected],
  );

  const act = async (action, ev) => {
    const confirms = {
      stop: `End "${ev.name}" now? Every targeted screen will be restored to its pre-event state.`,
      'un-sign-off': `Un-sign-off "${ev.name}"? Preload stops; screens stop downloading its content.`,
    };
    if (confirms[action] && !window.confirm(confirms[action])) return;
    try {
      const r = await fetch(`/api/events/${encodeURIComponent(ev.id)}/${action}`, {
        method: 'POST', credentials: 'include',
      });
      if (!r.ok) {
        const data = await r.json().catch(() => ({}));
        alert(data.detail || data.error || `Failed (${r.status})`);
        return;
      }
      refresh();
      if (typeof showToast === 'function') showToast(`${ev.name}: ${action}`);
    } catch (e) {
      alert(String(e));
    }
  };

  const grouped = useMemo(() => {
    const live = [], signedOff = [], draft = [], past = [];
    for (const e of items) {
      const s = eventStatusOf(e);
      if (s === 'live') live.push(e);
      else if (s === 'signed-off') signedOff.push(e);
      else if (s === 'past') past.push(e);
      else draft.push(e);
    }
    return { live, signedOff, draft, past };
  }, [items]);

  // Finding line: the single most pressing issue, if any.
  const finding = useMemo(() => {
    const atRisk = [...grouped.signedOff, ...grouped.draft]
      .map((e) => ({ e, r: readinessOf(e) }))
      .filter((x) => x.r.tone === 'warn' && x.e.startAt)
      .sort((a, b) => new Date(a.e.startAt) - new Date(b.e.startAt));
    if (atRisk.length === 0) {
      const liveN = grouped.live.length;
      if (liveN > 0) return `${liveN} event${liveN === 1 ? '' : 's'} broadcasting right now — nothing needs attention.`;
      if (grouped.signedOff.length > 0) return `${grouped.signedOff.length} event${grouped.signedOff.length === 1 ? '' : 's'} signed off — content preloading to targets.`;
      if (items.length === 0) return 'No events yet — create one to schedule a pop-up or activation.';
      return 'All drafts in good shape.';
    }
    const top = atRisk[0];
    const diff = new Date(top.e.startAt) - Date.now();
    const d = Math.round(diff / 86400000);
    const timing = d <= 0 ? 'now' : d === 1 ? 'in 1 day' : `in ${d} days`;
    return (
      <>
        <b>{top.e.name}</b> starts {timing} — <span style={{ color: 'var(--warn)' }}>{top.r.text.toLowerCase()}</span>
        {atRisk.length > 1 ? ` · +${atRisk.length - 1} more at risk` : ''}
      </>
    );
  }, [grouped, items]);

  const section = (label, rows, pulse) => rows.length === 0 ? null : (
    <div style={{ marginBottom: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, padding: '0 2px' }}>
        {pulse && (
          <span aria-hidden style={{
            width: 5, height: 5, borderRadius: '50%',
            background: 'var(--warn)',
            animation: 'sidebar-live-pulse 2s ease-in-out infinite',
          }} />
        )}
        <span style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.14em', color: 'var(--ink-4)', fontWeight: 500 }}>{label}</span>
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-5)' }}>{rows.length}</span>
      </div>
      <div style={{ border: 'var(--border)', borderRadius: 8, background: 'var(--ink-10)' }}>
        {rows.map((e) => (
          <EventRow key={e.id} event={e} selected={e.id === selected}
                    onOpen={openEvent} onAction={act} />
        ))}
      </div>
    </div>
  );

  const showRail = !!activeEvent && !compact;

  return (
    <AppShell current="events">
      <PageHeader
        title="Events"
        subtitle={<span style={{ fontSize: 13, color: 'var(--ink-3)' }}>{finding}</span>}
        actions={<Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New event</Button>}
      />
      <div style={{
        flex: 1, display: 'grid',
        gridTemplateColumns: showRail ? 'minmax(0, 1fr) 420px' : '1fr',
        minHeight: 0,
      }}>
        <div style={{ overflow: 'auto', padding: compact ? '16px 14px 32px' : '24px 28px 40px' }}>
          {loading && items.length === 0 ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>Loading…</div>
          ) : items.length === 0 ? (
            <div style={{
              padding: 40, textAlign: 'center',
              border: 'var(--border)', borderRadius: 8, background: 'var(--ink-10)',
              color: 'var(--ink-3)', fontSize: 13,
            }}>
              <div style={{ marginBottom: 6, fontWeight: 500, color: 'var(--ink-1)' }}>No events yet</div>
              <div style={{ color: 'var(--ink-4)', fontSize: 12 }}>
                Create an event to schedule a pop-up or activation. Upload two splashes (portrait + landscape), optionally pre-stage per-screen content, sign off, and screens auto-switch at the start time.
              </div>
              <div style={{ marginTop: 14 }}>
                <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New event</Button>
              </div>
            </div>
          ) : (
            <div>
              {section('Live now', grouped.live, true)}
              {section('Signed off · preloading', grouped.signedOff, false)}
              {section('Draft', grouped.draft, false)}
              {grouped.past.length > 0 && section('Past', grouped.past.slice(0, 5), false)}
              {grouped.past.length > 5 && (
                <div style={{ padding: '8px 2px', fontSize: 11, color: 'var(--ink-4)' }}>
                  +{grouped.past.length - 5} more past events
                </div>
              )}
            </div>
          )}
        </div>
        {showRail && (
          <DetailRail
            event={activeEvent}
            onClose={closeDetail}
            onAction={act}
            onEditTiming={() => { /* opens the create modal in edit mode in a follow-up */ }}
          />
        )}
      </div>
      {creating && (
        <CreateEventModal
          stores={stores}
          onClose={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false);
            refresh();
            setSelected(created.id);
          }}
        />
      )}
    </AppShell>
  );
};

Object.assign(window, { Events });
