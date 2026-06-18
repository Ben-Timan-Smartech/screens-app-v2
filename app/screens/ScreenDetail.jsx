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

// SyncJoinConfirmModal — content-mismatch confirmation. Pops when the
// user ticks a candidate screen whose current playlist differs from
// the screen they're configuring. Confirming pushes this screen's
// playlist to the candidate and joins it to the group. Cancelling is
// a no-op.
const SyncJoinConfirmModal = ({ thisScreen, target, onCancel, onConfirm }) => {
  const myCount = (thisScreen?.currentItems || []).length;
  const theirCount = (target?.currentItems || []).length;
  return (
    <div
      onClick={onCancel}
      style={{
        position: 'absolute', inset: 0, zIndex: 35,
        background: 'rgba(9,9,11,0.5)', backdropFilter: 'blur(2px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
      }}>
      <div
        onClick={(e) => e.stopPropagation()}
        className="scr-modal-panel"
        style={{
          width: 480, maxWidth: '92%',
          background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 12,
          padding: 22,
          boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
        }}>
        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--ink-1)', marginBottom: 6 }}>
          Different content — replace it?
        </div>
        <div style={{ fontSize: 12.5, color: 'var(--ink-3)', lineHeight: 1.55, marginBottom: 16 }}>
          <strong style={{ color: 'var(--ink-1)' }}>{target.name || target.deviceId}</strong>
          {' '}is currently playing
          {' '}<strong style={{ color: 'var(--ink-1)' }}>{theirCount} video{theirCount === 1 ? '' : 's'}</strong>
          {' '}— different from this screen's
          {' '}<strong style={{ color: 'var(--ink-1)' }}>{myCount} video{myCount === 1 ? '' : 's'}</strong>.
          {' '}Sync only works when group members play the same loop.
          <div style={{ marginTop: 10 }}>
            Push this screen's playlist over to {target.name || 'it'} and join the sync group?
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
          <Button variant="primary" size="sm" icon={<Icon.arrowR size={12} />} onClick={onConfirm}>
            Replace + join
          </Button>
        </div>
      </div>
    </div>
  );
};

// Compare two playlists for sync purposes. Sync only makes sense when
// every group member is playing the same loop in the same order; two
// screens with identical items in identical order are "matched."
// Empty + empty also matches (the next push will land identically).
const playlistsMatch = (a, b) => {
  const idsA = (a || []).map((v) => v && v.id);
  const idsB = (b || []).map((v) => v && v.id);
  if (idsA.length !== idsB.length) return false;
  for (let i = 0; i < idsA.length; i++) {
    if (idsA[i] !== idsB[i]) return false;
  }
  return true;
};

// SyncGroupCard — visual sync-group picker. Replaces the v0.1.6 text
// input which was too easy to typo into a typo-only-singleton group.
//
// Behavior:
// - Lists every OTHER registered screen with a checkbox.
// - Boxes for screens already in the same group are pre-checked.
// - Checking a previously-independent screen joins it to this
//   screen's group. If this screen wasn't in a group either, both
//   get added to a new group keyed off the storeId (or this
//   screen's id).
// - Unchecking a member removes it from the group.
// - A "Stop syncing" button removes THIS screen from the group.
//
// Content-match guard (v0.1.12): syncing two screens that have
// different playlists is meaningless — they'd play different content
// at the same offset. When the user ticks a screen whose playlist
// doesn't match this one, the parent opens a confirmation modal
// asking whether to push this screen's playlist to the other first.
//
// The server tracks the group label as a string — clients never
// need to type it. The UI invents sensible labels when a group
// needs to be created.
// v0.1.57: collapsed summary pill that opens the full picker in a
// modal. Pre-v0.1.57 the full member-grid + buttons sat permanently
// on ScreenDetail, eating ~250 px even when the screen wasn't in a
// group. Most operators just want the status at a glance.
const SyncGroupSummary = ({ screen, allScreens, canEdit, isLive, onOpen }) => {
  const groupId = screen.syncGroup || null;
  const memberCount = React.useMemo(
    () => (allScreens || []).filter((s) => s.syncGroup && s.syncGroup === groupId).length,
    [allScreens, groupId],
  );
  return (
    <Card padding={14}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 2 }}>Sync group</div>
          {groupId ? (
            <div style={{ fontSize: 11, color: 'var(--ok)' }}>
              ● Syncing with {memberCount - 1 > 0
                ? `${memberCount - 1} other screen${memberCount - 1 === 1 ? '' : 's'}`
                : 'no one yet'}
            </div>
          ) : (
            <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>● Independent playback</div>
          )}
        </div>
        <Button variant="secondary" size="sm" onClick={onOpen}>
          {groupId ? 'Manage' : 'Set up'}
        </Button>
      </div>
    </Card>
  );
};

