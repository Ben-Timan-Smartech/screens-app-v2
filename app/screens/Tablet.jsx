/* eslint-disable */
// On-tablet staff UI — landscape 1920×1080.
// Flow: PIN (left rail) → brand search & grid → video picker → success.

// ─────────────────────────────────────────────────────────────
// Extended brand list so search actually has something to filter.
// ─────────────────────────────────────────────────────────────
const TABLET_BRANDS = [
  ...MOCK_BRANDS,
  { id: 'samsung', name: 'Samsung', videos: 96 },
  { id: 'lg', name: 'LG', videos: 61 },
  { id: 'apple', name: 'Apple', videos: 44 },
  { id: 'dyson', name: 'Dyson', videos: 38 },
  { id: 'nespresso', name: 'Nespresso', videos: 27 },
  { id: 'theragun', name: 'Theragun', videos: 19 },
  { id: 'ninja', name: 'Ninja', videos: 31 },
  { id: 'garmin', name: 'Garmin', videos: 24 },
];

// ─────────────────────────────────────────────────────────────
// Left rail — store brand + session identity, shared across stages
// ─────────────────────────────────────────────────────────────
const TabletRail = ({ stage, brand, staff = 'Floor staff', step }) => (
  <aside style={{
    width: 420, flexShrink: 0,
    background: 'var(--ink-0)', color: 'var(--on-accent)',
    display: 'flex', flexDirection: 'column',
    padding: '56px 48px',
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 'auto' }}>
      <div style={{
        width: 44, height: 44, borderRadius: 10,
        background: 'var(--on-accent)', color: 'var(--ink-0)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 18, fontWeight: 500,
      }}>S</div>
      <div>
        <div style={{ fontSize: 16, fontWeight: 500, letterSpacing: -0.2 }}>Screens</div>
        <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.55)' }}>Saks Fifth Avenue</div>
      </div>
    </div>

    <div style={{ marginTop: 'auto' }}>
      <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 1.2, color: 'rgba(255,255,255,0.45)', marginBottom: 14 }}>
        {stage === 'pin' ? 'Staff access' : 'Swap what\u2019s playing'}
      </div>
      <div style={{ fontSize: 34, fontWeight: 500, letterSpacing: -0.8, lineHeight: 1.15, marginBottom: 14 }}>
        {stage === 'pin'  && 'Tap in your PIN to continue'}
        {stage === 'brand' && 'Which brand?'}
        {stage === 'video' && (brand ? `${brand.name} videos` : 'Pick a video')}
        {stage === 'done'  && 'Done \u2014 you can walk away'}
      </div>
      <div style={{ fontSize: 15, color: 'rgba(255,255,255,0.6)', lineHeight: 1.55, maxWidth: 280 }}>
        {stage === 'pin'  && 'Your PIN is the last 4 of your staff ID. Session ends automatically when you\u2019re done.'}
        {stage === 'brand' && 'Search or tap a brand. Only brands with videos assigned to this screen appear.'}
        {stage === 'video' && 'Tap any video and it will start on the screen in front of you within a few seconds.'}
        {stage === 'done'  && 'The new video is now playing. Tap anywhere to start over.'}
      </div>

      {/* Step indicator */}
      <div style={{ display: 'flex', gap: 8, marginTop: 40 }}>
        {['pin','brand','video','done'].map((s, i) => (
          <div key={s} style={{
            height: 4, flex: 1, borderRadius: 2,
            background: i <= step ? 'var(--on-accent)' : 'rgba(255,255,255,0.18)',
            transition: 'background .2s',
          }} />
        ))}
      </div>

      <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.45)', marginTop: 28, lineHeight: 1.6 }}>
        {staff === 'Floor staff' ? 'Tablet 14 · Ground floor · Sonos zone' : staff}
        <br />Need help? Call your manager on 6042
      </div>
    </div>
  </aside>
);

