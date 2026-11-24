/* eslint-disable */
// Sync groups: every group across the fleet, with how it's doing and the
// actions for running one. The shared pieces live in components/sync.jsx.
//
// Groups are derived from the live screens list (each screen carries its
// syncGroup), so nothing here needs its own endpoint.

const SyncGroups = () => {
  const live = useLiveScreens();
  const vp = useViewport();
  const auth = useAuth();
  const user = auth.user || { permissions: [] };
  const canPush = can(user, 'screens.push');
  const canCommand = can(user, 'screens.command');
  const [creating, setCreating] = React.useState(false);
  const [showSolo, setShowSolo] = React.useState(false);
  useSyncTick();

  // Screens moved in the last few seconds show in their new group straight
  // away; the server's list catches up on the next poll.
  const screens = (live.screens || []).map((s) => ({ ...s, syncGroup: syncEffectiveGroup(s) }));
  const groups = syncGroupsFrom(screens);
  const solo = sortSyncScreens(screens.filter((s) => !s.syncGroup));

  const openScreen = (m) =>
    navigate(`/screens/${encodeURIComponent((m.location && m.location.storeId) || 'unassigned')}/${encodeURIComponent(m.deviceId)}`);

  return (
    <AppShell current="sync-groups">
      <PageHeader
        title="Sync groups"
        subtitle="Screens that play the same videos in step, such as one column of a wall."
        actions={canPush ? (
          <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New group</Button>
        ) : null}
      />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: vp.isCompact ? '12px 14px 32px' : '20px 24px 40px',
        display: 'flex', flexDirection: 'column', gap: 14,
      }}>
        {creating && (
          <SyncNewGroupModal screens={screens} onClose={() => setCreating(false)} />
        )}

        {live.loading && groups.length === 0 && (
          <Card padding={20}><div style={{ fontSize: 13, color: 'var(--ink-4)' }}>Checking screens…</div></Card>
        )}

        {!live.loading && groups.length === 0 && (
          <Card padding={24}>
            <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 6 }}>No sync groups yet</div>
            <div style={{ fontSize: 12.5, color: 'var(--ink-3)', lineHeight: 1.55, marginBottom: 12 }}>
              Put screens in a group to have them play the same videos at the same moment, for example the screens in one column. Choose a name and the screens, and the others take the first screen's videos.
            </div>
            {canPush && <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New group</Button>}
          </Card>
        )}

        {groups.map((g) => (
          <Card key={g.id} padding={vp.isCompact ? 14 : 18}>
            <SyncGroupPanel group={g} screens={screens} canPush={canPush} canCommand={canCommand} onNavigate={openScreen} />
          </Card>
        ))}

        {solo.length > 0 && (
          <Card padding={vp.isCompact ? 14 : 18}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
              <div style={{ flex: 1, fontSize: 13, color: 'var(--ink-2)' }}>
                {solo.length} screen{solo.length === 1 ? '' : 's'} on {solo.length === 1 ? 'its' : 'their'} own, not in a sync group
              </div>
              <Button variant="ghost" size="sm" onClick={() => setShowSolo((x) => !x)}>{showSolo ? 'Hide' : 'Show'}</Button>
            </div>
            {showSolo && (
              <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 2 }}>
                {solo.map((m) => (
                  <button key={m.deviceId} type="button" onClick={() => openScreen(m)} style={{
                    display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '7px 8px',
                    border: 'none', background: 'none', borderRadius: 6, cursor: 'pointer', textAlign: 'left',
                  }}>
                    <StatusDot status={m.online ? 'online' : 'offline'} />
                    <span style={{ flex: 1, minWidth: 0, fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{syncScreenName(m)}</span>
                    <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>{(m.location && m.location.storeId) || ''}</span>
                  </button>
                ))}
              </div>
            )}
          </Card>
        )}
      </div>
    </AppShell>
  );
};

window.SyncGroups = SyncGroups;
