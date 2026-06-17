/* eslint-disable */
// Settings — users, brands, Drive sync, notifications.

const SettingsRow = ({ label, sub, value, children }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '14px 20px', borderBottom: 'var(--border-faint)' }}>
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>{sub}</div>}
    </div>
    {value && <div style={{ fontSize: 12, color: 'var(--ink-3)' }}>{value}</div>}
    {children}
  </div>
);

// ─────────────────────────────────────────────────────────────
// Users tab — list, PIN column, inline create form.
// PIN is what the user types into the on-tablet staff overlay.
// 4 digits. Shown masked by default; click eye to reveal.
// ─────────────────────────────────────────────────────────────
const PinDisplay = ({ pin }) => {
  const [show, setShow] = React.useState(false);
  if (!pin) return <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>—</span>;
  return (
    <button onClick={(e) => { e.stopPropagation(); setShow(s => !s); }} style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-2)',
      padding: '2px 8px', background: 'var(--ink-9)', borderRadius: 4,
      letterSpacing: 1, cursor: 'pointer',
    }} className="tnum" title={show ? 'Hide PIN' : 'Show PIN'}>
      {show ? pin : '••••'}
    </button>
  );
};

const RoleChip = ({ role }) => {
  const tone = role === 'SUPER_ADMIN' ? 'info'
    : role === 'ADMIN' ? 'neutral'
    : role === 'USER' ? 'ok'
    : 'outline';
  return <Chip tone={tone}>{ROLE_LABEL[role] || role}</Chip>;
};

const CreateUserForm = ({ onSave, onCancel }) => {
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [role, setRole] = React.useState('USER');
  const [pin, setPin] = React.useState('');
  const valid = name.trim() && /^\d{4}$/.test(pin);
  const generatePin = () => setPin(String(Math.floor(1000 + Math.random() * 9000)));
  return (
    <div style={{ border: 'var(--border)', borderRadius: 10, padding: 16, background: 'var(--ink-9)', marginBottom: 12 }}>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 10 }}>New user</div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>Name</label>
          <Input placeholder="Full name" value={name} onChange={(e) => setName(e.target.value)} size="sm" />
        </div>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>Email</label>
          <Input placeholder="name@smartech.group" value={email} onChange={(e) => setEmail(e.target.value)} size="sm" />
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>Role</label>
          <div style={{ display: 'flex', alignItems: 'center', height: 28, padding: '0 10px', border: 'var(--border-strong)', borderRadius: 2, fontSize: 12, color: 'var(--ink-2)', background: 'var(--ink-10)' }}>
            <select value={role} onChange={(e) => setRole(e.target.value)} style={{ flex: 1, border: 'none', outline: 'none', background: 'transparent', font: 'inherit', color: 'inherit', cursor: 'pointer' }}>
              <option value="SUPER_ADMIN">Super admin — full access incl. device config</option>
              <option value="ADMIN">Admin — manage screens + content</option>
              <option value="BRAND_MANAGER">Brand manager — scoped to brands</option>
              <option value="USER">In-store user — content swap only</option>
              <option value="VIEWER">Viewer — read-only</option>
            </select>
          </div>
        </div>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>PIN <span style={{ color: 'var(--ink-4)', fontWeight: 400 }}>· 4 digits</span></label>
          <div style={{ display: 'flex', gap: 6 }}>
            <Input placeholder="••••" value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))} size="sm" style={{ flex: 1, fontFamily: 'var(--font-mono)', letterSpacing: 2 }} />
            <Button variant="ghost" size="sm" onClick={generatePin} title="Generate random">↻</Button>
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', marginTop: 14 }}>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', flex: 1 }}>The PIN is what the user types on a tablet to swap content.</div>
        <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
        <Button variant="primary" size="sm" disabled={!valid} onClick={() => onSave({ name: name.trim(), email: email.trim(), role, pin })}>Create user</Button>
      </div>
    </div>
  );
};

