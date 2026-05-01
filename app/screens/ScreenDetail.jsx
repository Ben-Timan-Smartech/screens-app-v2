/* eslint-disable */
// Screen detail — single screen view. Live status when the demo slot is
// connected; falls back to mock placeholders for offline slots.

// PlaylistItem — single row in the screen-detail playlist.
//
//   • Clicking the thumbnail or title opens the preview modal.
//   • Right-edge ⋯ opens a small popover with Remove (destructive).
//   • The whole row is an HTML5 drag source — drag onto another row to
//     reorder. Only the grip handle has the grab cursor; the row itself
//     stays normal pointer so clicks still feel right.
//
// `onRemove` / `onReorder` are nulled out when the screen is offline so the
// affordances visibly disable rather than failing silently.
const PlaylistItem = ({
  v, i, isCurrent, onRemove, onPreview,
  draggable, isDragging, isDropTargetAbove, onDragStart, onDragOver, onDragLeave, onDrop, onDragEnd,
}) => {
  const [menuOpen, setMenuOpen] = React.useState(false);
  return (
    <div
      draggable={draggable}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
      onDragEnd={onDragEnd}
      style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '8px 10px 8px 4px',
        border: 'var(--border)', borderRadius: 8,
        background: isCurrent ? 'var(--ink-8)' : 'var(--ink-10)',
        position: 'relative',
        opacity: isDragging ? 0.35 : 1,
        // Drop indicator above the row.
        boxShadow: isDropTargetAbove ? 'inset 0 2px 0 0 var(--ink-1)' : 'none',
        transition: 'opacity .12s, box-shadow .08s',
      }}>
      <span style={{ color: 'var(--ink-5)', display: 'flex', padding: '4px 2px', cursor: draggable ? 'grab' : 'default' }}>
        <Icon.grip size={14} />
      </span>
      <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)', width: 18, textAlign: 'right' }}>{i + 1}</span>
      <div
        onClick={(e) => { e.stopPropagation(); onPreview && onPreview(); }}
        style={{ width: 56, height: 32, borderRadius: 4, overflow: 'hidden', flexShrink: 0, cursor: onPreview ? 'pointer' : 'default' }}>
        <Thumbnail title={v.title} brand={v.brand} size="sm" aspect="16/9" />
      </div>
      <div
        onClick={(e) => { e.stopPropagation(); onPreview && onPreview(); }}
        style={{ flex: 1, minWidth: 0, cursor: onPreview ? 'pointer' : 'default' }}>
        <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', display: 'flex', gap: 6 }}>
          {v.brand && <span>{v.brand}</span>}
          {v.brand && <span>·</span>}
          <span className="tnum">{v.durationSec ? `${v.durationSec}s` : (v.duration || '—')}</span>
          {isCurrent && <><span>·</span><span style={{ color: 'var(--ok)' }}>Now playing</span></>}
        </div>
      </div>
      <Button variant="ghost" size="sm" icon={<Icon.more size={14} />} onClick={() => setMenuOpen((m) => !m)} />
      {menuOpen && (
        <>
          <div onClick={() => setMenuOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 10 }} />
          <div style={{
            position: 'absolute', right: 8, top: '100%', marginTop: 4, zIndex: 11,
            background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 8,
            boxShadow: '0 12px 32px -8px rgba(20,20,20,0.18)',
            minWidth: 180, padding: 4,
          }}>
            <button
              onClick={() => { setMenuOpen(false); onRemove && onRemove(); }}
              disabled={!onRemove}
              style={{
                width: '100%', textAlign: 'left',
                padding: '8px 12px', borderRadius: 6,
                fontSize: 12, color: onRemove ? 'var(--err)' : 'var(--ink-4)',
                cursor: onRemove ? 'pointer' : 'not-allowed',
                background: 'transparent',
              }}>
              {onRemove ? 'Remove from playlist' : 'Remove (offline)'}
            </button>
          </div>
        </>
      )}
    </div>
  );
};

const StatusLine = ({ label, value, mono, tone }) => (
  <div style={{ display: 'flex', alignItems: 'center', padding: '8px 0', borderBottom: 'var(--border-faint)', gap: 8 }}>
    <span style={{ fontSize: 12, color: 'var(--ink-4)', flex: 1 }}>{label}</span>
    <span className={mono ? 'tnum' : ''} style={{ fontSize: 12, fontWeight: 500, color: tone === 'ok' ? 'var(--ok)' : tone === 'err' ? 'var(--err)' : 'var(--ink-1)', textAlign: 'right' }}>{value}</span>
  </div>
);

