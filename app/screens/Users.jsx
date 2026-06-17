/* eslint-disable */
// Users & permissions page. Lives at /#/settings/users (and /#/users
// for direct linking). Gated to roles with users.view.

const { useState, useEffect, useMemo, useCallback } = React;

const ROLE_LABELS = {
  owner:         'Owner',
  super_admin:   'Super admin',
  admin:         'Admin',
  manager:       'Manager',
  user:          'User',
  viewer:        'Viewer',
  brand_partner: 'Brand partner',
};

// v0.1.56: inline PIN editor — masked display + click to edit. Saves
// the new value via PATCH /api/users/<id> { pin: "1234" | "" } when
// the input blurs or Enter is pressed. Empty PIN clears it.
const PinCell = ({ user, editable, onSave }) => {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(user.pin || '');
  const [show, setShow] = useState(false);
  useEffect(() => { setDraft(user.pin || ''); }, [user.pin]);

  const commit = async () => {
    const next = draft.replace(/\D/g, '').slice(0, 4);
    setEditing(false);
    if (next !== (user.pin || '')) {
      if (next && next.length !== 4) {
        window.showToast && window.showToast('PIN must be 4 digits or empty', 'err');
        setDraft(user.pin || '');
        return;
      }
      await onSave(next);
    }
  };

  if (!editable) {
    return user.pin ? (
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-3)', letterSpacing: 1 }}>••••</span>
    ) : (
      <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>—</span>
    );
  }
  if (editing) {
    return (
      <input
        autoFocus
        value={draft}
        onChange={(e) => setDraft(e.target.value.replace(/\D/g, '').slice(0, 4))}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit();
          if (e.key === 'Escape') { setDraft(user.pin || ''); setEditing(false); }
        }}
        placeholder="••••"
        inputMode="numeric"
        style={{
          width: 64, height: 26, padding: '0 8px', borderRadius: 4,
          fontFamily: 'var(--font-mono)', fontSize: 13, letterSpacing: 2,
          background: 'var(--ink-10)', color: 'var(--ink-1)',
          border: 'var(--border-strong)',
        }}
      />
    );
  }
  return (
    <button
      onClick={(e) => { e.stopPropagation(); setEditing(true); }}
      onMouseEnter={() => setShow(true)}
      onMouseLeave={() => setShow(false)}
      style={{
        fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-2)', letterSpacing: 1,
        padding: '4px 8px', borderRadius: 4, background: 'var(--ink-9)',
        border: 'var(--border)', cursor: 'pointer', minWidth: 64, textAlign: 'left',
      }}
      title="Click to edit"
    >
      {user.pin
        ? (show ? user.pin : '••••')
        : <span style={{ color: 'var(--ink-4)' }}>Set PIN</span>}
    </button>
  );
};

const ROLE_BLURBS = {
  owner:         'Singular. Full control. Cannot be demoted.',
  super_admin:   'Full control except managing the Owner.',
  admin:         'Manage screens, schedules, library, and users.',
  manager:       'Push content and command screens in their scope.',
  user:          'Push content. Cannot manage users or settings.',
  viewer:        'Read-only access to screens and library.',
  brand_partner: 'Upload to their own brand. Uploads need approval.',
};