// UsersTab removed in v0.1.6. The Settings → Users tab kept a
// purely-local MOCK_USERS list that never talked to the backend,
// while the sidebar's Users page (Users.jsx) was the real one. Having
// two diverging views of the same data was confusing — sidebar Users
// is now the single source of truth.
const _RemovedUsersTab = () => {
  const [users, setUsers] = React.useState(MOCK_USERS);
  const [creating, setCreating] = React.useState(false);

  const addUser = (u) => {
    const initials = (u.name.split(/\s+/).map(p => p[0]).slice(0, 2).join('') || '??').toUpperCase();
    setUsers([...users, { ...u, id: 'u-' + Date.now().toString(36), initials, status: 'active', scope: u.role === 'SUPER_ADMIN' ? 'All brands' : '—' }]);
    setCreating(false);
  };
  const removeUser = (id) => setUsers(users.filter(u => u.id !== id));

  const active = users.filter(u => u.status === 'active').length;
  const pending = users.filter(u => u.status === 'pending').length;

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Users</div>
          <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>{active} member{active !== 1 ? 's' : ''} · {pending} invite{pending !== 1 ? 's' : ''} pending</div>
        </div>
        {!creating && <Button variant="primary" size="sm" icon={<Icon.plus size={12} />} onClick={() => setCreating(true)}>New user</Button>}
      </div>

      {creating && <CreateUserForm onSave={addUser} onCancel={() => setCreating(false)} />}

      <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '28px 1.4fr 1.5fr 70px 1fr 24px', gap: 12, padding: '10px 16px', borderBottom: 'var(--border)', fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5 }}>
          <span />
          <span>Name</span>
          <span>Email</span>
          <span>PIN</span>
          <span>Role</span>
          <span />
        </div>
        {users.map((u, i) => (
          <div key={u.id} style={{ display: 'grid', gridTemplateColumns: '28px 1.4fr 1.5fr 70px 1fr 24px', gap: 12, padding: '12px 16px', borderBottom: i < users.length - 1 ? 'var(--border-faint)' : 'none', alignItems: 'center' }}>
            <div style={{ width: 28, height: 28, borderRadius: '50%', background: u.status === 'pending' ? 'var(--ink-8)' : 'var(--ink-1)', color: u.status === 'pending' ? 'var(--ink-4)' : 'var(--ink-10)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 500 }}>{u.initials}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{u.name}</div>
              <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>{u.scope}</div>
            </div>
            <div style={{ fontSize: 12, color: 'var(--ink-3)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{u.email}</div>
            <PinDisplay pin={u.pin} />
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <RoleChip role={u.role} />
              {u.status === 'pending' && <Chip tone="warn">Pending</Chip>}
            </div>
            <Button variant="ghost" size="sm" icon={<Icon.more size={14} />} onClick={() => removeUser(u.id)} title="Remove" />
          </div>
        ))}
      </div>
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// Locations tab — taxonomy that drives the cascading dropdowns on
// the tablet's Device admin screen and on every "Add screen" flow.
// v0.1.38: stores are editable from here. Region / city / concept /
// floor / table are still hardcoded in LOCATION_TAXONOMY (a region
// change is rare and touches a lot of downstream picker logic);
// stores are the row that gets added most often for pop-ups, partner
// activations, dev fixtures, etc.
// ─────────────────────────────────────────────────────────────
const TaxonomyList = ({ title, items, render }) => (
  <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)' }}>
    <div style={{ padding: '12px 16px', borderBottom: 'var(--border-faint)', display: 'flex', alignItems: 'center' }}>
      <div style={{ flex: 1, fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{title}</div>
      <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{items.length}</span>
    </div>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, padding: 16 }}>
      {items.map((item, i) => render(item, i))}
    </div>
  </div>
);

// Built-in store ids the server pre-loads on both clients. The form
// rejects these as duplicates server-side, but we hide the delete
// button on these rows too so the UI doesn't suggest the action is
// possible.
const BUILTIN_STORE_IDS = new Set([
  'tmrw-times-square', 'smartech-selfridges',
  'smartech-kadewe', 'tmrw-rinascente',
  'events', 'test',
]);

const slugify = (s) => (s || '')
  .toLowerCase()
  .replace(/[^a-z0-9]+/g, '-')
  .replace(/^-+|-+$/g, '')
  .slice(0, 60);

const AddStoreForm = ({ cities, onAdded, onCancel }) => {
  const [name, setName] = React.useState('');
  const [id, setId] = React.useState('');
  const [idTouched, setIdTouched] = React.useState(false);
  const [address, setAddress] = React.useState('');
  const [city, setCity] = React.useState(cities[0]?.code || '');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  // Auto-suggest a slug from the name until the user edits the id
  // field directly. Same pattern Content Library uses for video
  // titles → ids.
  const autoId = !idTouched ? slugify(name) : id;
  const valid = name.trim() && /^[a-z0-9][a-z0-9-]{1,62}$/.test(autoId) && city;
  const submit = async () => {
    if (!valid || busy) return;
    setBusy(true); setErr(null);
    try {
      const store = await addStore({ id: autoId, name: name.trim(), address: address.trim(), city });
      showToast(`Added '${store.name}'`, 'ok');
      onAdded(store);
    } catch (e) {
      setErr(e.message || 'add failed');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div style={{ border: 'var(--border)', borderRadius: 10, padding: 16, background: 'var(--ink-9)', marginBottom: 12 }}>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 10 }}>New store</div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>Name</label>
          <Input placeholder="e.g. Smartech · Battersea" value={name} onChange={(e) => setName(e.target.value)} size="sm" />
        </div>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>ID <span style={{ color: 'var(--ink-4)', fontWeight: 400 }}>· kebab-case, unique</span></label>
          <Input
            placeholder={slugify(name) || 'store-slug'}
            value={autoId}
            onChange={(e) => { setIdTouched(true); setId(e.target.value); }}
            size="sm"
            style={{ fontFamily: 'var(--font-mono)' }}
          />
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 10, marginBottom: 10 }}>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>Address</label>
          <Input placeholder="Street, postcode" value={address} onChange={(e) => setAddress(e.target.value)} size="sm" />
        </div>
        <div>
          <label style={{ display: 'block', fontSize: 11, fontWeight: 500, color: 'var(--ink-3)', marginBottom: 4 }}>City</label>
          <div style={{ display: 'flex', alignItems: 'center', height: 28, padding: '0 10px', border: 'var(--border-strong)', borderRadius: 2, fontSize: 12, color: 'var(--ink-2)', background: 'var(--ink-10)' }}>
            <select value={city} onChange={(e) => setCity(e.target.value)} style={{ flex: 1, border: 'none', outline: 'none', background: 'transparent', font: 'inherit', color: 'inherit', cursor: 'pointer' }}>
              {cities.map(c => <option key={c.code} value={c.code}>{c.code} · {c.region}</option>)}
            </select>
          </div>
        </div>
      </div>
      {err && <div style={{ fontSize: 11, color: 'var(--err-1, #A63824)', marginBottom: 8 }}>{err}</div>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Button size="sm" disabled={!valid || busy} onClick={submit}>{busy ? 'Saving…' : 'Add store'}</Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>Cancel</Button>
        <div style={{ flex: 1 }} />
        <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>
          Stores propagate to tablets on next launch (or via Refresh now).
        </div>
      </div>
    </div>
  );
};

const LocationsTab = () => {
  const t = LOCATION_TAXONOMY;
  // Bump a counter on `stores-refresh` so React re-renders the list
  // when a mutation lands. LOCATION_TAXONOMY.stores is mutated in
  // place by addStore/deleteStore; this is the cheapest way to
  // notice without lifting the array into React state on every page.
  const [, setTick] = React.useState(0);
  React.useEffect(() => {
    const bump = () => setTick(x => x + 1);
    window.addEventListener('stores-refresh', bump);
    return () => window.removeEventListener('stores-refresh', bump);
  }, []);
  const [adding, setAdding] = React.useState(false);
  const removeStore = async (id, name) => {
    if (!confirm(`Remove store '${name}'?\n\nAny screen still linked to it will fall back to its raw id until reassigned.`)) return;
    try {
      await deleteStore(id);
      showToast(`Removed '${name}'`, 'ok');
    } catch (e) {
      showToast(`Couldn't remove: ${e.message}`, 'err');
    }
  };
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Locations</div>
          <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>
            Pickable values for the cascading location dropdowns. Used by the on-tablet device admin and the add-screen flow.
          </div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
        <TaxonomyList title="Region" items={t.regions} render={(r) => <Chip key={r}>{r}</Chip>} />
        <TaxonomyList title="City" items={t.cities} render={(c) => (
          <span key={c.code} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Chip>{c.code}</Chip>
            <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>· {c.region}</span>
          </span>
        )} />
      </div>

      <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)', marginBottom: 12 }}>
        <div style={{ padding: '12px 16px', borderBottom: 'var(--border-faint)', display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ flex: 1, fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Stores</div>
          <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{t.stores.length}</span>
          {!adding && (
            <Button size="sm" onClick={() => setAdding(true)}>+ Add store</Button>
          )}
        </div>
        {adding && (
          <div style={{ padding: 12, borderBottom: 'var(--border-faint)' }}>
            <AddStoreForm
              cities={t.cities}
              onAdded={() => setAdding(false)}
              onCancel={() => setAdding(false)}
            />
          </div>
        )}
        {t.stores.map((s, i) => {
          const builtIn = BUILTIN_STORE_IDS.has(s.id);
          return (
            <div key={s.id} style={{ padding: '12px 16px', borderBottom: i < t.stores.length - 1 ? 'var(--border-faint)' : 'none', display: 'flex', alignItems: 'center', gap: 12 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{s.name}</div>
                <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>
                  <span style={{ fontFamily: 'var(--font-mono)' }}>{s.id}</span>
                  {s.address ? <> · {s.address}</> : null}
                </div>
              </div>
              <Chip tone="outline">{s.city}</Chip>
              {builtIn
                ? <Chip tone="neutral">built-in</Chip>
                : (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => removeStore(s.id, s.name)}
                    title="Delete this custom store"
                  >
                    Remove
                  </Button>
                )
              }
            </div>
          );
        })}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 12 }}>
        <TaxonomyList title="Concept" items={t.concepts} render={(c) => <Chip key={c}>{c}</Chip>} />
        <TaxonomyList title="Floor" items={t.floors} render={(f) => <Chip key={f}>{f}</Chip>} />
        <TaxonomyList title="Table" items={t.tables} render={(t2) => <Chip key={t2}>{t2}</Chip>} />
      </div>

      <div style={{ border: 'var(--border)', borderRadius: 10, padding: 16, background: 'var(--ink-9)', display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        <span style={{ color: 'var(--ink-4)', marginTop: 1 }}><Icon.warning size={14} /></span>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 4 }}>Screen Code is free text</div>
          <div style={{ fontSize: 12, color: 'var(--ink-3)', lineHeight: 1.5 }}>
            Every level above is a fixed dropdown. Screen Code (e.g. <code style={{ fontFamily: 'var(--font-mono)' }}>GF.A.1</code> or <code style={{ fontFamily: 'var(--font-mono)' }}>A1</code>) is the only field you type by hand on the tablet.
          </div>
        </div>
      </div>
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// Splashes tab — per-city brand mapping (NYC/ROM → tm:rw, LDN/BER →
// Smartech) + concept overrides. Reads /api/splashes for the
// available splash files; lets the user reassign which brand each
// city uses. Concept splashes use the same naming convention as the
// Drive folder (`Splash - 7EVN`) and are read-only here — drop a new
// folder on Drive + restart the server to add one.
// ─────────────────────────────────────────────────────────────
const SplashesTab = () => {
  const [data, setData] = React.useState({ brands: [], concepts: [], cityBrand: {} });
  const [loading, setLoading] = React.useState(true);
  const refresh = React.useCallback(async () => {
    try {
      const res = await fetch('/api/splashes', { cache: 'no-store' });
      const body = await res.json();
      setData(body);
    } catch (_) { /* keep last */ }
    setLoading(false);
  }, []);
  React.useEffect(() => { refresh(); }, [refresh]);

  const setCityBrand = async (city, brand) => {
    try {
      const res = await fetch('/api/splashes/mapping', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ city, brand: brand || null }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      showToast(`${city} → ${brand || 'auto'}`, 'ok');
      refresh();
    } catch (e) {
      showToast(`Update failed: ${e.message}`, 'err');
    }
  };

  const previewVideo = (m) => ({
    id: 'splash-' + m.name,
    title: `${m.kind === 'brand' ? 'Brand' : 'Concept'} splash · ${m.name}`,
    brand: m.name,
    mediaUrl: m.url,
    sizeMb: m.sizeMb,
    filename: m.filename,
  });
  const [preview, setPreview] = React.useState(null);

  const cities = LOCATION_TAXONOMY.cities;

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Splashes</div>
          <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>
            Each screen plays a splash between videos. Brand splashes are picked by city; concept splashes (Playhouse, 7EVN, etc.) override per concept.
          </div>
        </div>
      </div>

      {loading && <div style={{ fontSize: 12, color: 'var(--ink-4)' }}>Loading…</div>}

      {/* Brand splashes — one card each */}
      {!loading && (
        <>
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>Brand splashes</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10, marginBottom: 24 }}>
            {data.brands.length === 0 && (
              <div style={{ gridColumn: '1 / -1', padding: 16, fontSize: 12, color: 'var(--ink-4)', border: 'var(--border)', borderRadius: 8 }}>
                No brand splash folders found on Drive. Add a folder named <code style={{ fontFamily: 'var(--font-mono)' }}>Splash - tmrw</code> or <code style={{ fontFamily: 'var(--font-mono)' }}>Splash - Smartech</code> with at least one MP4.
              </div>
            )}
            {data.brands.map((m) => (
              <div key={m.name} style={{ border: 'var(--border)', borderRadius: 10, padding: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
                <BrandMark brand={m.name === 'tmrw' ? 'DVX' : m.name === 'smartech' ? 'SONOS' : m.name} size={36} radius={8} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{m.name === 'tmrw' ? 'tm:rw' : m.name}</div>
                  <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {m.filename} · {m.sizeMb} MB
                  </div>
                </div>
                <Button variant="ghost" size="sm" icon={<Icon.play size={12} />} onClick={() => setPreview(previewVideo(m))}>Preview</Button>
              </div>
            ))}
          </div>

          {/* City → brand mapping */}
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>City → brand splash</div>
          <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)', marginBottom: 24 }}>
            {cities.map((c, i) => {
              const current = data.cityBrand[c.code] || '';
              return (
                <div key={c.code} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px', borderBottom: i < cities.length - 1 ? 'var(--border-faint)' : 'none' }}>
                  <Chip>{c.code}</Chip>
                  <span style={{ fontSize: 12, color: 'var(--ink-4)', flex: 1 }}>{c.region}</span>
                  <select
                    value={current}
                    onChange={(e) => setCityBrand(c.code, e.target.value)}
                    style={{
                      height: 28, padding: '0 10px', border: 'var(--border-strong)', borderRadius: 2,
                      fontSize: 12, fontFamily: 'inherit', color: 'var(--ink-1)', background: 'var(--ink-10)',
                      cursor: 'pointer', minWidth: 160,
                    }}>
                    <option value="">(no splash)</option>
                    {data.brands.map(b => <option key={b.name} value={b.name}>{b.name === 'tmrw' ? 'tm:rw' : b.name}</option>)}
                  </select>
                </div>
              );
            })}
          </div>

          {/* Concept overrides */}
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 }}>Concept overrides</div>
          <div style={{ fontSize: 12, color: 'var(--ink-4)', marginBottom: 10 }}>
            When a screen's concept matches one of these, the concept splash plays instead of the city's brand splash.
          </div>
          <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)', marginBottom: 12 }}>
            {LOCATION_TAXONOMY.concepts.map((concept, i) => {
              const m = data.concepts.find(x => x.name === concept);
              return (
                <div key={concept} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', borderBottom: i < LOCATION_TAXONOMY.concepts.length - 1 ? 'var(--border-faint)' : 'none' }}>
                  <span style={{ fontSize: 13, color: 'var(--ink-1)', flex: 1 }}>{concept}</span>
                  {m ? (
                    <>
                      <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{m.filename} · {m.sizeMb} MB</span>
                      <Button variant="ghost" size="sm" icon={<Icon.play size={12} />} onClick={() => setPreview(previewVideo(m))}>Preview</Button>
                    </>
                  ) : (
                    <Chip tone="outline">No splash · falls back to city brand</Chip>
                  )}
                </div>
              );
            })}
          </div>

          <div style={{ fontSize: 11, color: 'var(--ink-4)', marginBottom: 12 }}>
            To add a concept splash: drop an MP4 into <code style={{ fontFamily: 'var(--font-mono)' }}>G:\Shared drives\Smartech\Screens\Splash - {'<concept>'}\</code> and restart serve.py.
          </div>
        </>
      )}

      {preview && (
        <div onClick={() => setPreview(null)} style={{
          position: 'absolute', inset: 0, zIndex: 30,
          background: 'rgba(9,9,11,0.72)', backdropFilter: 'blur(4px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
        }}>
          <div onClick={(e) => e.stopPropagation()} className="scr-modal-panel" style={{
            width: 'min(960px, 90%)', background: 'var(--ink-10)', borderRadius: 12, overflow: 'hidden',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', padding: '12px 16px', borderBottom: 'var(--border)' }}>
              <div style={{ flex: 1, fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{preview.title}</div>
              <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={() => setPreview(null)} />
            </div>
            <video src={preview.mediaUrl} controls autoPlay style={{ width: '100%', maxHeight: '70vh', background: '#000', display: 'block' }} />
          </div>
        </div>
      )}
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// Drive Sync tab — re-runs the local scan-videos.py against the
// mounted Drive folder. The CMS server doesn't keep its own copy
// of any video file; everything streams in place via the /media/
// endpoint, which reads from G:\Shared drives\Smartech\Screens\
// Brand Content directly.
// ─────────────────────────────────────────────────────────────
// Render the live progress sub-line for the Drive Sync card. Falls back
// gracefully when the scanner hasn't reported a total yet.
const renderProgress = (info) => {
  if (!info) return 'Starting…';
  const c = info.progressCurrent;
  const t = info.progressTotal;
  const label = info.progressLabel;
  if (c == null || t == null) return 'Starting…';
  const pct = t > 0 ? Math.round((c / t) * 100) : 0;
  return `Scanning ${c} / ${t} folders · ${pct}%${label ? ` · ${label}` : ''}`;
};

const formatRelative = (epochSec) => {
  if (!epochSec) return 'never';
  const ageSec = Math.floor((Date.now() / 1000) - epochSec);
  if (ageSec < 5)    return 'just now';
  if (ageSec < 60)   return `${ageSec}s ago`;
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)} min ago`;
  if (ageSec < 86400) return `${Math.floor(ageSec / 3600)} hr ago`;
  return `${Math.floor(ageSec / 86400)} day${Math.floor(ageSec / 86400) === 1 ? '' : 's'} ago`;
};

const DriveSyncTab = () => {
  const [info, setInfo] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  // v0.1.45: separate "refreshing directory view" state from the
  // "syncing Drive" state so the buttons reflect the right action.
  // Pulling library.json is fast (<1 s); a full sync can take minutes.
  const [refreshingDir, setRefreshingDir] = React.useState(false);
  const refresh = React.useCallback(async () => {
    try {
      const r = await fetch('/api/library/info', { cache: 'no-store' });
      setInfo(await r.json());
    } catch (_) { /* keep last */ }
  }, []);
  // Poll every 2s while a sync is running, every 30s otherwise.
  React.useEffect(() => {
    refresh();
    const id = setInterval(refresh, busy || info?.running ? 2000 : 30000);
    return () => clearInterval(id);
  }, [refresh, busy, info?.running]);

  const sync = async () => {
    if (busy || info?.running) return;
    setBusy(true);
    try {
      const r = await fetch('/api/library/refresh', { method: 'POST' });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      showToast('Sync started — scanning Drive folder', 'info');
    } catch (e) {
      showToast(`Sync failed: ${e.message}`, 'err');
    } finally {
      setTimeout(() => setBusy(false), 1500);
      refresh();
    }
  };

  // v0.1.45: pull the latest library.json the server has on disk and
  // refresh every consumer in the CMS. Does NOT trigger a fresh Drive
  // scan — that's what Sync now is for. Use this when:
  //   • someone else triggered a sync and you want to see the result
  //     without paying for another scan
  //   • the in-memory library cache feels stale (e.g. after a manual
  //     /data/library.json edit, or right after a sync completes)
  //   • debugging — confirm the server-side tree without round-tripping
  //     through the slow Drive walk
  const refreshDirectory = async () => {
    if (refreshingDir) return;
    setRefreshingDir(true);
    try {
      // Bypass any browser/CDN cache and bypass useLibrary's module-level
      // cache by firing 'library-refresh' afterwards.
      const r = await fetch('/api/library?_ts=' + Date.now(), {
        cache: 'no-store',
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const data = await r.json();
      const count = Array.isArray(data?.videos) ? data.videos.length : 0;
      // Re-pull /api/library/info too so the "Last sync" row updates
      // in case the in-tab state was behind the poll.
      await refresh();
      // Tell ContentLibrary + anything else listening to drop its cache.
      window.dispatchEvent(new CustomEvent('library-refresh'));
      showToast(`Directory refreshed — ${count} video${count === 1 ? '' : 's'}`, 'ok');
    } catch (e) {
      showToast(`Refresh failed: ${e.message}`, 'err');
    } finally {
      setRefreshingDir(false);
    }
  };

  const lastAt = info?.lastRunAt || info?.fileMtime;
  const running = info?.running;
  const success = info?.lastSuccess;
  const count = info?.lastCount;
  // Mode determines copy + chips. drive-api = cloud deploy reading Drive
  // via service account; filesystem = local dev with a Drive-for-Desktop
  // mount. Default to filesystem if the field is missing (older server).
  const cloud = info?.mode === 'drive-api';
  const sourceLabel = cloud
    ? (info?.driveFolderName
        ? `${info.driveFolderName} · folder ID ${info.driveFolderId}`
        : `Drive folder · ${info?.driveFolderId || 'unknown'}`)
    : (info?.folder || 'G:\\Shared drives\\Smartech\\Screens\\Brand Content');

  return (
    <>
      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Drive sync</div>
        <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>
          {cloud
            ? 'Re-scans the brand content folder on Google Drive via the Drive API. Auto-runs every 24 hours.'
            : "Re-scans the brand content folder on this machine's Google Drive sync. Auto-runs every 24 hours."}
        </div>
      </div>

      <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)', marginBottom: 16 }}>
        <SettingsRow
          label={<span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}><Icon.drive size={14} /> Source folder</span>}
          sub={sourceLabel}>
          <Chip tone={cloud ? 'info' : 'ok'}>{cloud ? 'Drive API' : 'Mounted'}</Chip>
        </SettingsRow>
        <SettingsRow
          label="Storage"
          sub={cloud
            ? 'Videos stream from Drive on demand and cache on each tablet — the CMS server keeps no copies of its own.'
            : 'Videos stream in place from Drive — the CMS server keeps no copies of its own.'}>
          <Chip tone="outline">Zero-copy</Chip>
        </SettingsRow>
        <SettingsRow
          label="Last sync"
          sub={
            running
              ? renderProgress(info)
              : `${formatRelative(lastAt)}${count != null ? ` · ${count} videos` : ''}${success === false ? ' · failed' : ''}`
          }>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            {/* v0.1.45: pulls the current library.json from the server
                without triggering a fresh Drive walk. Fast (<1 s) vs
                Sync now (minutes). */}
            <Button
              variant="ghost" size="sm"
              icon={<Icon.refresh size={12} />}
              disabled={refreshingDir}
              onClick={refreshDirectory}
              title="Re-pull the directory tree the server already has, without re-scanning Drive">
              {refreshingDir ? 'Refreshing…' : 'Refresh directory'}
            </Button>
            <Button
              variant="secondary" size="sm"
              icon={<Icon.sync size={12} />}
              disabled={busy || running}
              onClick={sync}>
              {running ? 'Syncing…' : 'Sync now'}
            </Button>
          </div>
        </SettingsRow>

        {running && info?.progressTotal && (
          <div style={{ padding: '0 20px 14px' }}>
            <div style={{ height: 4, borderRadius: 2, background: 'var(--ink-9)', overflow: 'hidden' }}>
              <div style={{
                width: `${Math.min(100, (info.progressCurrent / info.progressTotal) * 100)}%`,
                height: '100%',
                background: 'var(--ink-1)',
                transition: 'width .2s',
              }} />
            </div>
          </div>
        )}
        <SettingsRow
          label="Auto-sync"
          sub={cloud
            ? 'Every 24 hours while the Cloud Run instance is up. Click Sync now to trigger one on demand.'
            : 'Every 24 hours while the server is running. Restart serve.py to trigger one immediately.'} />
      </div>

      {info?.lastError && (
        <div style={{
          padding: 14, border: 'var(--border)', borderRadius: 10,
          background: 'var(--err-bg)', color: 'var(--err)',
          fontSize: 12, fontFamily: 'var(--font-mono)',
          whiteSpace: 'pre-wrap', wordBreak: 'break-all',
        }}>
          Last sync error:{'\n'}{info.lastError}
        </div>
      )}
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// Integrations tab — owner-only. Surfaces the Brand Asset Manager
// API key for view + edit. Server enforces the same role gate on
// every request, so this UI is a convenience; a non-owner who
// manually fetches /api/integrations/brandApiKey gets a 403.
//
// Display rule: the value is masked by default and revealed on
// demand. Mask shows the first 6 + last 4 chars so the owner can
// tell two keys apart at a glance without exposing the whole thing.
// ─────────────────────────────────────────────────────────────
const maskApiKey = (s) => {
  if (!s) return '';
  if (s.length <= 12) return '•'.repeat(s.length);
  return s.slice(0, 6) + '•'.repeat(Math.max(4, s.length - 10)) + s.slice(-4);
};

const fmtUpdatedAt = (epochSec) => {
  if (!epochSec) return 'never';
  try {
    return new Date(epochSec * 1000).toLocaleString();
  } catch (_) {
    return '—';
  }
};

const BrandApiKeyRow = () => {
  const [data, setData] = React.useState(null);   // { value, updatedAt, updatedBy }
  const [loading, setLoading] = React.useState(true);
  const [err, setErr] = React.useState(null);
  const [show, setShow] = React.useState(false);
  const [editing, setEditing] = React.useState(false);
  const [draft, setDraft] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  // v0.1.59: connection-test result. null = not run; otherwise the
  // payload from POST /api/integrations/brandApiKey/test, plus a
  // local `at` timestamp so the operator can see how stale it is.
  const [testResult, setTestResult] = React.useState(null);
  const [testing, setTesting] = React.useState(false);

  const refresh = React.useCallback(async () => {
    try {
      const r = await fetch('/api/integrations/brandApiKey', { cache: 'no-store' });
      if (!r.ok) {
        setErr(`HTTP ${r.status}`);
        setData({ value: '', updatedAt: null, updatedBy: null });
      } else {
        const j = await r.json();
        setData({ value: j.value || '', updatedAt: j.updatedAt, updatedBy: j.updatedBy });
        setErr(null);
      }
    } catch (e) {
      setErr(e.message);
    } finally {
      setLoading(false);
    }
  }, []);
  React.useEffect(() => { refresh(); }, [refresh]);

  const startEdit = () => {
    setDraft(data?.value || '');
    setEditing(true);
    // Any prior test result is about the old value — drop it so we
    // don't leave a stale "Connected" pill hanging next to a new key
    // the operator hasn't tested yet.
    setTestResult(null);
  };
  const cancelEdit = () => {
    setEditing(false);
    setDraft('');
  };
  const save = async () => {
    if (saving) return;
    setSaving(true);
    try {
      const r = await fetch('/api/integrations/brandApiKey', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value: draft.trim() }),
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        throw new Error(j.error || `HTTP ${r.status}`);
      }
      const j = await r.json();
      setData({ value: j.value || '', updatedAt: j.updatedAt, updatedBy: j.updatedBy });
      setEditing(false);
      setDraft('');
      // Result is stale after a save — the operator should re-test.
      setTestResult(null);
      showToast(j.value ? 'Brand API key saved' : 'Brand API key cleared', 'ok');
    } catch (e) {
      showToast(`Save failed: ${e.message}`, 'err');
    } finally {
      setSaving(false);
    }
  };
  const copy = async () => {
    if (!data?.value) return;
    try {
      await navigator.clipboard.writeText(data.value);
      showToast('Copied to clipboard', 'ok');
    } catch (_) {
      showToast('Copy failed', 'err');
    }
  };

  // v0.1.59: connection test. The server calls the tm:rw index /me
  // endpoint with the stored key so the value never reaches the
  // browser. We abort the request after 12 s in case the upstream
  // hangs — the server-side urlopen also has its own 8 s timeout.
  const runTest = async () => {
    if (testing) return;
    setTesting(true);
    setTestResult(null);
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 12000);
    try {
      const r = await fetch('/api/integrations/brandApiKey/test', {
        method: 'POST',
        signal: ctl.signal,
      });
      const j = await r.json().catch(() => ({}));
      setTestResult({ ...j, at: Date.now() });
    } catch (e) {
      setTestResult({
        ok: false,
        status: e.name === 'AbortError' ? 'unreachable' : 'unreachable',
        detail: e.name === 'AbortError' ? 'Timed out after 12 s' : (e.message || 'Network error'),
        at: Date.now(),
      });
    } finally {
      clearTimeout(timer);
      setTesting(false);
    }
  };

  const present = !!data?.value;

  // Map server status → display tone + label.
  const testTone = (() => {
    if (!testResult) return null;
    if (testResult.ok) return 'ok';
    if (testResult.status === 'no_key') return 'warn';
    return 'err';
  })();
  const testLabel = (() => {
    if (!testResult) return null;
    if (testResult.ok) return `Connected${testResult.latencyMs != null ? ` · ${testResult.latencyMs} ms` : ''}`;
    if (testResult.status === 'unauthorized') return 'Key rejected';
    if (testResult.status === 'unreachable')  return 'Unreachable';
    if (testResult.status === 'server_error') return 'Server error';
    if (testResult.status === 'no_key')       return 'No key set';
    return 'Failed';
  })();

  return (
    <div style={{ padding: '16px 20px', borderBottom: 'var(--border-faint)' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>Brand Asset Manager API key</div>
        {present
          ? <Chip tone="ok">Set</Chip>
          : <Chip tone="warn">Not set</Chip>}
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-4)', marginBottom: 10 }}>
        Used by the CMS to read brands, logos, and splash assets from the Brand Asset Manager.
        Last updated {fmtUpdatedAt(data?.updatedAt)}{data?.updatedBy ? ` by ${data.updatedBy}` : ''}.
      </div>

      {loading ? (
        <div style={{ fontSize: 12, color: 'var(--ink-4)' }}>Loading…</div>
      ) : editing ? (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <Input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Paste new key — empty to clear"
            size="sm"
            style={{
              flex: 1, minWidth: 240,
              fontFamily: 'var(--font-mono)', fontSize: 12,
            }}
            autoFocus
          />
          <Button variant="primary" size="sm" disabled={saving} onClick={save}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
          <Button variant="ghost" size="sm" disabled={saving} onClick={cancelEdit}>
            Cancel
          </Button>
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <code style={{
            flex: 1, minWidth: 240,
            padding: '6px 10px',
            background: 'var(--ink-9)',
            border: 'var(--border)',
            borderRadius: 4,
            fontFamily: 'var(--font-mono)', fontSize: 12,
            color: present ? 'var(--ink-1)' : 'var(--ink-4)',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}>
            {present ? (show ? data.value : maskApiKey(data.value)) : '— no key set —'}
          </code>
          {present && (
            <Button variant="ghost" size="sm" onClick={() => setShow(s => !s)}>
              {show ? 'Hide' : 'Reveal'}
            </Button>
          )}
          {present && (
            <Button variant="ghost" size="sm" onClick={copy}>Copy</Button>
          )}
          {present && (
            <Button
              variant="ghost" size="sm"
              disabled={testing}
              onClick={runTest}
              title="Verify the saved key against tm:rw index API">
              {testing ? 'Testing…' : 'Test connection'}
            </Button>
          )}
          <Button variant="secondary" size="sm" onClick={startEdit}>
            {present ? 'Edit' : 'Set key'}
          </Button>
        </div>
      )}

      {/* v0.1.59: test-connection result. Renders below the row when
          present so the status sticks around until the operator
          re-tests or edits the key. */}
      {testResult && !editing && (
        <div style={{
          marginTop: 10, padding: '8px 12px',
          background: testTone === 'ok' ? 'var(--ok-bg)'
                    : testTone === 'warn' ? 'var(--warn-bg)'
                    : 'var(--err-bg)',
          color: testTone === 'ok' ? 'var(--ok)'
               : testTone === 'warn' ? 'var(--warn)'
               : 'var(--err)',
          borderRadius: 6, fontSize: 12,
          display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
        }}>
          <span style={{ fontWeight: 500 }}>● {testLabel}</span>
          {testResult.detail && (
            <span style={{ opacity: 0.85 }}>· {testResult.detail}</span>
          )}
          {testResult.identity && Object.keys(testResult.identity).length > 0 && (
            <span className="tnum" style={{ opacity: 0.85, fontFamily: 'var(--font-mono)' }}>
              · {Object.entries(testResult.identity).map(([k, v]) => `${k}=${v}`).join(', ')}
            </span>
          )}
        </div>
      )}

      {err && (
        <div style={{ fontSize: 11, color: 'var(--err)', marginTop: 8 }}>
          {err === 'owner_only'
            ? 'Owner role required.'
            : err === 'unauthenticated'
              ? 'Sign in again to view this.'
              : `Couldn't load: ${err}`}
        </div>
      )}
    </div>
  );
};

