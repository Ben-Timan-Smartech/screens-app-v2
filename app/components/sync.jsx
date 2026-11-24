/* eslint-disable */
// Sync groups: shared logic and UI, used by the Sync groups page and the
// screen page.
//
// A group is a name that several screens share (the server stores it as the
// screen's `syncGroup`). Screens in a group play one playlist on one clock.
// Since v0.2.25 each screen reports how its sync is going on every heartbeat
// (`syncStatus`, see _clean_sync_report in serve.py), so the CMS can say
// whether a group is actually in step rather than assuming it.

// A report older than this says nothing about now: the screen has stopped
// reporting, or is too busy to.
const SYNC_REPORT_STALE_SEC = 150;

// v0.2.25: a player that answers a change within a heartbeat, and the
// CMS wording for an older one.
const SYNC_REPORT_MIN_APP = '0.2.25';

const syncScreenName = (s) => {
  try { return liveScreenToRow(s).name; } catch (_) { return s.name || s.deviceId; }
};

const syncNaturalSort = (a, b) =>
  String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });

const sortSyncScreens = (list) =>
  (list || []).slice().sort((a, b) => syncNaturalSort(syncScreenName(a), syncScreenName(b)));

const syncStoreName = (storeId) => {
  if (!storeId) return '';
  const st = allStores().find((x) => x.id === storeId);
  return st ? st.name : storeId;
};

// Old groups were named "store:<id>" by the CMS; show the store's name.
const syncGroupLabel = (id) => {
  if (!id) return '';
  if (id.startsWith('store:')) return `${syncStoreName(id.slice(6))} screens`;
  return id;
};

const syncPlaylistKey = (items) => (items || []).map((v) => v && v.id).join('|');

// Parses "0.2.25" (and "web-1.0.0" to nothing) into [0,2,25].
const syncVersion = (v) => {
  const m = String(v || '').match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
};
const syncVersionAtLeast = (v, min) => {
  const a = syncVersion(v); const b = syncVersion(min);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) { if (a[i] !== b[i]) return a[i] > b[i]; }
  return true;
};

// The group's reference playlist: the member pushed to most recently. This is
// the same rule the server uses when a screen joins and takes the playlist.
const syncReference = (members) => (members || []).slice()
  .sort((a, b) => (b.pushedAt || 0) - (a.pushedAt || 0))[0] || null;

const syncDriftText = (ms) => {
  if (ms == null) return '';
  const abs = Math.abs(ms);
  const dir = ms < 0 ? 'behind' : 'ahead';
  if (abs < 1000) return `${abs} ms ${dir}`;
  return `${(abs / 1000).toFixed(1)} s ${dir}`;
};

/**
 * How one screen in a group is doing, from the data the server has.
 * Returns {tone: 'ok'|'warn'|'err'|'off'|'info', label, detail}.
 * `reference` is the group's reference member (see syncReference).
 */
