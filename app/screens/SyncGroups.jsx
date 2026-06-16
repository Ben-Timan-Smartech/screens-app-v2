/* eslint-disable */
// Sync groups — dedicated overview of every group + member screens.
//
// Sync groups used to be visible only when you opened a single screen's
// detail page (SyncGroupCard listed every other screen with a tick).
// That's fine for "join this screen to a group" but lousy for "tell me
// what groups exist across the fleet". This page closes that gap.
//
// Groups are derived from /api/screens (each screen carries a syncGroup
// field). We don't need a new server endpoint — the data already flows
// through `useLiveScreens`. Re-derived on every poll so a fresh
// registration with a `store:<id>` auto-group appears within a tick.

const SyncGroups = () => {
  const live = useLiveScreens();
  const vp = useViewport();
  const auth = useAuth();
  const user = auth.user || { permissions: [] };
  const canCommand = can(user, 'screens.command');

  // Bucket screens by their syncGroup. Stable insertion order means
  // groups appear in the order their first member was last seen,
  // which matches the Screens list ordering.
  const groups = React.useMemo(() => {
    const byGroup = new Map();
    for (const s of (live.screens || [])) {
      if (!s.syncGroup) continue;
      const list = byGroup.get(s.syncGroup) || [];
      list.push(s);
      byGroup.set(s.syncGroup, list);
    }
    return Array.from(byGroup.entries()).map(([id, members]) => {
      const sorted = members.slice().sort((a, b) => (a.name || a.deviceId).localeCompare(b.name || b.deviceId));
      const online = sorted.filter((s) => s.online).length;
      // Pick a representative member for the playlist read — group
      // members share the same playlist by definition (the fan-out
      // on push enforces this), so the first member is fine.
      const rep = sorted[0];
      return {
        id,
        members: sorted,
        memberCount: sorted.length,
        onlineCount: online,
        itemCount: (rep?.currentItems || []).length,
        revision: rep?.currentRevision ?? 0,
      };
    });
  }, [live.screens]);

  // Screens with no syncGroup — surfaced at the bottom so the operator
  // can see what's NOT grouped at a glance. Helpful when investigating
  // "why isn't this tablet syncing?"
  const orphans = React.useMemo(() => (
    (live.screens || [])
      .filter((s) => !s.syncGroup)
      .sort((a, b) => (a.name || a.deviceId).localeCompare(b.name || b.deviceId))
  ), [live.screens]);

  const onCalibrate = async (group) => {
    try {
      const res = await calibrateSyncGroup(group.id, 60);
      const n = res.screensTargeted || group.memberCount;
      showToast(`Calibration started on ${n} screen${n === 1 ? '' : 's'} in ${group.id}`, 'ok');
    } catch (e) {
      showToast(`Calibrate failed: ${e.message}`, 'err');
    }
  };

  return (
    <AppShell current="sync-groups">
      <PageHeader
        title="Sync groups"
        subtitle="Screens locked to the same playlist + loop position. Members of a group play frame-aligned."
      />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: vp.isCompact ? '12px 14px 32px' : '20px 24px 40px',
        display: 'flex', flexDirection: 'column', gap: 14,
      }}>

        {/* Empty + loading states */}
        {live.loading && groups.length === 0 && orphans.length === 0 && (
          <Card padding={20}>
            <div style={{ fontSize: 13, color: 'var(--ink-4)' }}>Checking screens…</div>
          </Card>
        )}

        {!live.loading && groups.length === 0 && (
          <Card padding={24}>
            <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 6 }}>
              No sync groups yet
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--ink-3)', lineHeight: 1.55 }}>
              Screens auto-group by store on register — a tablet with a <code style={{ background: 'var(--ink-9)', padding: '1px 4px', borderRadius: 3 }}>location.storeId</code> set joins
              <code style={{ background: 'var(--ink-9)', padding: '1px 4px', borderRadius: 3, marginLeft: 4 }}>store:&lt;storeId&gt;</code> automatically.
              {' '}Or tick screens together from any Screen detail page.
            </div>
          </Card>
        )}

        {/* One card per group */}
        {groups.map((g) => (
          <Card key={g.id} padding={vp.isCompact ? 16 : 18}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 200 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
                  <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-0)' }}>
                    {g.id}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', fontFamily: 'ui-monospace, monospace' }}>
                    rev {g.revision}
                  </div>
                </div>
                <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 4 }}>
                  {g.memberCount} screen{g.memberCount === 1 ? '' : 's'} ·
                  {' '}<span style={{ color: g.onlineCount === g.memberCount ? 'var(--ok)' : 'var(--ink-4)' }}>
                    {g.onlineCount} online
                  </span> ·
                  {' '}{g.itemCount} video{g.itemCount === 1 ? '' : 's'} in playlist
                </div>
              </div>

              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button
                  variant="secondary" size="sm"
                  disabled={!canCommand || g.memberCount === 0}
                  onClick={() => onCalibrate(g)}
                  title="Show a 60-second synchronised clock on every member so you can eyeball that they're in sync.">
                  Calibrate
                </Button>
                {g.members[0] && (
                  <Button
                    variant="ghost" size="sm"
                    onClick={() => navigate(`/screens/${encodeURIComponent(g.members[0].deviceId)}`)}>
                    Open first member
                  </Button>
                )}
              </div>
            </div>

            {/* Member rows */}
            <div style={{ marginTop: 14, borderTop: 'var(--border-faint)', paddingTop: 10, display: 'flex', flexDirection: 'column', gap: 4 }}>
              {g.members.map((m) => {
                const sub = [m.location?.storeId, m.location?.screenCode].filter(Boolean).join(' · ');
                return (
                  <div
                    key={m.deviceId}
                    onClick={() => navigate(`/screens/${encodeURIComponent(m.deviceId)}`)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: '8px 10px',
                      borderRadius: 6,
                      cursor: 'pointer',
                    }}
                    onMouseEnter={(e) => e.currentTarget.style.background = 'var(--ink-9)'}
                    onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                    <StatusDot status={m.online ? 'online' : 'offline'} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, color: 'var(--ink-1)', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {m.name || m.deviceId}
                      </div>
                      {sub && (
                        <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {sub}
                        </div>
                      )}
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--ink-4)', fontFamily: 'ui-monospace, monospace' }}>
                      rev {m.currentRevision ?? 0}
                    </div>
                  </div>
                );
              })}
            </div>
          </Card>
        ))}

        {/* Ungrouped screens — at the bottom for diagnostic context */}
        {orphans.length > 0 && (
          <Card padding={vp.isCompact ? 16 : 18}>
            <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 4 }}>
              Not in any sync group
            </div>
            <div style={{ fontSize: 12, color: 'var(--ink-3)', marginBottom: 12 }}>
              {orphans.length} screen{orphans.length === 1 ? '' : 's'} playing independently. Tick them together from a Screen detail page to form a group.
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {orphans.map((m) => {
                const sub = [m.location?.storeId, m.location?.screenCode].filter(Boolean).join(' · ');
                return (
                  <div
                    key={m.deviceId}
                    onClick={() => navigate(`/screens/${encodeURIComponent(m.deviceId)}`)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: '8px 10px',
                      borderRadius: 6,
                      cursor: 'pointer',
                    }}
                    onMouseEnter={(e) => e.currentTarget.style.background = 'var(--ink-9)'}
                    onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                    <StatusDot status={m.online ? 'online' : 'offline'} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, color: 'var(--ink-1)', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {m.name || m.deviceId}
                      </div>
                      {sub && (
                        <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {sub}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </Card>
        )}
      </div>
    </AppShell>
  );
};

window.SyncGroups = SyncGroups;