const IntegrationsTab = () => (
  <>
    <div style={{ marginBottom: 16 }}>
      <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Integrations</div>
      <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>
        API keys for third-party services the CMS reads from. Only the Owner can view or edit
        these — non-owners see a 403 even if they hit the endpoints directly.
      </div>
    </div>
    <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)' }}>
      <BrandApiKeyRow />
    </div>
    <div style={{ marginTop: 14, fontSize: 11, color: 'var(--ink-4)' }}>
      Keys are stored server-side on the persistent state volume. A Cloud Run redeploy doesn't
      reset them. To rotate, paste the new key here — the old value is overwritten and the
      change is recorded in the activity log (the value itself is never logged).
    </div>
  </>
);

const Settings = () => {
  // "Users" tab removed in v0.1.6 — it pointed at a mocked-up local
  // state list while the real users page (sidebar → Users, route
  // /users) talks to the actual /api/users endpoints. Having two
  // different lists of users was confusing — sidebar Users is now
  // the single source of truth.
  const [tab, setTab] = React.useState('brands');
  const auth = useAuth();
  const isOwner = auth?.user?.role === 'owner';
  // v0.1.58: Integrations tab is owner-only — it shows + edits the
  // Brand Asset Manager API key. The server enforces the same role
  // gate on every read/write, so hiding the tab from non-owners is a
  // UX nicety, not the access control.
  const tabs = [
    { k: 'brands', label: 'Brands' },
    { k: 'locations', label: 'Locations' },
    { k: 'splashes', label: 'Splashes' },
    { k: 'drive', label: 'Drive sync' },
    ...(isOwner ? [{ k: 'integrations', label: 'Integrations' }] : []),
    { k: 'notify', label: 'Notifications' },
  ];
  const vp = useViewport();
  const compactNav = vp.isCompact;
  return (
    <AppShell current="settings">
      <PageHeader title="Settings" subtitle="Organisation · Smartech Group" />
      <div style={{
        flex: 1, display: 'flex',
        flexDirection: compactNav ? 'column' : 'row',
        overflow: 'hidden',
      }}>
        {/* Side nav — vertical column on laptop+, horizontal scroll
            strip on mobile / tablet. */}
        <div style={{
          width: compactNav ? '100%' : 200,
          borderRight: compactNav ? 'none' : 'var(--border)',
          borderBottom: compactNav ? 'var(--border)' : 'none',
          padding: compactNav ? '8px 12px' : '16px 10px',
          background: 'var(--ink-9)',
          overflowX: compactNav ? 'auto' : 'visible',
          flexShrink: 0,
        }}>
          <div style={{
            display: 'flex',
            flexDirection: compactNav ? 'row' : 'column',
            gap: compactNav ? 4 : 1,
            minWidth: compactNav ? 'min-content' : 'auto',
          }}>
            {tabs.map(t => (
              <button key={t.k} onClick={() => setTab(t.k)} style={{
                display: 'flex', alignItems: 'center', padding: compactNav ? '8px 14px' : '7px 10px', borderRadius: 6,
                background: tab === t.k ? 'var(--ink-8)' : 'transparent',
                fontSize: 13, fontWeight: tab === t.k ? 500 : 400,
                color: tab === t.k ? 'var(--ink-1)' : 'var(--ink-3)',
                textAlign: 'left',
                whiteSpace: 'nowrap',
                flexShrink: 0,
              }}>{t.label}</button>
            ))}
          </div>
        </div>

        <div style={{
          flex: 1, overflow: 'auto',
          padding: compactNav ? '18px 16px 32px' : '24px 28px 40px',
        }}>
          {tab === 'brands' && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16 }}>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Brands</div>
                  <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>{MOCK_BRANDS.length} brands · {(window.MOCK_VIDEOS || []).length.toLocaleString()} videos total</div>
                </div>
                <Button variant="primary" size="sm" icon={<Icon.plus size={12} />}>Add brand</Button>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
                {MOCK_BRANDS.map((b) => (
                  <div key={b.id} style={{ padding: 16, border: 'var(--border)', borderRadius: 10, display: 'flex', alignItems: 'center', gap: 12 }}>
                    <BrandMark brand={b.name} size={36} radius={8} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>{b.name}</div>
                      <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>{b.products.length} products · {b.videos} videos</div>
                    </div>
                    <Button variant="ghost" size="sm" icon={<Icon.edit size={13} />} />
                  </div>
                ))}
              </div>
            </>
          )}

          {tab === 'locations' && <LocationsTab />}

          {tab === 'splashes' && <SplashesTab />}

          {tab === 'drive' && <DriveSyncTab />}

          {tab === 'integrations' && isOwner && <IntegrationsTab />}

          {tab === 'notify' && (
            <>
              <div style={{ marginBottom: 16 }}>
                <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)' }}>Notifications</div>
                <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>Email and Slack delivery per event type</div>
              </div>
              <div style={{ border: 'var(--border)', borderRadius: 10, background: 'var(--ink-10)' }}>
                {[
                  { l: 'Screen offline > 10 min', s: 'High priority · wakes on-call', on: ['Email', 'Slack'] },
                  { l: 'Upload completed', s: 'Triggered after transcoding finishes', on: ['Email'] },
                  { l: 'Schedule conflict detected', s: 'When creating or editing schedules', on: ['Email', 'Slack'] },
                  { l: 'Drive sync failure', s: 'Hourly check', on: ['Email'] },
                ].map((n, i) => (
                  <SettingsRow key={i} label={n.l} sub={n.s}>
                    <div style={{ display: 'flex', gap: 6 }}>
                      {n.on.map(x => <Chip key={x} tone="neutral">{x}</Chip>)}
                    </div>
                    <Button variant="ghost" size="sm" icon={<Icon.edit size={13} />} />
                  </SettingsRow>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </AppShell>
  );
};

Object.assign(window, { Settings });