const syncHealth = (s, { reference, groupAnchorMs } = {}) => {
  if (!s.online) return { tone: 'off', label: 'Offline', detail: 'Keeps its last playlist and rejoins when it reconnects.' };
  if (s.event) return { tone: 'info', label: 'In an event', detail: 'Plays the event until it ends, then rejoins.' };
  if (s.previewRemainingSec > 0) return { tone: 'info', label: 'Content test', detail: 'Plays the test until it ends, then rejoins.' };
  if (s.contentMode === 'web') return { tone: 'info', label: 'Showing a web page', detail: 'Rejoins when it goes back to videos.' };

  if (reference && reference.deviceId !== s.deviceId
      && syncPlaylistKey(s.currentItems) !== syncPlaylistKey(reference.currentItems)) {
    return { tone: 'err', label: 'Different playlist', detail: 'This screen has different videos from the rest of the group, so it can\'t be in step. Make it match, or remove it.' };
  }
  const st = s.syncStatus;
  const isWeb = s.appFlavor === 'web';
  if (!st) {
    if (!isWeb && s.appVersion && !syncVersionAtLeast(s.appVersion, SYNC_REPORT_MIN_APP)) {
      return { tone: 'info', silent: true, label: 'Not reporting', detail: `Needs app v${SYNC_REPORT_MIN_APP} to report its sync. Plays in the group as before.` };
    }
    return { tone: 'info', silent: true, label: 'No report yet', detail: 'The screen hasn\'t reported its sync yet.' };
  }
  if (s.syncStatusAgeSec != null && s.syncStatusAgeSec > SYNC_REPORT_STALE_SEC) {
    return { tone: 'warn', silent: true, label: 'No recent report', detail: `Last reported ${Math.round(s.syncStatusAgeSec / 60)} min ago.` };
  }
  if (st.missing > 0) {
    return { tone: 'err', label: `Can't play ${st.missing} of ${st.items}`, detail: 'This screen can\'t play some of the group\'s videos, so its slots are out of step with the others.' };
  }
  if (st.state === 'waiting') {
    return { tone: 'info', label: 'Waiting to start', detail: 'Ready for the group\'s next start.' };
  }
  if (s.deviceRevision != null && s.currentRevision != null && s.deviceRevision < s.currentRevision && !isWeb) {
    return { tone: 'warn', label: 'Not updated yet', detail: 'A newer playlist is on its way to this screen.' };
  }
  if (groupAnchorMs != null && st.anchorMs != null && Math.abs(st.anchorMs - groupAnchorMs) > 5) {
    return { tone: 'warn', label: 'Catching up', detail: 'Hasn\'t picked up the group\'s latest start yet.' };
  }
  if (st.state === 'loading') return { tone: 'warn', label: 'Loading', detail: 'Opening its video.' };
  if (st.driftMs == null) return { tone: 'ok', label: 'In step', detail: 'Playing with the group.' };
  const abs = Math.abs(st.driftMs);
  const text = syncDriftText(st.driftMs);
  if (abs <= 80) return { tone: 'ok', label: 'In step', detail: `Within ${abs} ms of the group clock.` };
  if (abs <= 300) return { tone: 'warn', label: 'Catching up', detail: `${text} the group clock, closing the gap.` };
  return { tone: 'err', label: 'Out of step', detail: `${text} the group clock.` };
};

const syncToneColor = { ok: 'var(--ok)', warn: 'var(--warn)', err: 'var(--err)', off: 'var(--ink-4)', info: 'var(--ink-3)' };
const syncToneDot = { ok: 'var(--ok-dot)', warn: 'var(--warn-dot)', err: 'var(--err-dot)', off: 'var(--err-dot)', info: 'var(--ink-5)' };

// Everything the UI needs about one group, from the live screen list.
const buildSyncGroup = (id, screens) => {
  const members = sortSyncScreens((screens || []).filter((s) => s.syncGroup === id));
  const reference = syncReference(members);
  const anchor = members.map((m) => m.syncLoopStartedAtMs).find((x) => x != null) || null;
  const health = members.map((m) => ({ screen: m, health: syncHealth(m, { reference, groupAnchorMs: anchor }) }));
  const counts = { ok: 0, warn: 0, err: 0, off: 0, info: 0, silent: 0 };
  health.forEach((h) => { counts[h.health.silent ? 'silent' : h.health.tone]++; });
  return {
    id, members, reference, anchor, health, counts,
    label: syncGroupLabel(id),
    onlineCount: members.filter((m) => m.online).length,
    itemCount: (reference ? reference.currentItems : []).length,
    mismatched: health.filter((h) => h.health.label === 'Different playlist').map((h) => h.screen),
  };
};

const syncGroupsFrom = (screens) => {
  const ids = Array.from(new Set((screens || []).map((s) => s.syncGroup).filter(Boolean)));
  return ids.map((id) => buildSyncGroup(id, screens))
    .sort((a, b) => syncNaturalSort(a.label, b.label));
};

// "5 of 6 in step · 1 offline"
const syncGroupSummary = (g) => {
  const n = g.members.length;
  const parts = [];
  if (n === 1) parts.push('One screen: add another to sync');
  else parts.push(`${g.counts.ok} of ${n} in step`);
  if (g.counts.err) parts.push(`${g.counts.err} out of step`);
  if (g.counts.warn) parts.push(`${g.counts.warn} catching up`);
  if (g.counts.off) parts.push(`${g.counts.off} offline`);
  if (g.counts.info) parts.push(`${g.counts.info} not playing the group`);
  if (g.counts.silent) parts.push(`${g.counts.silent} not reporting`);
  return parts.join(' · ');
};