// ─────────────────────────────────────────────────────────────
// PIN entry — numpad + dots
// ─────────────────────────────────────────────────────────────
const Numpad = ({ onKey }) => (
  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 140px)', gap: 18 }}>
    {[1,2,3,4,5,6,7,8,9].map((n) => (
      <button key={n} onClick={() => onKey(n)} style={{
        height: 90, fontSize: 32, fontWeight: 500,
        background: 'var(--ink-10)', color: 'var(--ink-1)',
        border: 'var(--border-strong)', borderRadius: 16,
        fontFamily: 'inherit',
      }}>{n}</button>
    ))}
    <div />
    <button onClick={() => onKey(0)} style={{ height: 90, fontSize: 32, fontWeight: 500, background: 'var(--ink-10)', color: 'var(--ink-1)', border: 'var(--border-strong)', borderRadius: 16, fontFamily: 'inherit' }}>0</button>
    <button onClick={() => onKey('back')} style={{ height: 90, background: 'transparent', color: 'var(--ink-3)', borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', border: 'none' }}>
      <Icon.close size={24} />
    </button>
  </div>
);

const TabletPin = ({ onUnlock }) => {
  const [pin, setPin] = React.useState([]);
  const onKey = (k) => {
    if (k === 'back') { setPin(pin.slice(0, -1)); return; }
    if (pin.length >= 4) return;
    const next = [...pin, k];
    setPin(next);
    if (next.length === 4) setTimeout(() => onUnlock && onUnlock(), 180);
  };
  return (
    <div style={{ display: 'flex', height: '100%', background: 'var(--ink-10)' }}>
      <TabletRail stage="pin" step={0} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 48 }}>
        <div style={{ display: 'flex', gap: 20 }}>
          {[0,1,2,3].map((i) => (
            <div key={i} style={{
              width: 22, height: 22, borderRadius: '50%',
              background: i < pin.length ? 'var(--ink-0)' : 'transparent',
              border: '2px solid ' + (i < pin.length ? 'var(--ink-0)' : 'var(--ink-6)'),
              transition: 'all .15s',
            }} />
          ))}
        </div>
        <Numpad onKey={onKey} />
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Brand picker — search input + logo grid
// ─────────────────────────────────────────────────────────────
const TabletBrandPicker = ({ onBack, onPick }) => {
  const [q, setQ] = React.useState('');
  const filtered = TABLET_BRANDS.filter((b) =>
    b.name.toLowerCase().includes(q.toLowerCase())
  );
  return (
    <div style={{ display: 'flex', height: '100%', background: 'var(--ink-10)' }}>
      <TabletRail stage="brand" step={1} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '56px 64px', overflow: 'hidden' }}>
        {/* Search bar */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 28 }}>
          {onBack && (
            <button onClick={onBack} style={{
              width: 60, height: 60, borderRadius: 14,
              border: 'var(--border-strong)', background: 'var(--ink-10)',
              color: 'var(--ink-2)', display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0,
            }}>
              <Icon.chevL size={22} />
            </button>
          )}
          <div style={{
            flex: 1, height: 60, borderRadius: 14,
            border: 'var(--border-strong)', background: 'var(--ink-9)',
            display: 'flex', alignItems: 'center', gap: 14, padding: '0 22px',
          }}>
            <Icon.search size={20} />
            <input
              autoFocus
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search brands"
              style={{
                flex: 1, height: '100%', background: 'transparent',
                border: 'none', outline: 'none', fontSize: 18, fontFamily: 'inherit',
                color: 'var(--ink-1)',
              }}
            />
            {q && (
              <button onClick={() => setQ('')} style={{ background: 'transparent', border: 'none', color: 'var(--ink-3)', padding: 4 }}>
                <Icon.close size={18} />
              </button>
            )}
          </div>
        </div>

        {/* Brand grid */}
        <div style={{ flex: 1, overflow: 'auto', marginRight: -16, paddingRight: 16 }}>
          {filtered.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '80px 0', color: 'var(--ink-4)', fontSize: 16 }}>
              No brands match &ldquo;{q}&rdquo;
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18 }}>
              {filtered.map((b) => (
                <button key={b.id} onClick={() => onPick && onPick(b)} style={{
                  padding: 22, background: 'var(--ink-10)',
                  border: 'var(--border-strong)', borderRadius: 18,
                  display: 'flex', flexDirection: 'column', gap: 18, alignItems: 'flex-start',
                  minHeight: 190, fontFamily: 'inherit',
                }}>
                  <BrandMark brand={b.name} size={72} radius={14} />
                  <div style={{ textAlign: 'left' }}>
                    <div style={{ fontSize: 20, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: -0.3 }}>{b.name}</div>
                    <div style={{ fontSize: 13, color: 'var(--ink-4)', marginTop: 4 }} className="tnum">{b.videos} videos</div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Video picker — two-column grid of large tap targets
// ─────────────────────────────────────────────────────────────
const TabletVideoPicker = ({ onBack, onPick, brand }) => {
  const [q, setQ] = React.useState('');
  const all = (brand ? MOCK_VIDEOS.filter(v => v.brand === brand.name) : MOCK_VIDEOS);
  const list = all.length ? all : MOCK_VIDEOS; // fallback for brands w/o mock videos
  const filtered = list.filter(v => v.title.toLowerCase().includes(q.toLowerCase()));
  return (
    <div style={{ display: 'flex', height: '100%', background: 'var(--ink-10)' }}>
      <TabletRail stage="video" step={2} brand={brand} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '56px 64px', overflow: 'hidden' }}>
        <div style={{ display: 'flex', gap: 12, marginBottom: 28 }}>
          {onBack && (
            <button onClick={onBack} style={{
              width: 60, height: 60, borderRadius: 14,
              border: 'var(--border-strong)', background: 'var(--ink-10)',
              color: 'var(--ink-2)', display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0,
            }}>
              <Icon.chevL size={22} />
            </button>
          )}
          <div style={{
            flex: 1, height: 60, borderRadius: 14,
            border: 'var(--border-strong)', background: 'var(--ink-9)',
            display: 'flex', alignItems: 'center', gap: 14, padding: '0 22px',
          }}>
            <Icon.search size={20} />
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={`Search ${brand?.name || ''} videos`}
              style={{
                flex: 1, height: '100%', background: 'transparent',
                border: 'none', outline: 'none', fontSize: 18, fontFamily: 'inherit',
                color: 'var(--ink-1)',
              }}
            />
          </div>
        </div>

        <div style={{ flex: 1, overflow: 'auto', marginRight: -16, paddingRight: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            {filtered.map((v, i) => (
              <button key={v.id} onClick={() => onPick && onPick(v)} style={{
                display: 'flex', flexDirection: 'column', gap: 0, padding: 0,
                background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 14,
                textAlign: 'left', overflow: 'hidden', fontFamily: 'inherit',
              }}>
                <div style={{ width: '100%', aspectRatio: '16 / 9' }}>
                  <Thumbnail title={v.title} brand={v.brand} duration={v.duration} />
                </div>
                <div style={{ padding: 16 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                    {i === 0 && <Chip tone="ok">Now playing</Chip>}
                    <span style={{ fontSize: 16, fontWeight: 500, color: 'var(--ink-1)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                  </div>
                  <div style={{ fontSize: 13, color: 'var(--ink-4)' }}>{v.product || 'Brand loop'} · <span className="tnum">{v.duration}</span></div>
                </div>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Success
// ─────────────────────────────────────────────────────────────
const TabletSuccess = ({ video, brand }) => (
  <div style={{ display: 'flex', height: '100%', background: 'var(--ink-10)' }}>
    <TabletRail stage="done" step={3} brand={brand} />
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 32 }}>
      <div style={{ width: 96, height: 96, borderRadius: '50%', background: 'var(--ok-bg)', color: 'var(--ok)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Icon.check size={40} stroke={2} />
      </div>
      <div style={{ textAlign: 'center' }}>
        <div style={{ fontSize: 30, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: -0.5, marginBottom: 8 }}>Now playing</div>
        <div style={{ fontSize: 18, color: 'var(--ink-3)' }}>{video?.title || 'Arc Ultra \u2014 hero reveal'}</div>
      </div>
      <div style={{ width: 440, aspectRatio: '16/9', borderRadius: 14, overflow: 'hidden' }}>
        <Thumbnail title={video?.title || 'Arc Ultra \u2014 hero reveal'} brand={video?.brand || brand?.name || 'SONOS'} />
      </div>
      <div style={{ fontSize: 13, color: 'var(--ink-4)' }}>Session ends in 15s</div>
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────
// Full interactive flow
// ─────────────────────────────────────────────────────────────
const TabletFlow = () => {
  const [stage, setStage] = React.useState('pin');
  const [brand, setBrand] = React.useState(null);
  const [video, setVideo] = React.useState(null);
  return (
    <div className="scr" style={{ height: '100%', width: '100%' }}>
      {stage === 'pin' && <TabletPin onUnlock={() => setStage('brands')} />}
      {stage === 'brands' && <TabletBrandPicker onBack={() => setStage('pin')} onPick={(b) => { setBrand(b); setStage('videos'); }} />}
      {stage === 'videos' && <TabletVideoPicker brand={brand} onBack={() => setStage('brands')} onPick={(v) => { setVideo(v); setStage('done'); setTimeout(() => { setStage('pin'); setBrand(null); setVideo(null); }, 4500); }} />}
      {stage === 'done' && <TabletSuccess video={video} brand={brand} />}
    </div>
  );
};

Object.assign(window, { TabletPin, TabletBrandPicker, TabletVideoPicker, TabletSuccess, TabletFlow });