const Users = () => {
  const { user: actor } = useAuth();
  const [state, setState] = useState({ loading: true, users: [], roles: [] });
  const [draft, setDraft] = useState({ email: '', displayName: '', role: 'user' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true }));
    return apiGet('/api/users').then((data) => {
      setState({ loading: false, users: data.users || [], roles: data.roles || [] });
    });
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const canEditTarget = useCallback((target) => {
    if (!actor) return false;
    if (target.role === 'owner') return actor.id === target.id;
    if (actor.role === 'owner') return true;
    if (actor.role === 'super_admin') return target.role !== 'owner';
    if (actor.role === 'admin') return ['admin', 'manager', 'user', 'viewer', 'brand_partner'].includes(target.role);
    return false;
  }, [actor]);

  const onInvite = useCallback(async (e) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    const { ok, status, data } = await apiPost('/api/users', draft);
    setBusy(false);
    if (ok) {
      setDraft({ email: '', displayName: '', role: 'user' });
      reload();
      window.showToast && window.showToast(`Invited ${data.user.displayName}`);
      return;
    }
    const messages = {
      missing_fields: 'Email and name are required.',
      bad_role: 'Pick a valid role.',
      domain_blocked: 'That email isn\'t in an allowed Google Workspace domain.',
      role_above_actor: 'You can\'t assign a role above your own.',
      cannot_invite_owner: 'Owner is singular and can\'t be invited.',
      already_exists: 'Someone with that email is already in the workspace.',
      forbidden: 'You don\'t have permission to invite users.',
    };
    setError(messages[data.error] || `Invite failed (${data.error || status}).`);
  }, [draft, reload]);

  const onUpdate = useCallback(async (target, patch) => {
    const { ok, data } = await fetch(`/api/users/${target.id}`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    }).then(async (r) => ({ ok: r.ok, data: await r.json().catch(() => ({})) }));
    if (ok) {
      reload();
      window.showToast && window.showToast(`Updated ${target.displayName}`);
    } else {
      window.showToast && window.showToast(`Couldn't update: ${data.error || 'unknown'}`, 'err');
    }
  }, [reload]);

  const onRemove = useCallback(async (target) => {
    if (!window.confirm(`Remove ${target.displayName}? They'll lose access immediately.`)) return;
    const r = await fetch(`/api/users/${target.id}`, {
      method: 'DELETE', credentials: 'include',
    });
    if (r.ok) {
      reload();
      window.showToast && window.showToast(`Removed ${target.displayName}`);
    } else {
      const data = await r.json().catch(() => ({}));
      window.showToast && window.showToast(`Couldn't remove: ${data.error || 'unknown'}`, 'err');
    }
  }, [reload]);

  const assignableRoles = useMemo(() => {
    if (!actor) return [];
    if (actor.role === 'owner') return state.roles.filter((r) => r !== 'owner');
    if (actor.role === 'super_admin') return state.roles.filter((r) => r !== 'owner');
    if (actor.role === 'admin') return ['admin', 'manager', 'user', 'viewer', 'brand_partner'];
    return [];
  }, [actor, state.roles]);

  const vp = useViewport();
  const compact = vp.isCompact;
  return (
    <AppShell current="settings">
      <PageHeader
        title="Users & permissions"
        subtitle="Invite teammates and set what they can do."
        crumbs={[
          { label: 'Settings', href: '/settings' },
          { label: 'Users' },
        ]}
      />
      <div style={{
        flex: 1, overflow: 'auto',
        padding: compact ? '16px 14px 32px' : '20px 24px 40px',
      }}>
        {can(actor, 'users.invite') && (
          <Card padding={compact ? 14 : 18} style={{ marginBottom: 16 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-1)', marginBottom: 10 }}>
              Invite a teammate
            </div>
            <form
              onSubmit={onInvite}
              style={{
                display: 'grid',
                // Mobile stacks every input; tablet shows email + name
                // on one row, role + button on next; laptop+ keeps the
                // current 4-column layout.
                gridTemplateColumns: compact ? '1fr' : '1.4fr 1fr 1fr auto',
                gap: 8,
              }}>
              <Input
                placeholder="email@smartechworld.com"
                value={draft.email}
                onChange={(e) => setDraft({ ...draft, email: e.target.value })}
                type="email"
              />
              <Input
                placeholder="Display name"
                value={draft.displayName}
                onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
              />
              <select
                value={draft.role}
                onChange={(e) => setDraft({ ...draft, role: e.target.value })}
                style={{
                  height: 30, padding: '0 10px', borderRadius: 4, fontSize: 12,
                  background: 'var(--ink-10)', color: 'var(--ink-1)',
                  border: 'var(--border-strong)',
                }}
              >
                {assignableRoles.map((r) => (
                  <option key={r} value={r}>{ROLE_LABELS[r] || r}</option>
                ))}
              </select>
              <Button type="submit" variant="primary" size="sm" disabled={busy}>
                {busy ? 'Inviting…' : 'Invite'}
              </Button>
            </form>
            {error && (
              <div style={{ marginTop: 10, fontSize: 12, color: 'var(--err)' }}>{error}</div>
            )}
            <div style={{ marginTop: 10, fontSize: 11, color: 'var(--ink-4)', lineHeight: 1.5 }}>
              They'll appear here right away, but only get access after their first Google sign-in with this email.
            </div>
          </Card>
        )}

        <Card padding={0}>
          <div style={{ padding: '12px 16px', borderBottom: 'var(--border-faint)', fontSize: 12, fontWeight: 600, color: 'var(--ink-1)' }}>
            Workspace ({state.users.length})
          </div>
          {state.loading ? (
            <div style={{ padding: 20, fontSize: 12, color: 'var(--ink-4)' }}>Loading…</div>
          ) : state.users.length === 0 ? (
            <div style={{ padding: 20, fontSize: 12, color: 'var(--ink-4)' }}>No users yet.</div>
          ) : (
            <div>
              {state.users.map((u) => {
                const editable = canEditTarget(u);
                const isSelf = actor && u.id === actor.id;
                const initials = (u.displayName || u.email || '?')
                  .split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase();
                return (
                  <div key={u.id} style={{
                    display: 'grid',
                    // Mobile: avatar + identity on row 1, role / pin /
                    // status / actions span row 2 via flex. Laptop+:
                    // 6 columns including the new PIN cell.
                    gridTemplateColumns: compact
                      ? '36px 1fr'
                      : '36px 1.6fr 1.2fr 80px 0.6fr auto',
                    gap: 10, alignItems: 'center', padding: '12px 16px',
                    borderBottom: 'var(--border-faint)',
                    opacity: u.status === 'disabled' ? 0.55 : 1,
                  }}>
                    <div style={{
                      width: 30, height: 30, borderRadius: '50%',
                      background: 'var(--ink-1)', color: 'var(--ink-10)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 11, fontWeight: 500, overflow: 'hidden',
                    }}>
                      {u.pictureUrl ? (
                        <img src={u.pictureUrl} alt="" width={30} height={30} style={{ display: 'block' }} />
                      ) : initials}
                    </div>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>
                        {u.displayName}
                        {isSelf && <span style={{ fontSize: 10, color: 'var(--ink-4)', marginLeft: 6 }}>(you)</span>}
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {u.email}
                      </div>
                    </div>
                    <div>
                      {editable && u.role !== 'owner' ? (
                        <select
                          value={u.role}
                          onChange={(e) => onUpdate(u, { role: e.target.value })}
                          style={{
                            height: 26, padding: '0 8px', borderRadius: 4, fontSize: 12,
                            background: 'var(--ink-10)', color: 'var(--ink-1)',
                            border: 'var(--border-strong)',
                          }}
                        >
                          {assignableRoles.map((r) => (
                            <option key={r} value={r}>{ROLE_LABELS[r] || r}</option>
                          ))}
                        </select>
                      ) : (
                        <span style={{ fontSize: 12, color: 'var(--ink-2)' }}>{ROLE_LABELS[u.role] || u.role}</span>
                      )}
                      <div style={{ fontSize: 10, color: 'var(--ink-4)', marginTop: 2 }}>
                        {ROLE_BLURBS[u.role]}
                      </div>
                    </div>
                    <PinCell
                      user={u}
                      editable={editable}
                      onSave={(next) => onUpdate(u, { pin: next })}
                    />
                    <div style={{ fontSize: 11, color: u.status === 'active' ? 'var(--ok)' : 'var(--ink-4)' }}>
                      {u.status === 'active' ? 'Active' : 'Disabled'}
                    </div>
                    <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                      {editable && u.role !== 'owner' && !isSelf && (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => onUpdate(u, { status: u.status === 'active' ? 'disabled' : 'active' })}
                        >
                          {u.status === 'active' ? 'Disable' : 'Re-enable'}
                        </Button>
                      )}
                      {can(actor, 'users.delete') && u.role !== 'owner' && !isSelf && (
                        <Button variant="ghost" size="sm" onClick={() => onRemove(u)}>
                          Remove
                        </Button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Card>
      </div>
    </AppShell>
  );
};

window.Users = Users;
