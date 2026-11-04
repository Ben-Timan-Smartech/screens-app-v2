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
// PATCH /api/events/<id>. Throws with the server's plain-words reason.
const eventsPatch = async (id, patch) => {
  const r = await fetch(`/api/events/${encodeURIComponent(id)}`, {
    method: 'PATCH', credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.detail || data.error || `Save failed (${r.status})`);
  return data.event;
};

// Library video -> event item (the same shape a playlist item has).
const eventsItemFromVideo = (v) => ({
  id: v.id,
  title: v.title,
  brand: v.brand,
  product: v.product,
  durationSec: typeof v.durationSec === 'number' ? Math.round(v.durationSec) : 15,
  url: v.mediaUrl || v.url,
  sizeMb: v.sizeMb,
  width: v.width,
  height: v.height,
  type: v.type || 'video',
});

const DetailRail = ({ event, onClose, onAction, onEdit, onChanged }) => {
  const r = readinessOf(event);
  const editable = event.state !== 'ended';
  const targetEditable = event.state === 'draft' || event.state === 'signed-off';
  // { landscape: 0..1 } while an upload is in flight; 1 = server compressing.
  const [busy, setBusy] = useState({});
  const [picking, setPicking] = useState(null);   // orientation | null
  const [savingToggle, setSavingToggle] = useState(false);
  const fleet = useFleet();

  const setSplash = async (orientation, video) => {
    await eventsPatch(event.id, { splashes: { [orientation]: eventsItemFromVideo(video) } });
    onChanged();
  };
  const pickSplash = async (orientation, video) => {
    setPicking(null);
    try {
      await setSplash(orientation, video);
      showToast(`${orientation === 'portrait' ? 'Portrait' : 'Landscape'} splash set`, 'ok');
    } catch (e) { showToast(e.message, 'err'); }
  };
  const uploadSplash = async (orientation, file) => {
    setBusy((b) => ({ ...b, [orientation]: 0 }));
    try {
      const res = await uploadVideo({
        file, brand: 'events', compress: true,
        title: `${event.name} · ${orientation} splash`,
        onProgress: (f) => setBusy((b) => ({ ...b, [orientation]: f })),
      });
      window.dispatchEvent(new CustomEvent('library-refresh', { detail: { video: res.video } }));
      await setSplash(orientation, res.video);
      const c = res.compression || {};
      showToast(c.compressed
        ? `${orientation === 'portrait' ? 'Portrait' : 'Landscape'} splash compressed ${c.originalMb} → ${c.finalMb} MB`
        : `${orientation === 'portrait' ? 'Portrait' : 'Landscape'} splash uploaded${c.compressNote ? ` (${c.compressNote})` : ''}`, 'ok');
    } catch (e) {
      showToast(e.message || 'Upload failed', 'err');
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[orientation]; return n; });
    }
  };
  const toggleSplashOn = async () => {
    if (!editable || savingToggle) return;
    setSavingToggle(true);
    try { await eventsPatch(event.id, { splashOn: !event.splashOn }); onChanged(); }
    catch (e) { showToast(e.message, 'err'); }
    finally { setSavingToggle(false); }
  };
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
  return (
    <aside style={{
      borderLeft: 'var(--border)', background: 'var(--ink-10)',
      padding: '24px 24px 32px', overflowY: 'auto',
      display: 'flex', flexDirection: 'column', gap: 20,
      flex: 1, minHeight: 0,
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
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
          <div style={{ fontSize: 12, color: 'var(--ink-2)', flex: 1, minWidth: 0, lineHeight: 1.5 }}>
            {event.targetMode === 'screens'
              ? (event.screens || []).length === 0
                ? <span style={{ color: 'var(--warn)' }}>No screens picked yet</span>
                : <span>Specific screens · <b style={{ color: 'var(--ink-1)' }}>{eventsScreenLabels(event.screens, fleet)}</b></span>
              : <span>Stores · <b style={{ color: 'var(--ink-1)' }}>{eventsStoreLabels(event.stores) || 'none'}</b></span>}
          </div>
          {targetEditable && (
            <Button variant="secondary" size="sm" onClick={onEdit}>Change</Button>
          )}
        </div>
        {event.state === 'active' && (
          <div style={{ fontSize: 11, color: 'var(--ink-5)' }}>End the event to change which screens it covers.</div>
        )}
      </div>

      {/* Splashes */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <SectionLabel right={`${(sp.portrait ? 1 : 0) + (sp.landscape ? 1 : 0)} of 2`}>
          Splashes · required
        </SectionLabel>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          {['landscape', 'portrait'].map((o) => (
            <SplashSlot key={o} orientation={o} item={sp[o]} editable={editable}
                        progress={busy[o]}
                        onUpload={(f) => uploadSplash(o, f)}
                        onPick={() => setPicking(o)} />
          ))}
        </div>
        {editable && (
          <div style={{ fontSize: 11, color: 'var(--ink-5)', lineHeight: 1.45 }}>
            Uploads are compressed to 1080p so older boxes can play them.
          </div>
        )}
      </div>
      {picking && (
        <EventsSplashPicker orientation={picking}
                            onPick={(v) => pickSplash(picking, v)}
                            onClose={() => setPicking(null)} />
      )}

      {/* Master toggle */}
      <button type="button" role="switch" aria-checked={!!event.splashOn}
        disabled={!editable || savingToggle} onClick={toggleSplashOn}
        style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 12px', border: 'var(--border)', borderRadius: 4,
        background: 'var(--ink-10)', textAlign: 'left', width: '100%',
        cursor: editable ? 'pointer' : 'default', opacity: savingToggle ? 0.6 : 1,
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
      </button>

      {/* Per-screen content */}
      <EventsScreenContent event={event} fleet={fleet} editable={editable} onChanged={onChanged} />

      {/* Footer actions */}
      <div style={{
        marginTop: 'auto', paddingTop: 16,
        borderTop: 'var(--border)',
        display: 'flex', gap: 8, flexWrap: 'wrap',
      }}>
        {event.state === 'draft' && (
          <>
            <Button variant="secondary" size="sm" onClick={onEdit} style={{ flex: 1, justifyContent: 'center' }}>Edit details</Button>
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
          <Button variant="secondary" size="sm" onClick={() => onAction('duplicate', event)} style={{ flex: 1, justifyContent: 'center' }}>Duplicate</Button>
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

// Readable target summaries: screens show their codes (PH.B2) rather than
// device UUIDs, stores show their names.
const eventsScreenLabels = (ids, fleet) => {
  const codes = (ids || []).map((id) => {
    const s = (fleet || []).find((x) => x.deviceId === id);
    return s ? s.screenCode : id.slice(0, 6);
  });
  return codes.length > 6 ? `${codes.slice(0, 6).join(', ')} +${codes.length - 6}` : codes.join(', ');
};
const eventsStoreLabels = (ids) => {
  const list = (typeof allStores === 'function') ? allStores() : (window.MOCK_STORES || []);
  return (ids || []).map((id) => (list.find((s) => s.id === id) || {}).name || id).join(', ');
};

const SplashSlot = ({ orientation, item, editable, progress, onUpload, onPick }) => {
  const isPortrait = orientation === 'portrait';
  const busy = progress != null;
  const clipPortrait = item && item.width && item.height ? item.height > item.width : null;
  const mismatch = clipPortrait != null && clipPortrait !== isPortrait;
  const slotBtn = {
    flex: 1, border: 'var(--border-strong)', background: 'var(--ink-10)',
    color: 'var(--ink-2)', padding: '7px 6px', borderRadius: 3,
    fontSize: 11, fontFamily: 'inherit', textAlign: 'center',
    cursor: busy ? 'default' : 'pointer', opacity: busy ? 0.55 : 1,
    whiteSpace: 'nowrap',
  };
  return (
    <div style={{
      border: item ? 'var(--border)' : '1px dashed var(--border-strong)',
      background: item ? 'var(--ink-9)' : 'transparent',
      borderRadius: 4, padding: 10,
      display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0,
    }}>
      <div style={{
        fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--ink-4)',
        letterSpacing: '0.14em', textTransform: 'uppercase',
      }}>
        {isPortrait ? 'Portrait' : 'Landscape'}
      </div>
      <div style={{
        position: 'relative', borderRadius: 2, overflow: 'hidden',
        background: 'var(--ink-9)',
        aspectRatio: isPortrait ? '9 / 16' : '16 / 9',
        height: isPortrait ? 180 : undefined,
        margin: isPortrait ? '0 auto' : undefined,
        width: isPortrait ? 101 : '100%',
        flexShrink: 0,
        display: 'grid', placeItems: 'center',
      }}>
        {item && item.url && !busy && (
          item.type === 'image'
            ? <img src={item.url} alt="" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
            : <video src={`${item.url}#t=0.5`} muted playsInline preload="metadata"
                     style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', background: '#000' }} />
        )}
        {!item && !busy && (
          <span style={{
            fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--warn)',
            letterSpacing: '0.12em', textTransform: 'uppercase',
          }}>Not set</span>
        )}
        {busy && (
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--accent)', letterSpacing: '0.06em', textAlign: 'center', padding: 6 }}>
            {progress < 1 ? `Uploading ${Math.round(progress * 100)}%` : 'Compressing…'}
          </span>
        )}
      </div>
      {item && (
        <div style={{
          fontSize: 11, color: 'var(--ink-3)', fontFamily: 'var(--font-mono)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          letterSpacing: '0.02em',
        }} title={item.title || item.id}>{item.title || item.id}</div>
      )}
      {item && (item.width || item.sizeMb) && (
        <div style={{ fontSize: 10, color: 'var(--ink-5)', fontFamily: 'var(--font-mono)' }}>
          {[item.width && item.height ? `${item.width}×${item.height}` : null,
            item.sizeMb ? `${item.sizeMb} MB` : null].filter(Boolean).join(' · ')}
        </div>
      )}
      {mismatch && (
        <div style={{ fontSize: 11, color: 'var(--warn)', lineHeight: 1.4 }}>
          This clip is {clipPortrait ? 'portrait' : 'landscape'}, so it will play letterboxed.
        </div>
      )}
      {editable && (
        <div style={{ display: 'flex', gap: 6 }}>
          <label style={slotBtn}>
            {item ? 'Replace' : 'Upload'}
            <input type="file" accept="video/mp4,video/quicktime,.mp4,.mov,.m4v"
                   disabled={busy} style={{ display: 'none' }}
                   onChange={(e) => {
                     const f = e.target.files && e.target.files[0];
                     e.target.value = '';
                     if (f) onUpload(f);
                   }} />
          </label>
          <button type="button" disabled={busy} onClick={onPick} style={slotBtn}>Library</button>
        </div>
      )}
    </div>
  );
};

// Pick an existing library clip as a splash. Clips matching the slot's
// orientation sort first, and heavy ones say so: a library pick isn't
// re-compressed the way an upload is.
const EventsSplashPicker = ({ orientation, onPick, onClose }) => {
  const [q, setQ] = useState('');
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onClose]);
  const wantPortrait = orientation === 'portrait';
  const results = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const fit = (v) => (v.width && v.height ? ((v.height > v.width) === wantPortrait ? 0 : 2) : 1);
    return (window.MOCK_VIDEOS || [])
      .filter((v) => (v.mediaUrl || v.url) && !v.pendingSync)
      .filter((v) => !needle || [v.title, v.brand, v.product].some((x) => String(x || '').toLowerCase().includes(needle)))
      .sort((a, b) => fit(a) - fit(b) || (b.uploadedAt || 0) - (a.uploadedAt || 0))
      .slice(0, 40);
  }, [q, wantPortrait]);
  return (
    <div onClick={(e) => { e.stopPropagation(); onClose(); }} style={{
      position: 'fixed', inset: 0, zIndex: 110, background: 'rgba(7, 9, 11, 0.6)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
    }}>
      <div role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()} style={{
        width: 'min(560px, 100%)', maxHeight: '85vh', display: 'flex', flexDirection: 'column',
        background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 8,
        boxShadow: '0 24px 64px -12px rgba(0,0,0,0.5)', overflow: 'hidden',
      }}>
        <div style={{ padding: '14px 18px', borderBottom: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontWeight: 500, color: 'var(--ink-1)', flex: 1 }}>
            Pick a {wantPortrait ? 'portrait' : 'landscape'} splash
          </h2>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} title="Close" />
        </div>
        <div style={{ padding: '12px 18px 6px' }}>
          <Input placeholder="Search the library by title or brand" value={q}
                 onChange={(e) => setQ(e.target.value)} leadingIcon={<Icon.search size={13} />} />
        </div>
        <div style={{ overflow: 'auto', padding: '4px 10px 12px' }}>
          {results.length === 0 ? (
            <div style={{ padding: 14, fontSize: 12, color: 'var(--ink-4)' }}>
              {q ? `Nothing in the library matches "${q}".` : 'The library is empty.'}
            </div>
          ) : results.map((v) => {
            const portrait = v.width && v.height ? v.height > v.width : null;
            const heavy = (typeof heavyReasons === 'function') ? heavyReasons(v) : [];
            return (
              <button key={v.id} type="button" onClick={() => onPick(v)} style={{
                display: 'flex', alignItems: 'center', gap: 10, width: '100%', minHeight: 44,
                padding: '6px 8px', borderRadius: 3, textAlign: 'left', background: 'transparent', cursor: 'pointer',
              }}>
                <span style={{ color: 'var(--ink-4)', display: 'flex' }}><Icon.video size={14} /></span>
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ display: 'block', fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                  <span className="tnum" style={{ display: 'block', fontSize: 11, color: 'var(--ink-4)' }}>
                    {[v.brand, v.width && v.height ? `${v.width}×${v.height}` : null, v.sizeMb ? `${v.sizeMb} MB` : null].filter(Boolean).join(' · ')}
                  </span>
                </span>
                {heavy.length > 0 && (
                  <span style={{ fontSize: 10, color: 'var(--warn)', flexShrink: 0 }}>Heavy for older boxes</span>
                )}
                {portrait != null && (
                  <span style={{ fontSize: 11, color: portrait === wantPortrait ? 'var(--ink-3)' : 'var(--warn)', flexShrink: 0 }}>
                    {portrait ? 'Portrait' : 'Landscape'}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};

// Pick individual screens for a targetMode='screens' event. Grouped by
// store; search matches code, name or store.
const EventsScreenPicker = ({ selected, onChange }) => {
  const fleet = useFleet();
  const [q, setQ] = useState('');
  const storeName = (id) => {
    const list = (typeof allStores === 'function') ? allStores() : (window.MOCK_STORES || []);
    return (list.find((s) => s.id === id) || {}).name || (id === 'unassigned' ? 'No store' : id);
  };
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return [...fleet]
      .filter((s) => !needle || [s.screenCode, s.name, storeName(s.storeId)].some((x) => String(x || '').toLowerCase().includes(needle)))
      .sort((a, b) => storeName(a.storeId).localeCompare(storeName(b.storeId)) || String(a.screenCode).localeCompare(String(b.screenCode)));
  }, [fleet, q]);
  const toggle = (id) => onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  const shownIds = rows.map((s) => s.deviceId);
  const allShown = shownIds.length > 0 && shownIds.every((id) => selected.includes(id));
  let lastStore = null;
  return (
    <div style={{ marginTop: 8, border: 'var(--border)', borderRadius: 3 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: 6, borderBottom: 'var(--border)' }}>
        <div style={{ flex: 1 }}>
          <Input placeholder="Search screens or stores" value={q}
                 onChange={(e) => setQ(e.target.value)} leadingIcon={<Icon.search size={13} />} />
        </div>
        <Button variant="ghost" size="sm" disabled={shownIds.length === 0}
                onClick={() => onChange(allShown
                  ? selected.filter((id) => !shownIds.includes(id))
                  : [...new Set([...selected, ...shownIds])])}>
          {allShown ? 'Clear shown' : `Select ${q ? 'shown' : 'all'}`}
        </Button>
      </div>
      <div style={{ maxHeight: 220, overflow: 'auto', padding: 4 }}>
        {rows.length === 0 ? (
          <div style={{ padding: 10, fontSize: 12, color: 'var(--ink-4)' }}>
            {fleet.length === 0 ? 'Loading screens…' : `No screens match "${q}".`}
          </div>
        ) : rows.map((s) => {
          const picked = selected.includes(s.deviceId);
          const header = s.storeId !== lastStore ? storeName(s.storeId) : null;
          lastStore = s.storeId;
          return (
            <React.Fragment key={s.deviceId}>
              {header && (
                <div style={{ padding: '8px 8px 3px', fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.12em', color: 'var(--ink-5)' }}>{header}</div>
              )}
              <label style={{
                display: 'flex', alignItems: 'center', gap: 8, minHeight: 32,
                padding: '4px 8px', fontSize: 12, borderRadius: 2, cursor: 'pointer',
                background: picked ? 'var(--ink-9)' : 'transparent',
                color: picked ? 'var(--ink-1)' : 'var(--ink-2)',
              }}>
                <input type="checkbox" checked={picked} onChange={() => toggle(s.deviceId)} />
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, width: 64, flexShrink: 0 }}>{s.screenCode}</span>
                <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {s.name !== s.screenCode ? s.name : ''}
                </span>
                <span style={{ fontSize: 11, color: 'var(--ink-4)', flexShrink: 0 }}>
                  {s.orient === 'portrait' ? 'Portrait' : 'Landscape'}{s.status === 'online' ? '' : ' · offline'}
                </span>
              </label>
            </React.Fragment>
          );
        })}
      </div>
      <div style={{ padding: '6px 10px', borderTop: 'var(--border)', fontSize: 11, color: 'var(--ink-4)' }}>
        {selected.length} screen{selected.length === 1 ? '' : 's'} picked
      </div>
    </div>
  );
};

// What each targeted screen plays during the event. A screen with no content
// of its own loops the splash; give it clips and they play instead (plus the
// splash when the toggle above is on). This works while the event is live:
// the server pushes the change to the screen straight away, and the screen's
// own playlist still comes back when the event ends, because the restore uses
// the snapshot taken at start, not whatever the event played.
const EventsScreenContent = ({ event, fleet, editable, onChanged }) => {
  const [selected, setSelected] = useState([]);
  const [picking, setPicking] = useState(null);   // deviceIds being edited | null
  const [saving, setSaving] = useState(false);
  useEffect(() => { setSelected([]); setPicking(null); }, [event.id]);
  const assignments = event.screenAssignments || {};
  const byId = useMemo(() => Object.fromEntries(fleet.map((s) => [s.deviceId, s])), [fleet]);
  const rows = [...(event.targetScreenIds || Object.keys(assignments))].sort((a, b) =>
    String(byId[a]?.screenCode || a).localeCompare(String(byId[b]?.screenCode || b)));
  const live = event.state === 'active';
  const itemsOf = (id) => assignments[id]?.items || [];
  const custom = rows.filter((id) => itemsOf(id).length > 0).length;
  const codeOf = (id) => byId[id]?.screenCode || id.slice(0, 8);

  const save = async (deviceIds, items) => {
    const next = { ...assignments };
    deviceIds.forEach((id) => {
      if (items.length) next[id] = { items, splashOn: next[id]?.splashOn ?? null };
      else delete next[id];
    });
    setSaving(true);
    try {
      await eventsPatch(event.id, { screenAssignments: next });
      setPicking(null);
      setSelected([]);
      onChanged();
      const who = deviceIds.length === 1 ? codeOf(deviceIds[0]) : `${deviceIds.length} screens`;
      showToast(items.length
        ? `${who} ${live ? 'now playing' : 'will play'} ${items.length} clip${items.length === 1 ? '' : 's'}`
          + (live ? `. ${deviceIds.length === 1 ? 'Its own playlist comes' : 'Their own playlists come'} back when the event ends` : '')
        : `${who} back to the splash`, 'ok');
    } catch (e) { showToast(e.message, 'err'); }
    finally { setSaving(false); }
  };

  // Prefill the picker with what's there when every screen being edited
  // already plays the same clips.
  const initialFor = (ids) => {
    const first = itemsOf(ids[0]);
    const key = (list) => list.map((i) => i.id).join('|');
    return ids.every((id) => key(itemsOf(id)) === key(first)) ? first : [];
  };
  const orientFor = (ids) => {
    const set = new Set(ids.map((id) => byId[id]?.orient === 'portrait' ? 'portrait' : 'landscape'));
    return set.size === 1 ? [...set][0] : null;
  };
  const toggle = (id) => setSelected((sel) => sel.includes(id) ? sel.filter((x) => x !== id) : [...sel, id]);
  const allPicked = rows.length > 0 && rows.every((id) => selected.includes(id));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <SectionLabel right={rows.length ? `${custom} custom · ${rows.length - custom} splash only` : null}>
        What each screen plays
      </SectionLabel>
      <div style={{ fontSize: 11, color: 'var(--ink-5)', lineHeight: 1.45 }}>
        {live
          ? 'Changes reach the screen straight away. Each screen goes back to its own playlist when the event ends.'
          : 'Screens without their own content loop the splash. You can change this while the event is running too.'}
      </div>
      {rows.length === 0 ? (
        <div style={{ fontSize: 12, color: 'var(--ink-4)' }}>No screens targeted yet.</div>
      ) : (
        <div style={{ border: 'var(--border)', borderRadius: 3 }}>
          {editable && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderBottom: 'var(--border)', minHeight: 40 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: 'var(--ink-4)', cursor: 'pointer', flex: 1 }}>
                <input type="checkbox" checked={allPicked}
                       onChange={() => setSelected(allPicked ? [] : rows)} />
                {selected.length ? `${selected.length} selected` : 'Select screens'}
              </label>
              {selected.length > 0 && (
                <Button variant="primary" size="sm" disabled={saving}
                        onClick={() => setPicking(selected)}>
                  Choose content
                </Button>
              )}
            </div>
          )}
          <div style={{ maxHeight: 320, overflow: 'auto' }}>
            {rows.map((id) => {
              const s = byId[id] || {};
              const items = itemsOf(id);
              const sel = selected.includes(id);
              const splashOn = assignments[id]?.splashOn ?? event.splashOn;
              return (
                <div key={id} style={{
                  display: 'flex', alignItems: 'center', gap: 8, padding: '7px 10px', minHeight: 44,
                  borderBottom: 'var(--border)', background: sel ? 'var(--ink-9)' : 'transparent',
                }}>
                  {editable && <input type="checkbox" checked={sel} onChange={() => toggle(id)} aria-label={`Select ${codeOf(id)}`} />}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, fontSize: 12, color: 'var(--ink-1)' }}>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{codeOf(id)}</span>
                      <span style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {[s.name && s.name !== s.screenCode ? s.name : null, s.orient === 'portrait' ? 'Portrait' : 'Landscape', s.status && s.status !== 'online' ? 'offline' : null].filter(Boolean).join(' · ')}
                      </span>
                    </div>
                    <div style={{ fontSize: 11, color: items.length ? 'var(--ink-2)' : 'var(--ink-5)', marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {items.length
                        ? `${items.map((i) => i.title).join(', ')}${splashOn ? ' + splash' : ''}`
                        : 'Splash only'}
                    </div>
                  </div>
                  {editable && (
                    <Button variant="ghost" size="sm" disabled={saving} onClick={() => setPicking([id])}>
                      {items.length ? 'Edit' : 'Add content'}
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
      {picking && (
        <EventsContentPicker
          title={picking.length === 1 ? `Content for ${codeOf(picking[0])}` : `Content for ${picking.length} screens`}
          orientation={orientFor(picking)}
          initial={initialFor(picking)}
          saving={saving}
          onSave={(items) => save(picking, items)}
          onClose={() => setPicking(null)} />
      )}
    </div>
  );
};

// Pick an ordered set of library clips for one or more screens. Clips play in
// the order they're picked. Saving with none sends the screen back to the splash.
const EventsContentPicker = ({ title, orientation, initial, saving, onSave, onClose }) => {
  const [q, setQ] = useState('');
  const [picked, setPicked] = useState(initial || []);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onClose]);
  const results = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const fit = (v) => (!orientation || !(v.width && v.height) ? 1 : ((v.height > v.width) === (orientation === 'portrait') ? 0 : 2));
    return (window.MOCK_VIDEOS || [])
      .filter((v) => (v.mediaUrl || v.url) && !v.pendingSync)
      .filter((v) => !needle || [v.title, v.brand, v.product].some((x) => String(x || '').toLowerCase().includes(needle)))
      .sort((a, b) => fit(a) - fit(b) || (b.uploadedAt || 0) - (a.uploadedAt || 0))
      .slice(0, 60);
  }, [q, orientation]);
  const pickedIds = picked.map((i) => i.id);
  const toggle = (v) => setPicked((list) => list.some((i) => i.id === v.id)
    ? list.filter((i) => i.id !== v.id)
    : [...list, eventsItemFromVideo(v)]);
  const totalSec = picked.reduce((n, i) => n + (Number(i.durationSec) || 0), 0);
  return (
    <div onClick={(e) => { e.stopPropagation(); onClose(); }} style={{
      position: 'fixed', inset: 0, zIndex: 110, background: 'rgba(7, 9, 11, 0.6)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
    }}>
      <div role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()} style={{
        width: 'min(600px, 100%)', maxHeight: '88vh', display: 'flex', flexDirection: 'column',
        background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 8,
        boxShadow: '0 24px 64px -12px rgba(0,0,0,0.5)', overflow: 'hidden',
      }}>
        <div style={{ padding: '14px 18px', borderBottom: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontWeight: 500, color: 'var(--ink-1)', flex: 1 }}>{title}</h2>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} title="Close" />
        </div>
        {picked.length > 0 && (
          <div style={{ padding: '10px 18px', borderBottom: 'var(--border)', display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 170, overflow: 'auto' }}>
            <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.12em', color: 'var(--ink-5)' }}>Plays in this order</div>
            {picked.map((i, n) => (
              <div key={i.id} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--ink-1)', minHeight: 28 }}>
                <span className="tnum" style={{ width: 18, color: 'var(--ink-4)', fontSize: 11 }}>{n + 1}</span>
                <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{i.title}</span>
                <Button variant="ghost" size="sm" icon={<Icon.close size={12} />} title="Remove"
                        onClick={() => setPicked((list) => list.filter((x) => x.id !== i.id))} />
              </div>
            ))}
          </div>
        )}
        <div style={{ padding: '12px 18px 6px' }}>
          <Input placeholder="Search the library by title or brand" value={q}
                 onChange={(e) => setQ(e.target.value)} leadingIcon={<Icon.search size={13} />} />
        </div>
        <div style={{ overflow: 'auto', padding: '4px 10px 12px', flex: 1, minHeight: 120 }}>
          {results.length === 0 ? (
            <div style={{ padding: 14, fontSize: 12, color: 'var(--ink-4)' }}>
              {q ? `Nothing in the library matches "${q}".` : 'The library is empty.'}
            </div>
          ) : results.map((v) => {
            const on = pickedIds.includes(v.id);
            const portrait = v.width && v.height ? v.height > v.width : null;
            const heavy = (typeof heavyReasons === 'function') ? heavyReasons(v) : [];
            return (
              <label key={v.id} style={{
                display: 'flex', alignItems: 'center', gap: 10, width: '100%', minHeight: 44,
                padding: '6px 8px', borderRadius: 3, cursor: 'pointer',
                background: on ? 'var(--ink-9)' : 'transparent',
              }}>
                <input type="checkbox" checked={on} onChange={() => toggle(v)} />
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ display: 'block', fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                  <span className="tnum" style={{ display: 'block', fontSize: 11, color: 'var(--ink-4)' }}>
                    {[v.brand, v.duration, v.sizeMb ? `${v.sizeMb} MB` : null].filter(Boolean).join(' · ')}
                  </span>
                </span>
                {heavy.length > 0 && (
                  <span style={{ fontSize: 10, color: 'var(--warn)', flexShrink: 0 }}>Heavy for older boxes</span>
                )}
                {portrait != null && (
                  <span style={{ fontSize: 11, color: !orientation || portrait === (orientation === 'portrait') ? 'var(--ink-3)' : 'var(--warn)', flexShrink: 0 }}>
                    {portrait ? 'Portrait' : 'Landscape'}
                  </span>
                )}
              </label>
            );
          })}
        </div>
        <div style={{ padding: '12px 18px', borderTop: 'var(--border)', display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span className="tnum" style={{ fontSize: 12, color: 'var(--ink-4)', flex: 1 }}>
            {picked.length
              ? `${picked.length} clip${picked.length === 1 ? '' : 's'} · ${Math.floor(totalSec / 60)}:${String(totalSec % 60).padStart(2, '0')}`
              : 'No clips: the screen loops the splash'}
          </span>
          <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
          <Button variant="primary" size="sm" disabled={saving} onClick={() => onSave(picked)}>
            {saving ? 'Saving…' : picked.length ? 'Save' : 'Use splash only'}
          </Button>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Event form — creates a draft, or edits an existing event's name, window
// and target. Splashes + per-screen content are set in the detail rail.
// While an event is live its target is fixed (the server refuses the
// change, since it would orphan the restore snapshots), so it's read-only.
const eventsLocalInput = (iso) => {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const EventFormModal = ({ stores, event, onClose, onSaved }) => {
  const editing = !!event;
  const targetLocked = editing && event.state === 'active';
  const [name, setName] = useState(event?.name || '');
  const [startAt, setStartAt] = useState(eventsLocalInput(event?.startAt));
  const [endAt, setEndAt] = useState(eventsLocalInput(event?.endAt));
  const [scope, setScope] = useState(event?.targetMode || 'stores');
  const [selectedStores, setSelectedStores] = useState(event?.stores || []);
  const [selectedScreens, setSelectedScreens] = useState(event?.screens || []);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    setError(null);
    if (!name.trim()) { setError('Name is required'); return; }
    const payload = {
      name: name.trim(),
      startAt: startAt ? new Date(startAt).toISOString() : null,
      endAt:   endAt   ? new Date(endAt).toISOString()   : null,
    };
    if (!targetLocked) {
      payload.targetMode = scope;
      payload.stores = scope === 'stores' ? selectedStores : [];
      payload.screens = scope === 'screens' ? selectedScreens : [];
    }
    setSaving(true);
    try {
      const r = await fetch(editing ? `/api/events/${encodeURIComponent(event.id)}` : '/api/events', {
        method: editing ? 'PATCH' : 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) { setError(data.detail || data.error || `Save failed (${r.status})`); return; }
      onSaved(data.event);
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
  const scopeOption = (value, text) => (
    <label style={{
      display: 'flex', alignItems: 'center', gap: 8,
      padding: '8px 11px', fontSize: 12, color: 'var(--ink-2)',
      border: scope === value ? '1px solid var(--accent)' : 'var(--border)',
      background: scope === value ? 'var(--warn-bg)' : 'transparent',
      borderRadius: 3, cursor: 'pointer',
    }}>
      <input type="radio" checked={scope === value} onChange={() => setScope(value)} />
      {text}
    </label>
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
          <h2 style={{ margin: 0, fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>{editing ? 'Edit event' : 'New event'}</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--ink-4)', cursor: 'pointer', fontSize: 18 }}>×</button>
        </div>
        <div style={{ padding: '20px 22px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div>
            {label('Name')}
            <input type="text" value={name} onChange={(e) => setName(e.target.value)}
                   placeholder="e.g. Playhouse Berlin pop-up — launch week"
                   style={{ ...inputStyle, marginTop: 7 }} />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 10 }}>
            <div>
              {label('Starts', 'your local time')}
              <input type="datetime-local" value={startAt} onChange={(e) => setStartAt(e.target.value)} style={{ ...inputStyle, marginTop: 7 }} />
            </div>
            <div>
              {label('Ends')}
              <input type="datetime-local" value={endAt} onChange={(e) => setEndAt(e.target.value)} style={{ ...inputStyle, marginTop: 7 }} />
            </div>
          </div>
          <div>
            {label('Target')}
            {targetLocked ? (
              <div style={{ marginTop: 7, fontSize: 12, color: 'var(--ink-4)' }}>
                This event is live, so its screens are fixed. End it to change them.
              </div>
            ) : (
              <>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 7 }}>
                  {scopeOption('stores', 'Whole stores')}
                  {scopeOption('screens', 'Specific screens')}
                </div>
                {scope === 'stores' && (
                  <div style={{
                    marginTop: 8, maxHeight: 180, overflow: 'auto', padding: 6,
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
                {scope === 'screens' && (
                  <EventsScreenPicker selected={selectedScreens} onChange={setSelectedScreens} />
                )}
              </>
            )}
            {!editing && (
              <div style={{ fontSize: 11, color: 'var(--ink-5)', marginTop: 7 }}>
                Splashes + per-screen content set on the next step, after create.
              </div>
            )}
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
            {saving ? 'Saving…' : editing ? 'Save changes' : 'Create as draft →'}
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
  const [editing, setEditing] = useState(null);    // event being edited
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
    if (action === 'duplicate') {
      // New draft with the same target + content; the window is left blank
      // so a copy never auto-starts on a date that's already passed.
      try {
        const r = await fetch('/api/events', {
          method: 'POST', credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name: `${ev.name} (copy)`.slice(0, 120),
            targetMode: ev.targetMode, stores: ev.stores || [], screens: ev.screens || [],
            splashes: ev.splashes || {}, splashOn: ev.splashOn !== false,
            screenAssignments: ev.screenAssignments || {},
          }),
        });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) { alert(data.detail || data.error || `Failed (${r.status})`); return; }
        refresh();
        setSelected(data.event.id);
        showToast(`Duplicated as "${data.event.name}"`, 'ok');
      } catch (e) { alert(String(e)); }
      return;
    }
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
            onEdit={() => setEditing(activeEvent)}
            onChanged={refresh}
          />
        )}
      </div>
      {/* On a phone the rail opens full-screen instead of beside the list. */}
      {activeEvent && compact && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 90, background: 'var(--ink-10)', display: 'flex', flexDirection: 'column' }}>
          <DetailRail
            event={activeEvent}
            onClose={closeDetail}
            onAction={act}
            onEdit={() => setEditing(activeEvent)}
            onChanged={refresh}
          />
        </div>
      )}
      {creating && (
        <EventFormModal
          stores={stores}
          onClose={() => setCreating(false)}
          onSaved={(created) => {
            setCreating(false);
            refresh();
            setSelected(created.id);
          }}
        />
      )}
      {editing && (
        <EventFormModal
          stores={stores}
          event={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            refresh();
            showToast('Event updated', 'ok');
          }}
        />
      )}
    </AppShell>
  );
};

Object.assign(window, { Events });