const SyncGroupModal = ({ open, onClose, ...props }) => {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{
      position: 'fixed', inset: 0, zIndex: 40,
      background: 'rgba(9,9,11,0.5)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width: 'min(560px, 92%)', maxHeight: '85%',
        background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 14,
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
        boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', padding: '12px 16px', borderBottom: 'var(--border)' }}>
          <div style={{ flex: 1, fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Sync group</div>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} />
        </div>
        <div style={{ flex: 1, overflow: 'auto' }}>
          <SyncGroupCardBody {...props} onAfterAction={onClose} />
        </div>
      </div>
    </div>
  );
};

const SyncGroupCardBody = ({ screen, allScreens, onSetGroup, onSetGroupForDevice, onRequestJoinWithDifferentContent, onCalibrate, canEdit, isLive, onAfterAction }) => {
  const groupId = screen.syncGroup || null;
  // Members = every screen in this group (including the current one).
  // Other screens = all other registered screens, sorted by name.
  const members = React.useMemo(
    () => (allScreens || []).filter((s) => s.syncGroup && s.syncGroup === groupId),
    [allScreens, groupId],
  );
  const others = React.useMemo(
    () => (allScreens || [])
      .filter((s) => s.deviceId !== screen.deviceId)
      .sort((a, b) => (a.name || a.deviceId).localeCompare(b.name || b.deviceId)),
    [allScreens, screen.deviceId],
  );

  // Suggested group key if this screen isn't in one yet. Prefer the
  // store id so multiple screens at the same store fall together;
  // fall back to the device id when no store is configured.
  const suggestedKey = `store:${screen.location?.storeId || screen.deviceId}`;
  const effectiveGroup = groupId || suggestedKey;

  const onToggleOther = (other) => {
    if (!canEdit) return;
    const otherInGroup = !!(other.syncGroup && other.syncGroup === groupId);
    if (otherInGroup) {
      // Removing other from group → set their syncGroup to null.
      onSetGroupForDevice(other.deviceId, null);
      return;
    }
    // Joining: enforce content-match. Sync is meaningless when the
    // two screens are playing different playlists — they'd land on
    // the same loop offset but show different videos. If the content
    // doesn't match, defer to the parent which puts up a modal
    // asking whether to push this screen's playlist to the other
    // before joining.
    const myItems = screen.currentItems || [];
    const theirItems = other.currentItems || [];
    if (!playlistsMatch(myItems, theirItems)) {
      onRequestJoinWithDifferentContent(other);
      return;
    }
    // Content already matches — join silently.
    if (!groupId) onSetGroup(suggestedKey);
    onSetGroupForDevice(other.deviceId, effectiveGroup);
  };

  // v0.1.57: rendered inside SyncGroupModal which already provides
  // its own framing. The outer Card wrapper is gone — content sits
  // directly against the modal padding.
  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Sync group</div>
        {groupId ? (
          <span style={{ fontSize: 11, color: 'var(--ok)' }}>
            ● Syncing with {members.length - 1 > 0 ? `${members.length - 1} other screen${members.length - 1 === 1 ? '' : 's'}` : 'no one yet'}
          </span>
        ) : (
          <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>● Independent playback</span>
        )}
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', lineHeight: 1.5, marginBottom: 12 }}>
        {isLive
          ? 'Tick screens to play the same video at the same time. Same content needs to be on each screen — pushing playlists from this page fans out automatically.'
          : 'Tick screens to play the same video at the same time. Saves now, applies when the tablet reconnects.'}
      </div>

      {others.length === 0 ? (
        <div style={{
          padding: '12px 14px', border: 'var(--border)', borderRadius: 6,
          fontSize: 12, color: 'var(--ink-4)', textAlign: 'center',
        }}>
          No other screens registered yet — sync needs at least two.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {others.map((other) => {
            const inSameGroup = !!(other.syncGroup && other.syncGroup === groupId);
            const inDifferentGroup = !!(other.syncGroup && other.syncGroup !== groupId);
            return (
              <button
                key={other.deviceId}
                disabled={!canEdit}
                onClick={() => onToggleOther(other)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10,
                  width: '100%', padding: '8px 10px',
                  border: inSameGroup ? '1px solid var(--ink-0)' : 'var(--border)',
                  borderRadius: 6,
                  background: inSameGroup ? 'var(--ink-8)' : 'var(--ink-10)',
                  textAlign: 'left',
                  opacity: inDifferentGroup ? 0.55 : 1,
                  cursor: canEdit ? 'pointer' : 'default',
                }}
                title={inDifferentGroup ? `Currently in group '${other.syncGroup}'. Ticking will move it to this one.` : undefined}>
                <span style={{
                  width: 16, height: 16, borderRadius: 3,
                  background: inSameGroup ? 'var(--ink-0)' : 'var(--ink-10)',
                  border: inSameGroup ? 'none' : '1.5px solid var(--ink-6)',
                  color: 'var(--on-accent)',
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  flexShrink: 0,
                }}>
                  {inSameGroup && <Icon.check size={11} />}
                </span>
                <StatusDot status={other.online ? 'online' : 'offline'} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {other.name || other.deviceId}
                  </div>
                  <div style={{ fontSize: 10.5, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {[other.location?.storeId, other.location?.screenCode].filter(Boolean).join(' · ') || other.deviceId}
                    {inDifferentGroup && ` · in '${other.syncGroup}'`}
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      {/* Calibrate — lights up every group member (or this one screen
          if it's solo) with a giant ticking server-corrected clock
          overlay for 60 s. Staff visually confirm whether the screens
          tick on the same wall-clock second; if they do, clock sync
          is healthy and any visible drift in playback is a content
          / queue issue rather than a clock issue. Always available
          when the screen is live — useful even pre-sync to eyeball
          a single tablet's clock against your watch. */}
      <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Button
          variant="secondary" size="sm"
          disabled={!canEdit || !isLive}
          onClick={() => onCalibrate && onCalibrate()}
          title={isLive ? 'Show a giant synced clock on every group member for 60 s' : 'Available once the screen reconnects.'}>
          Calibrate screens
        </Button>
        {groupId && (
          <Button
            variant="ghost" size="sm"
            disabled={!canEdit}
            onClick={() => onSetGroup(null)}>
            Stop syncing this screen
          </Button>
        )}
      </div>
    </div>
  );
};

// PollModeRow — three discrete poll cadences replacing the old binary
// Low Data Mode toggle. Visual is a segmented control so it reads as
// a single choice rather than a stack of options. Slow also skips the
// per-location splash download.
// DisplayResolutionCard — v0.1.14. HDMI mode picker for boxes like
// the TX3 Mini that boot at 720p but support 1080p. Lists every mode
// the tablet's heartbeat reported in supportedModes + an "Auto" row
// at the top. Selected mode is the override the CMS most recently
// pushed (screen.displayMode); the radio next to "Auto" highlights
// when no override is set. We also show the tablet's currently
// active mode as a chip so the user can see whether the override
// has actually taken effect yet.
//
// Hidden entirely when the tablet hasn't reported any supportedModes
// (older app version, or a device whose Display API doesn't expose
// them). That way the picker doesn't appear with one greyed "Auto"
// row that does nothing.
const DisplayResolutionCard = ({ screen, onSetMode, canEdit, isLive }) => {
  const modes = Array.isArray(screen?.supportedModes) ? screen.supportedModes : [];
  if (modes.length === 0) return null;
  const override = screen?.displayMode ?? null;        // null = auto
  const active = screen?.activeDisplayMode || 0;       // 0 = unknown
  // Cluster duplicate resolutions (same w×h different refresh) but
  // keep distinct refresh rates so the user can pick e.g. 1080p@60
  // vs 1080p@50 if the panel cares.
  const sortedModes = [...modes].sort((a, b) =>
    (b.w * b.h) - (a.w * a.h) || (b.hz || 0) - (a.hz || 0)
  );
  const labelFor = (m) => {
    const hz = m.hz ? `${Math.round(m.hz)}Hz` : '';
    return `${m.w}×${m.h}${hz ? ` · ${hz}` : ''}`;
  };
  return (
    <Card padding={16}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Display resolution</div>
        {active > 0 && (() => {
          const activeMode = modes.find(m => m.id === active);
          if (!activeMode) return null;
          return (
            <span style={{
              fontSize: 10, fontWeight: 500,
              color: 'var(--ink-3)',
              background: 'var(--ink-8)',
              padding: '2px 8px', borderRadius: 999,
              letterSpacing: 0.4,
            }}>Currently {labelFor(activeMode)}</span>
          );
        })()}
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', marginBottom: 10 }}>
        {isLive
          ? 'Switches the box\'s HDMI output to the selected mode. Some boxes (TX3 Mini, generic Android TV sticks) boot at 720p even when the panel supports more — pick a higher mode here.'
          : 'Saves the override now; the tablet applies it when it reconnects.'}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <ResolutionRow
          label="Auto"
          sub="Let the box keep its current HDMI mode."
          selected={override === null}
          disabled={!canEdit}
          onClick={() => override !== null && onSetMode(null)}
        />
        {sortedModes.map(m => (
          <ResolutionRow
            key={m.id}
            label={labelFor(m)}
            sub={m.id === active ? 'Currently active on the tablet.' : null}
            selected={override === m.id}
            disabled={!canEdit}
            onClick={() => override !== m.id && onSetMode(m.id)}
          />
        ))}
      </div>
    </Card>
  );
};

const ResolutionRow = ({ label, sub, selected, disabled, onClick }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    style={{
      display: 'flex', alignItems: 'center', gap: 10,
      padding: '8px 10px', textAlign: 'left',
      background: selected ? 'var(--ink-9)' : 'transparent',
      border: selected ? 'var(--border-strong)' : '1px solid transparent',
      borderRadius: 6,
      cursor: disabled ? 'not-allowed' : (selected ? 'default' : 'pointer'),
      opacity: disabled ? 0.5 : 1,
    }}>
    <span style={{
      width: 14, height: 14, borderRadius: '50%',
      border: 'var(--border-strong)',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
    }}>
      {selected && (
        <span style={{
          width: 6, height: 6, borderRadius: '50%',
          background: 'var(--ink-1)',
        }} />
      )}
    </span>
    <div style={{ flex: 1 }}>
      <div style={{ fontSize: 12, fontWeight: selected ? 600 : 500, color: 'var(--ink-1)' }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>{sub}</div>}
    </div>
  </button>
);

const PollModeRow = ({ value, onChange, disabled, isLive }) => {
  const modes = [
    { key: 'fast',   label: 'Fast',   detail: '10 s'   },
    { key: 'normal', label: 'Normal', detail: '60 s'   },
    { key: 'slow',   label: 'Slow',   detail: '5 min'  },
  ];
  const sub = ({
    fast:   'Tablet checks the server every 10 s. Use for install / debugging.',
    normal: 'Tablet checks the server every 60 s. Default for most screens.',
    slow:   'Tablet checks every 10 min and skips the per-location splash. Use for cellular / metered installs.',
  })[value] || '';
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '8px 0' }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Poll mode</div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>
          {sub}
          {!isLive && ' Saves now, applies when the tablet reconnects.'}
        </div>
      </div>
      <div style={{
        display: 'inline-flex',
        border: 'var(--border-strong)', borderRadius: 6, overflow: 'hidden',
        opacity: disabled ? 0.5 : 1,
      }}>
        {modes.map((m, i) => {
          const selected = value === m.key;
          return (
            <button
              key={m.key}
              disabled={disabled}
              onClick={() => { if (!selected) onChange(m.key); }}
              style={{
                padding: '6px 12px',
                background: selected ? 'var(--ink-0)' : 'var(--ink-10)',
                color: selected ? 'var(--on-accent)' : 'var(--ink-2)',
                fontSize: 12,
                fontWeight: selected ? 600 : 500,
                cursor: disabled ? 'not-allowed' : (selected ? 'default' : 'pointer'),
                borderLeft: i === 0 ? 'none' : '1px solid var(--ink-7)',
                minWidth: 60,
              }}>
              {m.label}
              <div style={{ fontSize: 10, fontWeight: 400, opacity: 0.7, marginTop: 1 }}>{m.detail}</div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

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
  const vp = useViewport();
  const live = useLiveScreens();
  // The screenId in the URL is the tablet's deviceId. Look it up directly.
  const lastKnown = (live.screens || []).find((s) => s.deviceId === screenId) || null;
  // v0.1.60: split "online" (recently heartbeated — status pill stays
  // green) from "live" (currently polling — commands will land on the
  // next cycle without a long queue). Old servers don't emit `live`;
  // fall back to `online` so this code keeps working pre-upgrade.
  const isOnline = !!lastKnown?.online;
  const isLive = lastKnown?.live != null ? !!lastKnown.live : isOnline;
  const liveScreen = isOnline ? lastKnown : null;
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
    // v0.1.57: unregister is now a server-side delete. The confirm
    // copy + success toast are tailored to that — there's no "queued"
    // step to wait through.
    const isUnregister = command === 'unregister';
    const note = isUnregister
      ? `Unregister ${liveScreen?.name || 'this screen'}?\n\nThis removes it from the CMS immediately. ` +
        `If the tablet is still online it'll fall back to onboarding on its next poll.`
      : isLive
      ? `${label} the live tablet?`
      : `${label}? Screen is offline — command will run when it reconnects.`;
    if (!confirm(note)) return;
    setBusy(command);
    try {
      await sendScreenCommand(targetDeviceId, command);
      if (isUnregister) {
        showToast(`Removed ${liveScreen?.name || 'screen'} from the CMS`, 'ok');
        // Navigate back to the store view — this screen is gone.
        setTimeout(() => navigate(`/screens/${storeId}`), 600);
        return;
      }
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

  const handleAudioToggle = async (next) => {
    if (!targetDeviceId) return;
    try {
      await setScreenAudio(targetDeviceId, next);
      showToast(
        isLive
          ? (next ? 'Audio enabled — all videos play with sound' : 'Audio muted — only library-flagged videos play sound')
          : (next ? 'Audio will turn on next reconnect' : 'Audio will mute on next reconnect'),
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  const handlePollModeChange = async (mode) => {
    if (!targetDeviceId) return;
    try {
      await setScreenPollMode(targetDeviceId, mode);
      const label = { fast: 'Fast (10 s)', normal: 'Normal (60 s)', slow: 'Slow (5 min)' }[mode] || mode;
      showToast(
        isLive
          ? `Poll mode → ${label}`
          : `Poll mode will switch to ${label} on reconnect`,
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  const handleRefreshNow = async () => {
    if (!targetDeviceId) return;
    try {
      await sendScreenCommand(targetDeviceId, 'refresh');
      // ETA depends on current poll mode — be honest about how long
      // the tablet might take to pick up the refresh command.
      const mode = hasHistory ? lastKnown.pollMode : 'normal';
      const eta = { fast: '~10 s', normal: '~60 s', slow: '~10 min' }[mode] || '~60 s';
      showToast(
        isLive
          ? `Refresh queued — tablet picks it up in ${eta}`
          : `Refresh queued — runs when the tablet reconnects`,
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  // Fire the calibration overlay on every group member (or just this
  // screen if it's solo). Targets the group when there is one — falls
  // back to the deviceId so the staff can eye-check a single tablet
  // against their own watch. 60-second window: long enough to walk to
  // each screen, short enough that the overlay doesn't get stuck if
  // the staff forgets to dismiss it (it auto-hides on the tablet
  // when correctedNow() passes the cutoff).
  const handleCalibrate = async () => {
    if (!targetDeviceId) return;
    const groupOrSelf = lastKnown?.syncGroup || targetDeviceId;
    try {
      const res = await calibrateSyncGroup(groupOrSelf, 60);
      const n = res.screensTargeted || 1;
      showToast(
        `Calibration started on ${n} screen${n === 1 ? '' : 's'} for 60s — watch the digits match.`,
        'ok',
      );
    } catch (e) {
      showToast(`Calibrate failed: ${e.message}`, 'err');
    }
  };

  // Push a new HDMI mode override. `modeId` is an int from the
  // screen's supportedModes list, or null to clear (== auto). The
  // tablet re-validates against its supportedModes before touching
  // the window, so a stale modeId here can't lock the box into
  // something it can't render.
  const handleSetDisplayMode = async (modeId) => {
    if (!targetDeviceId) return;
    try {
      await setScreenDisplayMode(targetDeviceId, modeId);
      // Build a readable label for the toast — works even when the
      // tablet's most recent supportedModes hasn't been re-fetched
      // (it sticks around in lastKnown until the next heartbeat).
      const modes = lastKnown?.supportedModes || [];
      const m = modeId == null ? null : modes.find(x => x.id === modeId);
      const label = !m
        ? 'Auto (let the box decide)'
        : `${m.w}×${m.h}${m.hz ? ` @ ${Math.round(m.hz)}Hz` : ''}`;
      showToast(
        isLive ? `Resolution → ${label}` : `Resolution will switch to ${label} on reconnect`,
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  // Set this screen's sync group. `next` can be a group label string
  // or null to detach. Used by SyncGroupCard's "Stop syncing" button
  // and as the bootstrap call when joining the first other screen.
  const handleSetSyncGroup = async (next) => {
    if (!targetDeviceId) return;
    try {
      await setScreenSyncGroup(targetDeviceId, next || null);
      showToast(
        next
          ? 'Joined sync group — push a playlist to keep group members aligned.'
          : 'Stopped syncing this screen.',
        'ok',
      );
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  // Move some OTHER screen into / out of a group. Same endpoint, the
  // CMS just calls it on a different deviceId. SyncGroupCard fires
  // this when a checkbox is ticked or unticked.
  const handleSetSyncGroupForDevice = async (deviceId, next) => {
    if (!deviceId) return;
    try {
      await setScreenSyncGroup(deviceId, next || null);
      // Don't toast here; the parent click already showed visual
      // feedback by re-rendering the checkbox state.
    } catch (e) {
      showToast(`Failed: ${e.message}`, 'err');
    }
  };

  // Confirmed: push this screen's playlist to `other` and add it to
  // this screen's sync group. Called by the content-mismatch modal.
  const confirmJoinReplacingContent = async () => {
    const other = syncJoinTarget;
    if (!other || !targetDeviceId) { setSyncJoinTarget(null); return; }
    const myItems = lastKnown?.currentItems || [];
    // Suggested group key — keep in step with SyncGroupCard's logic.
    const suggested = lastKnown?.syncGroup || `store:${lastKnown?.location?.storeId || lastKnown?.deviceId}`;
    try {
      // Step 1: push our playlist to the other screen (replace mode).
      // This is the cross-screen edit the modal is asking permission
      // for — without it, the two would still be in different groups
      // OR same group with mismatched content.
      await setScreenPlaylist(other.deviceId, myItems, 'replace');
      // Step 2: make sure this screen is in a group.
      if (!lastKnown?.syncGroup) {
        await setScreenSyncGroup(targetDeviceId, suggested);
      }
      // Step 3: move the other screen into that group.
      await setScreenSyncGroup(other.deviceId, lastKnown?.syncGroup || suggested);
      showToast(
        `Pushed ${myItems.length} video${myItems.length === 1 ? '' : 's'} to ${other.name || other.deviceId} and joined the sync group.`,
        'ok',
      );
    } catch (e) {
      showToast(`Sync join failed: ${e.message}`, 'err');
    } finally {
      setSyncJoinTarget(null);
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
  // v0.1.57: sync group modal — opens when the summary pill is tapped.
  const [syncModalOpen, setSyncModalOpen] = React.useState(false);
  // v0.1.57: Danger-zone actions are hidden behind a toggle so the
  // page fits one viewport. Update / Reboot / Clear cache / Unregister
  // are needed rarely; the four-button stack was always-on previously.
  const [dangerOpen, setDangerOpen] = React.useState(false);
  // Set when the user ticks a sync-group candidate that has different
  // content from this screen. The modal asks whether to push this
  // screen's playlist to the candidate before joining; null = closed.
  const [syncJoinTarget, setSyncJoinTarget] = React.useState(null);
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
      {syncJoinTarget && (
        <SyncJoinConfirmModal
          thisScreen={lastKnown}
          target={syncJoinTarget}
          onCancel={() => setSyncJoinTarget(null)}
          onConfirm={confirmJoinReplacingContent}
        />
      )}
      <div style={{
        flex: 1, overflow: 'auto',
        padding: vp.isCompact ? '16px 14px 32px' : '20px 24px 40px',
      }}>
        <div style={{
          display: 'grid',
          // Two-column on laptop+ (main playlist + status/config rail);
          // single stacked column on mobile/tablet so the rail flows
          // beneath the main content.
          gridTemplateColumns: vp.isCompact ? '1fr' : '1fr 300px',
          gap: 20, alignItems: 'start',
        }}>
          {/* Main column */}
          <div>
            {/* Now playing preview */}
            <div style={{ marginBottom: 22 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                {/* v0.1.60: three states. Live = polling actively right
                    now; Online = recently heartbeated, expected back
                    soon; Offline = past the online threshold. The dot
                    pulses only while live so the page reads "active"
                    at a glance. */}
                <StatusDot status={isOnline ? 'online' : 'offline'} pulse={isLive} />
                <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-2)' }}>
                  {isLive && current ? 'Now playing'
                   : isLive            ? 'Connected, idle'
                   : isOnline && current ? 'Last seen playing'
                   : isOnline           ? 'Online'
                   : 'Offline'}
                </span>
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
                  variant="secondary" size="sm" icon={<Icon.refresh size={12} />}
                  disabled={!canEdit}
                  onClick={handleRefreshNow}
                  title="Tells the tablet to re-poll. Useful in Slow mode when you want a push to land before the next 5-minute tick.">
                  Refresh now
                </Button>
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

            {/* Splash mix toggle — only meaningful when live.
                v0.1.33: locked off when the screen is in a sync group.
                The server forces mixSplash=false in /api/state for any
                screen with a syncGroup (the splash's extra duration
                breaks the loop math), so flipping it here would just
                bounce back on the next poll. Greyed out + explainer. */}
            {(() => {
              const inGroup = hasHistory && !!lastKnown.syncGroup;
              return (
                <Card padding={16}>
                  <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 6 }}>Display</div>
                  <ToggleRow
                    label="Mix splash with playlist"
                    sub={inGroup
                      ? `Disabled — screen is in sync group '${lastKnown.syncGroup}'. Mix splash breaks the group's loop math; leave the group to enable.`
                      : (canEdit
                          ? (isLive
                              ? 'Plays the bundled splash between videos. Default on.'
                              : 'Plays the bundled splash between videos. Saves now, applies when the tablet reconnects.')
                          : 'Plays the bundled splash between videos. Default on.')}
                    value={inGroup ? false : (hasHistory ? !!lastKnown.mixSplash : true)}
                    onChange={handleMixSplashToggle}
                    disabled={!canEdit || inGroup}
                  />
              <ToggleRow
                label="Audio"
                sub={canEdit
                  ? (isLive
                    ? 'Off (default): muted; videos can opt in via the Content Library. On: every video plays with sound.'
                    : 'Off (default): muted; videos can opt in via the Content Library. On: every video plays with sound. Applies when the tablet reconnects.')
                  : 'Off by default; videos can opt in via the Content Library.'}
                value={hasHistory ? !!lastKnown.audioOn : false}
                onChange={handleAudioToggle}
                disabled={!canEdit}
              />
              <PollModeRow
                value={hasHistory ? (lastKnown.pollMode || 'normal') : 'normal'}
                onChange={handlePollModeChange}
                disabled={!canEdit}
                isLive={isLive}
              />
            </Card>
              );
            })()}

            {/* v0.1.57: sync group now collapses to a small summary
                pill. Tap Manage / Set up to open the full picker in a
                modal — keeps the page short for the common case where
                the operator just wants to glance at sync status. */}
            {hasHistory && (
              <SyncGroupSummary
                screen={lastKnown}
                allScreens={live.screens || []}
                canEdit={canEdit}
                isLive={isLive}
                onOpen={() => setSyncModalOpen(true)}
              />
            )}
            {hasHistory && (
              <SyncGroupModal
                open={syncModalOpen}
                onClose={() => setSyncModalOpen(false)}
                screen={lastKnown}
                allScreens={live.screens || []}
                onSetGroup={handleSetSyncGroup}
                onSetGroupForDevice={handleSetSyncGroupForDevice}
                onRequestJoinWithDifferentContent={(other) => setSyncJoinTarget(other)}
                onCalibrate={handleCalibrate}
                canEdit={canEdit}
                isLive={isLive}
              />
            )}

            {/* HDMI mode override. Auto-hides on screens whose tablet
                hasn't reported supportedModes yet (older app version,
                or a device whose Display API doesn't expose them). */}
            {hasHistory && (
              <DisplayResolutionCard
                screen={lastKnown}
                onSetMode={handleSetDisplayMode}
                canEdit={canEdit}
                isLive={isLive}
              />
            )}

            {/* v0.1.57: Schedule "Coming soon" card removed — the
                Schedules sidebar is already hidden, no need to tease
                the feature on every screen detail page.

                Danger zone collapsed behind a toggle. Update / Reboot /
                Clear cache / Unregister are rare ops; surfacing the
                4-button stack permanently pushed the page off one
                viewport on common laptop heights. */}
            <Card padding={14} style={{ borderColor: 'rgba(185, 28, 28, 0.15)' }}>
              <div
                onClick={() => setDangerOpen((v) => !v)}
                style={{
                  display: 'flex', alignItems: 'center', cursor: 'pointer',
                  padding: 2, marginBottom: dangerOpen ? 10 : 0,
                }}
              >
                <div style={{ flex: 1, fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>More actions</div>
                <span style={{ fontSize: 10, color: 'var(--ink-4)', marginRight: 6 }}>
                  {dangerOpen ? 'Hide' : 'Update · Reboot · Clear cache · Unregister'}
                </span>
                <span style={{
                  display: 'inline-flex',
                  color: 'var(--ink-3)',
                  transform: dangerOpen ? 'rotate(180deg)' : 'none',
                  transition: 'transform 120ms ease',
                }}>
                  <Icon.chevD size={14} />
                </span>
              </div>
              {dangerOpen && (
                <>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <Button variant="secondary" size="sm" icon={<Icon.download size={12} />} disabled={!canEdit || busy === 'update'}
                      onClick={() => handleCommand('update', 'Triggered update on')}
                      style={{ justifyContent: 'flex-start' }}>
                      {busy === 'update' ? 'Updating…' : 'Update player APK'}
                    </Button>
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
                </>
              )}
            </Card>
          </div>
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { ScreenDetail });