// Toggle row — used for "Mix splash into playlist".
const ToggleRow = ({ label, sub, value, onChange, disabled }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
    <div style={{ flex: 1 }}>
      <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>{sub}</div>}
    </div>
    <button
      onClick={() => !disabled && onChange(!value)}
      disabled={disabled}
      style={{
        width: 32, height: 18, borderRadius: 999,
        background: value ? 'var(--ink-1)' : 'var(--ink-7)',
        position: 'relative', flexShrink: 0,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1,
        transition: 'background .12s',
      }}>
      <span style={{
        position: 'absolute', top: 2, left: value ? 16 : 2,
        width: 14, height: 14, borderRadius: '50%',
        background: 'var(--ink-10)',
        transition: 'left .12s',
      }} />
    </button>
  </div>
);

// Format helpers shared across the panel.
const formatBytes = (n) => {
  if (n == null) return '—';
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`;
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(0)} MB`;
  if (n >= 1024)      return `${(n / 1024).toFixed(0)} KB`;
  return `${n} B`;
};
const formatSecondsAgo = (s) => {
  if (s == null) return 'never';
  if (s < 5)   return 'now';
  if (s < 60)  return `${Math.round(s)}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return `${Math.round(s / 3600)} hr ago`;
};

const ScreenDetail = ({ onOpenSync, storeId, screenId }) => {
  const live = useLiveScreens();
  // The screenId in the URL is the tablet's deviceId. Look it up directly.
  const lastKnown = (live.screens || []).find((s) => s.deviceId === screenId) || null;
  const liveScreen = lastKnown?.online ? lastKnown : null;
  const isLive = !!liveScreen;
  const hasHistory = !!lastKnown;

  const store = MOCK_STORES.find(s => s.id === storeId) || MOCK_STORES[0];
  // Build a screen-row from the live record, or stub a placeholder when the
  // tablet has been removed from the registry entirely (URL still old).
  const screen = lastKnown
    ? liveScreenToRow(lastKnown)
    : { id: screenId, name: 'Screen not found', orient: 'landscape' };

  // The server persists `currentItems` per deviceId regardless of whether the
  // tablet is currently online — that's what the tablet picks up next poll.
  // Use it for offline screens too so the CMS shows the queued playlist.
  const playlist = hasHistory ? (lastKnown.currentItems || []) : [];
  // "Now playing" only makes sense when the tablet is actually online.
  const current = isLive ? playlist[0] : null;
  const totalDurationSec = playlist.reduce((a, v) => a + (v.durationSec || 15), 0);

  // Status panel values. We use lastKnown (registry record, online or not) so
  // the device info stays visible after a disconnect — only Health and Last
  // seen change. Pre-registration slots fall through to the "—" placeholders.
  const statusValues = hasHistory ? {
    health:        isLive
      ? { label: 'Online', tone: 'ok' }
      : { label: 'Offline', tone: 'err' },
    lastSeen:      formatSecondsAgo(lastKnown.secondsSinceHeartbeat),
    device:        lastKnown.deviceModel || '—',
    tier:          lastKnown.tier || (lastKnown.ramMb ? `${lastKnown.ramMb >= 3000 ? '1080p' : '720p'} (${(lastKnown.ramMb / 1024).toFixed(1)}GB RAM)` : '—'),
    orientation:   lastKnown.orientation || '—',
    storage:       lastKnown.cacheBytes != null
      ? `${formatBytes(lastKnown.cacheBytes)} cached`
      : '—',
    free:          lastKnown.freeStorageBytes != null ? formatBytes(lastKnown.freeStorageBytes) : '—',
    appVersion:    lastKnown.appVersion ? `v${lastKnown.appVersion}` : '—',
    revision:      `rev ${lastKnown.currentRevision || 0}`,
  } : {
    // Truly never-registered slot — keep the original "pending" copy.
    health: { label: 'Offline', tone: 'err' },
    lastSeen: 'never',
    device: 'Pending registration',
    tier: '—', orientation: screen.orient || '—',
    storage: '—', free: '—', appVersion: '—', revision: '—',
  };

  // Any tablet that has ever registered has a deviceId on the server, even
  // if it's currently offline. The server's command queue + per-screen
  // playlist state are both keyed on deviceId, so we can dispatch
  // edits/commands and the tablet picks them up next time it polls.
  const targetDeviceId = lastKnown?.deviceId || null;
  const canEdit = !!targetDeviceId;

  const [busy, setBusy] = React.useState(null);
  const handleCommand = async (command, label) => {
    if (!targetDeviceId) {
      showToast('No registered tablet for this slot yet', 'err');
      return;
    }
    const note = isLive
      ? `${label} the live tablet?`
      : `${label}? Screen is offline — command will run when it reconnects.`;
    if (!confirm(note)) return;
    setBusy(command);
    try {
      await sendScreenCommand(targetDeviceId, command);
      showToast(
        isLive
          ? `${label} queued — tablet picks it up within 3 s`
          : `${label} queued for next reconnect`,
        'ok',
      );
    } catch (e) {
      showToast(`Command failed: ${e.message}`, 'err');
    } finally {
      setBusy(null);
    }
  };

  const handleMixSplashToggle = async (next) => {
    if (!targetDeviceId) return;
    try {
      await setMixSplash(targetDeviceId, next);
      showToast(
        isLive
          ? (next ? 'Splash mixed into playlist' : 'Splash removed from playlist')
          : (next ? 'Splash will mix on next reconnect' : 'Splash will be removed on next reconnect'),
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  const handleClearAll = async () => {
    if (!targetDeviceId) return;
    const len = playlist.length;
    if (len === 0) return;
    const note = isLive
      ? `Remove all ${len} video${len === 1 ? '' : 's'} from ${screen.name}? The screen will fall back to the splash loop.`
      : `Clear queued playlist for ${screen.name}? Screen is offline — change runs when it reconnects.`;
    if (!confirm(note)) return;
    try {
      await setScreenPlaylist(targetDeviceId, [], 'replace');
      showToast(
        isLive ? `Cleared ${screen.name}` : `Cleared ${screen.name} — applies on reconnect`,
        'ok',
      );
    } catch (e) {
      showToast(`Clear failed: ${e.message}`, 'err');
    }
  };

  const [syncOpen, setSyncOpen] = React.useState(false);
  const [addOpen, setAddOpen] = React.useState(false);
  const [previewVideo, setPreviewVideo] = React.useState(null);
  // Drag-to-reorder state. dragIndex = the row being dragged; overIndex =
  // the row currently being hovered (drop indicator renders above it).
  const [dragIndex, setDragIndex] = React.useState(null);
  const [overIndex, setOverIndex] = React.useState(null);
  const handleOpenSync = onOpenSync || (() => setSyncOpen(true));

  const handleReorder = async (from, to) => {
    if (from === to || from == null || to == null || !canEdit) return;
    const next = [...playlist];
    const [moved] = next.splice(from, 1);
    // When dragging downward and dropping onto a row, the row's "above"
    // marker points at index `to` in the original list; after removing the
    // dragged row, the destination index shifts left by 1 if from < to.
    const insertAt = from < to ? to - 1 : to;
    next.splice(insertAt, 0, moved);
    try {
      await setScreenPlaylist(targetDeviceId, next, 'replace');
      showToast(
        isLive ? `Reordered "${moved.title}"` : `Reordered "${moved.title}" — applies on reconnect`,
        'ok',
      );
    } catch (e) {
      showToast(`Reorder failed: ${e.message}`, 'err');
    }
  };

  // The push payload uses `url`; the PreviewModal expects `mediaUrl`. Normalise
  // when opening so the same modal works from the library and from here.
  const openPreview = (item) => {
    if (!item?.url && !item?.mediaUrl) return;
    setPreviewVideo({
      ...item,
      mediaUrl: item.mediaUrl || item.url,
    });
  };

  return (
    <AppShell current="screens">
      <PageHeader
        crumbs={[
          { label: 'Screens', href: '/screens' },
          { label: store.name, href: `/screens/${store.id}` },
          screen.name,
        ]}
        title={screen.name}
        actions={
          <>
            <Button variant="secondary" size="sm" icon={<Icon.refresh size={12} />}>Rotate</Button>
            <Button variant="secondary" size="sm" icon={<Icon.sync size={12} />} onClick={handleOpenSync}>Sync to…</Button>
            <Button variant="ghost" size="sm" icon={<Icon.more size={14} />} />
          </>
        }
      />
      {syncOpen && typeof SyncPicker !== 'undefined' && <SyncPicker onClose={() => setSyncOpen(false)} />}
      {addOpen && canEdit && (
        <AddContentModal
          targetDeviceId={targetDeviceId}
          targetName={screen.name}
          onClose={() => setAddOpen(false)}
        />
      )}
      <PreviewModal video={previewVideo} onClose={() => setPreviewVideo(null)} />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 300px', gap: 20, alignItems: 'start' }}>
          {/* Main column */}
          <div>
            {/* Now playing preview */}
            <div style={{ marginBottom: 22 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <StatusDot status={isLive ? 'online' : 'offline'} pulse={isLive} />
                <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-2)' }}>{isLive && current ? 'Now playing' : isLive ? 'Connected, idle' : 'Offline'}</span>
                <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>· last seen {statusValues.lastSeen}</span>
              </div>
              {current ? (
                <div
                  onClick={() => openPreview(current)}
                  style={{ position: 'relative', borderRadius: 12, overflow: 'hidden', aspectRatio: '16/9', cursor: 'pointer' }}>
                  <Thumbnail title={current.title} brand={current.brand} aspect="16/9" size="lg" />
                  <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,0.15)' }}>
                    <div style={{
                      width: 56, height: 56, borderRadius: '50%',
                      background: 'rgba(255,255,255,0.92)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      color: 'var(--ink-0)',
                    }}><Icon.play size={20} /></div>
                  </div>
                </div>
              ) : (
                <div className="placeholder-tile" style={{ aspectRatio: '16/9', borderRadius: 12, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <span style={{ fontSize: 13, color: 'var(--ink-4)' }}>{isLive ? 'Splash on loop · push content from the library' : 'Screen is offline'}</span>
                </div>
              )}
            </div>

            {/* Playlist */}
            <div>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)' }}>Playlist</div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>
                    {playlist.length} video{playlist.length === 1 ? '' : 's'}
                    {totalDurationSec > 0 && ` · ${totalDurationSec}s loop`}
                    {isLive && ` · revision ${liveScreen.currentRevision || 0}`}
                  </div>
                </div>
                <Button
                  variant="secondary" size="sm" icon={<Icon.trash size={12} />}
                  disabled={!canEdit || playlist.length === 0}
                  onClick={handleClearAll}>
                  Clear all
                </Button>
                <Button
                  variant="secondary" size="sm" icon={<Icon.plus size={12} />}
                  disabled={!canEdit}
                  onClick={() => canEdit && setAddOpen(true)}>
                  Add content
                </Button>
              </div>
              {!isLive && hasHistory && (
                <div style={{
                  padding: '10px 12px', marginBottom: 8,
                  border: 'var(--border)', borderRadius: 8,
                  background: 'var(--ink-9)',
                  display: 'flex', alignItems: 'center', gap: 8,
                  fontSize: 11.5, color: 'var(--ink-3)',
                }}>
                  <Icon.warning size={13} />
                  <span>Screen offline — edits queue and apply when the tablet next reconnects.</span>
                </div>
              )}
              {playlist.length === 0 ? (
                <div style={{ padding: '24px', border: 'var(--border)', borderRadius: 8, textAlign: 'center', color: 'var(--ink-4)', fontSize: 12 }}>
                  Playlist is empty. Push from the content library.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {playlist.map((v, i) => (
                    <PlaylistItem
                      key={v.id || i} v={v} i={i} isCurrent={isLive && i === 0}
                      onPreview={() => openPreview(v)}
                      draggable={canEdit}
                      isDragging={dragIndex === i}
                      isDropTargetAbove={overIndex === i && dragIndex !== null && dragIndex !== i}
                      onDragStart={(e) => {
                        if (!canEdit) return;
                        setDragIndex(i);
                        // Required for Firefox to start a drag.
                        try { e.dataTransfer.setData('text/plain', String(i)); } catch (_) {}
                        e.dataTransfer.effectAllowed = 'move';
                      }}
                      onDragOver={(e) => {
                        if (dragIndex == null) return;
                        e.preventDefault();
                        e.dataTransfer.dropEffect = 'move';
                        if (overIndex !== i) setOverIndex(i);
                      }}
                      onDragLeave={() => {
                        if (overIndex === i) setOverIndex(null);
                      }}
                      onDrop={(e) => {
                        e.preventDefault();
                        const from = dragIndex;
                        const to = i;
                        setDragIndex(null);
                        setOverIndex(null);
                        handleReorder(from, to);
                      }}
                      onDragEnd={() => { setDragIndex(null); setOverIndex(null); }}
                      onRemove={canEdit ? async () => {
                        try {
                          const next = playlist.filter((x, idx) => idx !== i);
                          await setScreenPlaylist(targetDeviceId, next, 'replace');
                          showToast(
                            isLive ? `Removed "${v.title}"` : `Removed "${v.title}" — applies on reconnect`,
                            'ok',
                          );
                        } catch (e) {
                          showToast(`Remove failed: ${e.message}`, 'err');
                        }
                      } : null}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Right sidebar */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Card padding={16}>
              <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 6 }}>Status</div>
              <div>
                <StatusLine label="Health" value={
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <StatusDot status={isLive ? 'online' : 'offline'} /> {statusValues.health.label}
                  </span>
                } tone={statusValues.health.tone} />
                <StatusLine label="Last seen" value={statusValues.lastSeen} mono />
                <StatusLine label="Device" value={statusValues.device} />
                <StatusLine label="Tier" value={statusValues.tier} mono />
                <StatusLine label="Orientation" value={statusValues.orientation} />
                <StatusLine label="Cache" value={statusValues.storage} mono />
                <StatusLine label="Free disk" value={statusValues.free} mono />
                <StatusLine label="App version" value={statusValues.appVersion} mono />
                <StatusLine label="Playlist" value={statusValues.revision} mono />
              </div>
            </Card>

            {/* Splash mix toggle — only meaningful when live. */}
            <Card padding={16}>
              <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 6 }}>Display</div>
              <ToggleRow
                label="Mix splash with playlist"
                sub={canEdit
                  ? (isLive
                    ? 'Plays the bundled splash between videos. Default on.'
                    : 'Plays the bundled splash between videos. Saves now, applies when the tablet reconnects.')
                  : 'Plays the bundled splash between videos. Default on.'}
                value={hasHistory ? !!lastKnown.mixSplash : true}
                onChange={handleMixSplashToggle}
                disabled={!canEdit}
              />
            </Card>

            {/* Schedule placeholder — same as before */}
            <Card padding={16}>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                <div style={{ flex: 1, fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Schedule</div>
                <Button variant="ghost" size="sm">Edit</Button>
              </div>
              <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>No active schedules · default playlist</div>
            </Card>

            <Card padding={16} style={{ borderColor: 'rgba(185, 28, 28, 0.15)' }}>
              <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 10 }}>Danger zone</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <Button variant="secondary" size="sm" icon={<Icon.refresh size={12} />} disabled={!canEdit || busy === 'reboot'}
                  onClick={() => handleCommand('reboot', 'Reboot')}
                  style={{ justifyContent: 'flex-start' }}>
                  {busy === 'reboot' ? 'Rebooting…' : 'Reboot screen'}
                </Button>
                <Button variant="secondary" size="sm" icon={<Icon.trash size={12} />} disabled={!canEdit || busy === 'clearCache'}
                  onClick={() => handleCommand('clearCache', 'Clear cache on')}
                  style={{ justifyContent: 'flex-start' }}>
                  {busy === 'clearCache' ? 'Clearing…' : 'Clear cache'}
                </Button>
                <Button variant="danger" size="sm" icon={<Icon.close size={12} />} disabled={!canEdit || busy === 'unregister'}
                  onClick={() => handleCommand('unregister', 'Unregister')}
                  style={{ justifyContent: 'flex-start' }}>
                  {busy === 'unregister' ? 'Unregistering…' : 'Unregister device'}
                </Button>
              </div>
              {!canEdit && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 10 }}>Available once the screen registers.</div>}
              {canEdit && !isLive && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 10 }}>Commands queue and run when the tablet reconnects.</div>}
            </Card>
          </div>
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { ScreenDetail });