const syncGroupTone = (g) => {
  if (g.counts.err) return 'err';
  if (g.members.length < 2 || g.counts.warn || g.counts.off || g.counts.info || g.counts.silent) return 'warn';
  return 'ok';
};

const SyncHealthPill = ({ health, title }) => (
  <span title={title || health.detail} style={{
    display: 'inline-flex', alignItems: 'center', gap: 6,
    fontSize: 11, fontWeight: 500, color: syncToneColor[health.tone], whiteSpace: 'nowrap',
  }}>
    <span style={{ width: 7, height: 7, borderRadius: '50%', background: syncToneDot[health.tone], flexShrink: 0 }} />
    {health.label}
  </span>
);

const SyncOverlay = ({ title, onClose, children, width = 560 }) => (
  <div onClick={onClose} style={{
    position: 'fixed', inset: 0, zIndex: 60,
    background: 'rgba(9,9,11,0.5)', backdropFilter: 'blur(2px)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
  }}>
    <div onClick={(e) => e.stopPropagation()} style={{
      width: `min(${width}px, 94%)`, maxHeight: '88%',
      background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 14,
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
      boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 16px', borderBottom: 'var(--border)' }}>
        <div style={{ flex: 1, minWidth: 0, fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</div>
        <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: 16 }}>{children}</div>
    </div>
  </div>
);

// Instant feedback while the 3 s screens poll catches up: the last group set
// here per screen, dropped once the live data agrees or 8 s pass.
const syncPending = {};
const syncEffectiveGroup = (s) => {
  const p = syncPending[s.deviceId];
  if (!p) return s.syncGroup || null;
  if ((s.syncGroup || null) === p.group || Date.now() - p.at > 8000) { delete syncPending[s.deviceId]; return s.syncGroup || null; }
  return p.group;
};

// Re-render anything showing a group the moment a screen is moved, rather
// than waiting for the next poll.
const useSyncTick = () => {
  const [, bump] = React.useState(0);
  React.useEffect(() => {
    const f = () => bump((x) => x + 1);
    window.addEventListener('sync-pending', f);
    return () => window.removeEventListener('sync-pending', f);
  }, []);
};

const syncSetGroup = async (deviceId, group) => {
  syncPending[deviceId] = { group: group || null, at: Date.now() };
  window.dispatchEvent(new Event('sync-pending'));
  try {
    return await setScreenSyncGroup(deviceId, group || null);
  } catch (e) {
    delete syncPending[deviceId];
    throw e;
  }
};

const setScreenSyncTrim = async (deviceId, trimMs) => {
  const res = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/sync-trim`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ trimMs }),
  });
  if (!res.ok) throw new Error(`adjustment failed (${res.status})`);
  return res.json();
};

/** Line one screen up with the others by eye, 10 ms at a time. */
const SyncTrim = ({ screen }) => {
  const saved = screen.syncTrimMs || 0;
  const [draft, setDraft] = React.useState(null);   // {ms, at} until the list catches up
  const [busy, setBusy] = React.useState(false);
  const ms = draft && Date.now() - draft.at < 30000 ? draft.ms : saved;
  const set = async (next) => {
    const v = Math.max(-500, Math.min(500, next));
    if (v === ms) return;
    setBusy(true);
    try { await setScreenSyncTrim(screen.deviceId, v); setDraft({ ms: v, at: Date.now() }); }
    catch (e) { showToast(e.message, 'err'); }
    finally { setBusy(false); }
  };
  const name = syncScreenName(screen);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }} title="Adjust this screen's timing if it looks early or late next to the others">
      <Button variant="ghost" size="sm" disabled={busy || ms <= -500} onClick={() => set(ms - 10)} title={`Make ${name} 10 ms later`}>Later</Button>
      <span className="tnum" style={{ fontSize: 11, color: ms ? 'var(--ink-1)' : 'var(--ink-4)', minWidth: 52, textAlign: 'center' }}>{ms > 0 ? `+${ms}` : ms} ms</span>
      <Button variant="ghost" size="sm" disabled={busy || ms >= 500} onClick={() => set(ms + 10)} title={`Make ${name} 10 ms earlier`}>Earlier</Button>
    </div>
  );
};

const restartSyncGroup = async (groupId) => {
  const res = await fetch(`/api/sync-groups/${encodeURIComponent(groupId)}/restart`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  });
  if (!res.ok) throw new Error(`restart failed (${res.status})`);
  return res.json();
};

const renameSyncGroup = async (groupId, name) => {
  const res = await fetch(`/api/sync-groups/${encodeURIComponent(groupId)}/rename`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }),
  });
  if (res.status === 409) throw new Error('Another group already has that name');
  if (!res.ok) throw new Error(`rename failed (${res.status})`);
  return res.json();
};

// "starting together in 13 s" from a push/restart response.
const syncStartText = (res) =>
  res && res.startsInSec != null ? `starting together in ${Math.max(1, Math.round(res.startsInSec))} s` : '';

// What to say after a playlist change: a change on one member of a group
// lands on every member, so say how many and when they start.
const syncPushToast = (res, base) => {
  const n = res && res.screensTargeted;
  if (res && res.syncGroup && n > 1) {
    const start = syncStartText(res);
    return `${base} on all ${n} screens in ${syncGroupLabel(res.syncGroup)}${start ? `, ${start}` : ''}`;
  }
  return base;
};

// Screens to choose from: the group's own store first, search, and a toggle
// for the rest of the fleet.
const SyncScreenChooser = ({ screens, excludeIds, storeId, picked, onToggle, disabled }) => {
  const [q, setQ] = React.useState('');
  const [all, setAll] = React.useState(!storeId);
  const exclude = new Set(excludeIds || []);
  const text = q.trim().toLowerCase();
  const rows = sortSyncScreens((screens || []).filter((s) => !exclude.has(s.deviceId)))
    .filter((s) => all || (s.location && s.location.storeId === storeId))
    .filter((s) => !text || [syncScreenName(s), syncStoreName(s.location && s.location.storeId), s.deviceId]
      .some((x) => String(x || '').toLowerCase().includes(text)));
  const others = (screens || []).filter((s) => !exclude.has(s.deviceId) && !(s.location && s.location.storeId === storeId)).length;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <div style={{ flex: 1, minWidth: 160 }}>
          <Input size="sm" placeholder="Search screens" value={q} onChange={(e) => setQ(e.target.value)}
            leadingIcon={<Icon.search size={12} />} />
        </div>
        {storeId && others > 0 && (
          <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 11.5, color: 'var(--ink-3)' }}>
            <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
            Other locations
          </label>
        )}
      </div>
      {rows.length === 0 && (
        <div style={{ padding: 14, fontSize: 12, color: 'var(--ink-4)', textAlign: 'center', border: 'var(--border)', borderRadius: 6 }}>
          {text ? 'No screens match.' : 'No other screens here.'}
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 280, overflow: 'auto' }}>
        {rows.map((s) => {
          const on = picked.has(s.deviceId);
          const cur = syncEffectiveGroup(s);
          return (
            <button key={s.deviceId} type="button" disabled={disabled} onClick={() => onToggle(s)}
              style={{
                display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '8px 10px',
                border: on ? '1px solid var(--ink-0)' : 'var(--border)', borderRadius: 6,
                background: on ? 'var(--ink-8)' : 'var(--ink-10)', textAlign: 'left',
                cursor: disabled ? 'default' : 'pointer',
              }}>
              <span style={{
                width: 16, height: 16, borderRadius: 3, flexShrink: 0,
                background: on ? 'var(--ink-0)' : 'var(--ink-10)', border: on ? 'none' : '1.5px solid var(--ink-6)',
                color: 'var(--on-accent)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              }}>{on && <Icon.check size={11} />}</span>
              <StatusDot status={s.online ? 'online' : 'offline'} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{syncScreenName(s)}</div>
                <div style={{ fontSize: 10.5, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {[syncStoreName(s.location && s.location.storeId), cur ? `in ${syncGroupLabel(cur)}` : null].filter(Boolean).join(' · ')}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

// Move screens into `groupId`, one at a time, with a confirm for anything
// that would lose content or leave another group.
const joinSyncGroup = async (groupId, toJoin, { groupScreens, confirmText }) => {
  const reference = syncReference((groupScreens || []).filter((s) => syncEffectiveGroup(s) === groupId));
  const moving = toJoin.filter((s) => syncEffectiveGroup(s) && syncEffectiveGroup(s) !== groupId);
  const replacing = reference ? toJoin.filter((s) => syncPlaylistKey(s.currentItems) !== syncPlaylistKey(reference.currentItems)) : [];
  const lines = [];
  if (moving.length) {
    lines.push(...moving.map((s) => `${syncScreenName(s)} leaves ${syncGroupLabel(syncEffectiveGroup(s))}.`));
  }
  if (replacing.length) {
    lines.push(`${replacing.length} ${replacing.length === 1 ? 'screen takes' : 'screens take'} the group's videos, replacing what ${replacing.length === 1 ? 'it has' : 'they have'} now.`);
  }
  if (lines.length && !confirm(`${confirmText || 'Add to the group?'}\n\n${lines.join('\n')}`)) return false;
  for (const s of toJoin) await syncSetGroup(s.deviceId, groupId);
  return true;
};

const SyncNameField = ({ value, onChange, autoFocus }) => (
  <div>
    <div style={{ fontSize: 11, color: 'var(--ink-4)', marginBottom: 4 }}>Group name</div>
    <Input size="sm" placeholder="e.g. Left column" value={value} onChange={(e) => onChange(e.target.value)} />
  </div>
);

const SyncNewGroupModal = ({ screens, presetIds = [], storeId, suggestedName, onClose, onDone }) => {
  const [name, setName] = React.useState(suggestedName || '');
  const [picked, setPicked] = React.useState(new Set(presetIds));
  const [busy, setBusy] = React.useState(false);
  const names = new Set((screens || []).map((s) => s.syncGroup).filter(Boolean));
  const clash = name.trim() && names.has(name.trim());
  const toggle = (s) => setPicked((p) => {
    const n = new Set(p);
    if (n.has(s.deviceId)) n.delete(s.deviceId); else n.add(s.deviceId);
    return n;
  });
  const chosen = (screens || []).filter((s) => picked.has(s.deviceId));
  const lead = sortSyncScreens(chosen)[0];
  const create = async () => {
    const id = name.trim();
    if (!id || clash || chosen.length < 2) return;
    setBusy(true);
    try {
      const rest = sortSyncScreens(chosen).filter((s) => s !== lead);
      const moving = chosen.filter((s) => syncEffectiveGroup(s) && syncEffectiveGroup(s) !== id);
      if (moving.length && !confirm(moving.map((s) => `${syncScreenName(s)} leaves ${syncGroupLabel(syncEffectiveGroup(s))}.`).join('\n') + '\n\nCreate the group?')) { setBusy(false); return; }
      // The first screen sets the playlist; the others take it.
      await syncSetGroup(lead.deviceId, id);
      for (const s of rest) await syncSetGroup(s.deviceId, id);
      showToast(`Group "${id}" created with ${chosen.length} screens`, 'ok');
      onDone && onDone(id);
      onClose();
    } catch (e) {
      showToast(`Couldn't create the group: ${e.message}`, 'err');
    } finally {
      setBusy(false);
    }
  };
  return (
    <SyncOverlay title="New sync group" onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <SyncNameField value={name} onChange={setName} />
        {clash && <div style={{ fontSize: 11.5, color: 'var(--warn)' }}>There's already a group called that.</div>}
        <div>
          <div style={{ fontSize: 11, color: 'var(--ink-4)', marginBottom: 6 }}>Screens</div>
          <SyncScreenChooser screens={screens} excludeIds={[]} storeId={storeId} picked={picked} onToggle={toggle} disabled={busy} />
        </div>
        {lead && chosen.length > 1 && (
          <div style={{ fontSize: 11.5, color: 'var(--ink-3)', lineHeight: 1.5 }}>
            All {chosen.length} screens take <strong style={{ color: 'var(--ink-1)' }}>{syncScreenName(lead)}</strong>'s videos, and play them in step.
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
          <Button variant="primary" size="sm" disabled={busy || !name.trim() || clash || chosen.length < 2} onClick={create}>
            {chosen.length < 2 ? 'Choose 2 or more screens' : 'Create group'}
          </Button>
        </div>
      </div>
    </SyncOverlay>
  );
};

// One group's controls and members. Used on the Sync groups page and inside
// the screen page's modal.
const SyncGroupPanel = ({ group, screens, canPush, canCommand, focusDeviceId, onNavigate, onLeave }) => {
  const [busy, setBusy] = React.useState(null);
  const [adding, setAdding] = React.useState(false);
  const [picked, setPicked] = React.useState(new Set());
  const [renaming, setRenaming] = React.useState(false);
  const [name, setName] = React.useState(group.id);
  const focus = group.members.find((m) => m.deviceId === focusDeviceId);
  const storeId = (focus || group.members[0] || {}).location?.storeId || null;
  const run = async (key, fn, okText) => {
    setBusy(key);
    try { const r = await fn(); if (okText) showToast(typeof okText === 'function' ? okText(r) : okText, 'ok'); return r; }
    catch (e) { showToast(e.message, 'err'); return null; }
    finally { setBusy(null); }
  };
  const add = async () => {
    const list = (screens || []).filter((s) => picked.has(s.deviceId));
    if (!list.length) return;
    await run('add', async () => {
      const ok = await joinSyncGroup(group.id, list, { groupScreens: screens, confirmText: `Add ${list.length} screen${list.length === 1 ? '' : 's'} to ${group.label}?` });
      if (!ok) return null;
      setPicked(new Set()); setAdding(false);
      showToast(`Added ${list.length} screen${list.length === 1 ? '' : 's'} to ${group.label}`, 'ok');
      return true;
    });
  };
  const remove = async (s) => {
    if (!confirm(`Take ${syncScreenName(s)} out of ${group.label}? It keeps playing its videos, on its own clock.`)) return;
    await run(`rm-${s.deviceId}`, () => syncSetGroup(s.deviceId, null), `${syncScreenName(s)} is on its own clock again`);
  };
  const match = async () => {
    const ref = group.reference;
    if (!ref || !group.mismatched.length) return;
    if (!confirm(`Give every screen in ${group.label} ${syncScreenName(ref)}'s ${(ref.currentItems || []).length} videos? The loop starts again together.`)) return;
    await run('match', () => setScreenPlaylist(ref.deviceId, ref.currentItems || [], 'replace'),
      (r) => r && syncPushToast(r, 'Matched'));
  };
  const restart = async () => {
    await run('restart', () => restartSyncGroup(group.id),
      (r) => r && `Starting the group again together in about ${Math.max(1, Math.round(r.startsInSec || 0))} s`);
  };
  const rename = async () => {
    const next = name.trim();
    if (!next || next === group.id) { setRenaming(false); return; }
    const r = await run('rename', () => renameSyncGroup(group.id, next), `Renamed to ${next}`);
    if (r) setRenaming(false);
  };
  const calibrate = async () => {
    if (!confirm(`Show a clock test on all ${group.members.length} screens for 60 seconds? Customers will see it.`)) return;
    await run('cal', () => calibrateSyncGroup(group.id, 60), (r) => r && `Clock test on ${r.screensTargeted} screens for 60 s: the numbers should match`);
  };
  const doing = !!busy;
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 180 }}>
          {renaming ? (
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <div style={{ flex: 1 }}><Input size="sm" value={name} onChange={(e) => setName(e.target.value)} /></div>
              <Button variant="primary" size="sm" disabled={doing || !name.trim()} onClick={rename}>Save</Button>
              <Button variant="ghost" size="sm" onClick={() => { setRenaming(false); setName(group.id); }}>Cancel</Button>
            </div>
          ) : (
            <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
              <div style={{ fontSize: 15, fontWeight: 500, color: 'var(--ink-0)', overflowWrap: 'anywhere' }}>{group.label}</div>
              {canPush && <button type="button" onClick={() => { setName(group.id); setRenaming(true); }} style={{ border: 'none', background: 'none', padding: 0, cursor: 'pointer', fontSize: 11.5, color: 'var(--ink-4)' }}>Rename</button>}
            </div>
          )}
          <div style={{ fontSize: 12, marginTop: 4, color: syncToneColor[syncGroupTone(group)] }}>{syncGroupSummary(group)}</div>
          <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>{group.itemCount} video{group.itemCount === 1 ? '' : 's'} · {group.members.length} screen{group.members.length === 1 ? '' : 's'}</div>
        </div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {group.mismatched.length > 0 && (
            <Button variant="primary" size="sm" disabled={!canPush || doing} onClick={match}>Match playlists</Button>
          )}
          <Button variant="secondary" size="sm" disabled={!canPush || doing || group.members.length < 2} onClick={restart}
            title="Start the loop from the first video on every screen at the same moment">Restart together</Button>
          <Button variant="ghost" size="sm" disabled={!canCommand || doing || group.members.length < 2} onClick={calibrate}
            title="Show a test clock on every screen so you can check they match">Clock test</Button>
          {onLeave && <Button variant="ghost" size="sm" disabled={!canPush || doing} onClick={onLeave}>Leave group</Button>}
        </div>
      </div>

      <div style={{ marginTop: 12, borderTop: 'var(--border-faint)', paddingTop: 8, display: 'flex', flexDirection: 'column', gap: 2 }}>
        {group.health.map(({ screen: m, health }) => (
          <div key={m.deviceId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 8px', borderRadius: 6, flexWrap: 'wrap' }}>
            <div style={{ flex: 1, minWidth: 140 }}>
              <button type="button" onClick={() => onNavigate && onNavigate(m)}
                style={{ border: 'none', background: 'none', padding: 0, cursor: onNavigate ? 'pointer' : 'default', fontSize: 13, color: 'var(--ink-1)', fontWeight: 500, textAlign: 'left', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {syncScreenName(m)}
              </button>
              <div style={{ fontSize: 10.5, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {health.detail}
              </div>
            </div>
            {canPush && m.appFlavor === 'web' && <SyncTrim screen={m} />}
            <SyncHealthPill health={health} />
            {canPush && (
              <Button variant="ghost" size="sm" disabled={doing} icon={<Icon.close size={12} />} onClick={() => remove(m)} title={`Take ${syncScreenName(m)} out of the group`} />
            )}
          </div>
        ))}
      </div>

      {adding ? (
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <SyncScreenChooser screens={screens} excludeIds={group.members.map((m) => m.deviceId)} storeId={storeId}
            picked={picked} onToggle={(s) => setPicked((p) => { const n = new Set(p); n.has(s.deviceId) ? n.delete(s.deviceId) : n.add(s.deviceId); return n; })} disabled={doing} />
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <Button variant="ghost" size="sm" onClick={() => { setAdding(false); setPicked(new Set()); }}>Cancel</Button>
            <Button variant="primary" size="sm" disabled={doing || picked.size === 0} onClick={add}>Add {picked.size || ''} screen{picked.size === 1 ? '' : 's'}</Button>
          </div>
        </div>
      ) : (
        canPush && <div style={{ marginTop: 8 }}><Button variant="ghost" size="sm" icon={<Icon.plus size={12} />} onClick={() => setAdding(true)}>Add screens</Button></div>
      )}
    </div>
  );
};

// The screen page's "Sync group" modal.
const SyncGroupModal2 = ({ screen, screens, canPush, canCommand, onClose }) => {
  const [creating, setCreating] = React.useState(false);
  const [joinPick, setJoinPick] = React.useState(false);
  useSyncTick();
  const groupId = syncEffectiveGroup(screen);
  const group = groupId ? buildSyncGroup(groupId, (screens || []).map((s) => ({ ...s, syncGroup: syncEffectiveGroup(s) }))) : null;
  const existing = syncGroupsFrom(screens).filter((g) => g.id !== groupId);
  const sameStore = existing.filter((g) => g.members.some((m) => m.location?.storeId === screen.location?.storeId));
  const joinable = sameStore.length ? sameStore : existing;
  const leave = async () => {
    if (!confirm(`Take ${syncScreenName(screen)} out of ${group.label}?`)) return;
    try { await syncSetGroup(screen.deviceId, null); showToast('On its own clock again', 'ok'); }
    catch (e) { showToast(e.message, 'err'); }
  };
  const join = async (g) => {
    try {
      const ok = await joinSyncGroup(g.id, [screen], { groupScreens: screens, confirmText: `Join ${g.label}?` });
      if (ok) { showToast(`Joined ${g.label}`, 'ok'); setJoinPick(false); }
    } catch (e) { showToast(e.message, 'err'); }
  };
  return (
    <SyncOverlay title={`Sync · ${syncScreenName(screen)}`} onClose={onClose}>
      {creating && (
        <SyncNewGroupModal screens={screens} presetIds={[screen.deviceId]} storeId={screen.location?.storeId}
          suggestedName={syncStoreName(screen.location?.storeId) ? `${syncStoreName(screen.location?.storeId)} group` : ''}
          onClose={() => setCreating(false)} onDone={() => setCreating(false)} />
      )}
      {group ? (
        <SyncGroupPanel group={group} screens={screens} canPush={canPush} canCommand={canCommand}
          focusDeviceId={screen.deviceId} onLeave={leave} />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ fontSize: 12.5, color: 'var(--ink-3)', lineHeight: 1.55 }}>
            This screen plays on its own. Put it in a sync group to play the same videos in step with other screens, for example one column of a wall.
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <Button variant="primary" size="sm" disabled={!canPush} icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New group</Button>
            {joinable.length > 0 && <Button variant="secondary" size="sm" disabled={!canPush} onClick={() => setJoinPick((x) => !x)}>Join a group</Button>}
            <Button variant="ghost" size="sm" disabled={!canCommand || !screen.online} onClick={async () => {
              try { await calibrateSyncGroup(screen.deviceId, 60); showToast('Clock test on this screen for 60 s', 'ok'); }
              catch (e) { showToast(e.message, 'err'); }
            }}>Clock test</Button>
          </div>
          {joinPick && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {joinable.map((g) => (
                <button key={g.id} type="button" onClick={() => join(g)} style={{ border: 'var(--border)', borderRadius: 6, padding: '9px 12px', background: 'var(--ink-10)', textAlign: 'left', cursor: 'pointer' }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{g.label}</div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>{syncGroupSummary(g)}</div>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </SyncOverlay>
  );
};

// The summary card on the screen page.
const SyncGroupCard = ({ screen, screens, canPush, canCommand }) => {
  const [open, setOpen] = React.useState(false);
  useSyncTick();
  const groupId = syncEffectiveGroup(screen);
  const eff = (screens || []).map((s) => ({ ...s, syncGroup: syncEffectiveGroup(s) }));
  const group = groupId ? buildSyncGroup(groupId, eff) : null;
  const mine = group && group.health.find((h) => h.screen.deviceId === screen.deviceId);
  return (
    <Card padding={14}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 2 }}>Sync group</div>
          {group ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <div style={{ fontSize: 12, color: 'var(--ink-2)', overflowWrap: 'anywhere' }}>{group.label}</div>
              {mine && <SyncHealthPill health={mine.health} />}
              <div style={{ fontSize: 11, color: syncToneColor[syncGroupTone(group)] }}>{syncGroupSummary(group)}</div>
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>Plays on its own</div>
          )}
        </div>
        <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>{group ? 'Manage' : 'Set up'}</Button>
      </div>
      {open && <SyncGroupModal2 screen={screen} screens={screens} canPush={canPush} canCommand={canCommand} onClose={() => setOpen(false)} />}
    </Card>
  );
};

// Shown above the screen's playlist when it's in a group with others.
const SyncPlaylistBanner = ({ screen, screens }) => {
  const groupId = syncEffectiveGroup(screen);
  if (!groupId) return null;
  const n = (screens || []).filter((s) => syncEffectiveGroup(s) === groupId).length;
  if (n < 2) return null;
  return (
    <div style={{ padding: '9px 12px', borderRadius: 6, background: 'var(--info-bg, var(--ink-8))', fontSize: 11.5, color: 'var(--ink-2)', lineHeight: 1.5, marginBottom: 10 }}>
      These videos play on all <strong>{n} screens</strong> in <strong>{syncGroupLabel(groupId)}</strong>. Changes here apply to all of them, and they start again together.
    </div>
  );
};

Object.assign(window, {
  syncGroupsFrom, buildSyncGroup, syncGroupSummary, syncGroupTone, syncGroupLabel, syncEffectiveGroup,
  syncHealth, SyncHealthPill, SyncGroupPanel, SyncGroupCard, SyncNewGroupModal, SyncPlaylistBanner,
  SyncScreenChooser, syncPushToast, useSyncTick, syncScreenName, sortSyncScreens, SyncOverlay, syncToneColor,
});
