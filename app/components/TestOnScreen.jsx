/* eslint-disable */
// "Test on a screen": play one or more clips on a single real screen for a
// short time, then put the screen back exactly as it was. Lets an operator
// catch a clip that won't decode, letterboxes, or is too heavy for an older
// box before it goes out to the fleet.
//
// Server: POST /api/screens/<id>/preview starts a test, DELETE ends it early.
// The server restores the screen on its own when the time is up, and any real
// edit to that screen (push, toggle, rotation) ends the test first.
//
// Every script shares one global scope (Babel turns top-level consts into
// vars, last file wins), so all names here carry a TestOnScreen prefix.

const TEST_ON_SCREEN_DURATIONS = [
  { sec: 30,  label: '30 s' },
  { sec: 60,  label: '1 min' },
  { sec: 120, label: '2 min' },
  { sec: 300, label: '5 min' },
];
const TEST_ON_SCREEN_LAST_KEY = 'screens.testScreenId';

const previewOnScreen = async (deviceId, videos, durationSec) => {
  const items = videos.map((v) => ({
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
  }));
  const r = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/preview`, {
    method: 'POST', credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, durationSec }),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    const byCode = {
      unknown_screen: 'That screen is no longer registered.',
      unauthenticated: 'Your session has expired. Sign in again.',
      forbidden: "Your role can't push content to screens.",
    };
    throw new Error(data.detail || byCode[data.error] || `Couldn't start the test (${r.status}).`);
  }
  return data;
};

// Ends a test early. Resolves either way when no test is running (it already
// finished, or an edit ended it), since the screen is back to normal.
const endPreviewOnScreen = async (deviceId) => {
  const r = await fetch(`/api/screens/${encodeURIComponent(deviceId)}/preview`, {
    method: 'DELETE', credentials: 'include',
  });
  if (r.ok || r.status === 404) return true;
  const data = await r.json().catch(() => ({}));
  throw new Error(data.detail || `Couldn't end the test (${r.status}).`);
};

const testOnScreenClock = (sec) => {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

const testOnScreenEvery = (sec) => {
  if (!sec) return null;
  if (sec < 60) return `every ${Math.round(sec)} s`;
  const m = Math.round(sec / 60);
  return `every ${m} min`;
};

// Plain-words version of the library's heavy flags.
const testOnScreenHeavyWords = (v) => {
  const r = (typeof heavyReasons === 'function') ? heavyReasons(v) : [];
  if (r.includes('4k')) return '4K';
  if (r.includes('hires')) return 'above 1080p';
  if (r.includes('large')) return `${v.sizeMb} MB`;
  return null;
};

// Unnamed screens fall back to their code as a name; don't print it twice.
const testOnScreenSameName = (screen) => {
  const norm = (x) => String(x || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  return !screen.name || norm(screen.name) === norm(screen.screenCode);
};

const TestOnScreenScreenRow = ({ screen, storeName, selected, onPick }) => {
  const online = screen.status === 'online';
  return (
    <button
      type="button"
      disabled={!online}
      onClick={() => online && onPick(screen.deviceId)}
      aria-pressed={selected}
      style={{
        display: 'flex', alignItems: 'center', gap: 10, width: '100%',
        minHeight: 44, padding: '6px 10px', textAlign: 'left',
        background: selected ? 'var(--ink-8)' : 'transparent',
        boxShadow: selected ? 'inset 2px 0 0 0 var(--accent)' : 'none',
        borderRadius: 3, cursor: online ? 'pointer' : 'not-allowed',
        opacity: online ? 1 : 0.5,
      }}>
      <span aria-hidden style={{
        width: 7, height: 7, borderRadius: '50%', flexShrink: 0,
        background: online ? 'var(--ok-dot)' : 'var(--ink-6)',
      }} />
      <span style={{
        fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--ink-2)',
        width: 64, flexShrink: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
      }}>{screen.screenCode}</span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ display: 'block', fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {testOnScreenSameName(screen) ? storeName : screen.name}
        </span>
        <span style={{ display: 'block', fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {testOnScreenSameName(screen)
            ? (online ? 'Online' : 'Offline')
            : (online ? storeName : `Offline · ${storeName}`)}
        </span>
      </span>
      <span style={{ fontSize: 11, color: 'var(--ink-4)', flexShrink: 0 }}>
        {screen.orient === 'portrait' ? 'Portrait' : 'Landscape'}
      </span>
      {selected && <span style={{ color: 'var(--accent)', display: 'flex' }}><Icon.check size={14} /></span>}
    </button>
  );
};

const TestOnScreenLabel = ({ children, right }) => (
  <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 8 }}>
    <span style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-3)' }}>{children}</span>
    {right && <span style={{ fontSize: 11, color: 'var(--ink-4)' }}>{right}</span>}
  </div>
);

const TestOnScreenNote = ({ children }) => (
  <div role="note" style={{
    display: 'flex', gap: 8, alignItems: 'flex-start',
    padding: '9px 11px', borderRadius: 4,
    background: 'var(--warn-bg)', color: 'var(--warn)', fontSize: 12, lineHeight: 1.45,
  }}>
    <span style={{ display: 'flex', marginTop: 1 }}><Icon.warning size={13} /></span>
    <span>{children}</span>
  </div>
);

// videos: clips to test (from the library or a fresh upload). Leave empty to
//         show a clip picker (used from Screen detail).
// deviceId: preselect a screen (used from Screen detail).
const TestOnScreenModal = ({ videos: givenVideos, deviceId: givenDeviceId, onClose }) => {
  const fleet = useFleet();
  const pickClip = !givenVideos || givenVideos.length === 0;
  const [clips, setClips] = React.useState(givenVideos || []);
  const [clipQuery, setClipQuery] = React.useState('');
  const [screenQuery, setScreenQuery] = React.useState('');
  const [deviceId, setDeviceId] = React.useState(() => {
    if (givenDeviceId) return givenDeviceId;
    try { return localStorage.getItem(TEST_ON_SCREEN_LAST_KEY) || null; } catch { return null; }
  });
  const [duration, setDuration] = React.useState(60);
  const [phase, setPhase] = React.useState('setup');   // setup | sending | running | ending | finished
  const [error, setError] = React.useState(null);
  const [endsAt, setEndsAt] = React.useState(null);
  const [now, setNow] = React.useState(Date.now());

  // Capture phase + stopPropagation so Esc closes only this dialog, not a
  // library preview sitting underneath it.
  React.useEffect(() => {
    const onKey = (e) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      onClose();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  React.useEffect(() => {
    if (phase !== 'running') return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [phase]);

  const remainingSec = endsAt ? (endsAt - now) / 1000 : 0;
  React.useEffect(() => {
    if (phase === 'running' && endsAt && remainingSec <= 0) setPhase('finished');
  }, [phase, endsAt, remainingSec]);

  const storeNameOf = (id) => {
    const list = (typeof allStores === 'function') ? allStores() : (window.MOCK_STORES || []);
    return (list.find((s) => s.id === id) || {}).name || (id === 'unassigned' ? 'No store' : id);
  };

  const sortedFleet = React.useMemo(() => {
    const q = screenQuery.trim().toLowerCase();
    return [...fleet]
      .filter((s) => !q || [s.screenCode, s.name, storeNameOf(s.storeId)].some((x) => String(x || '').toLowerCase().includes(q)))
      .sort((a, b) =>
        (a.status === 'online' ? 0 : 1) - (b.status === 'online' ? 0 : 1)
        || String(a.screenCode).localeCompare(String(b.screenCode)));
  }, [fleet, screenQuery]);

  const screen = fleet.find((s) => s.deviceId === deviceId) || null;
  const screenOnline = screen?.status === 'online';
  const pollSec = screen?.raw?.pollIntervalSec || null;
  const screenLabel = screen ? (screen.screenCode || screen.name) : '';

  const clipResults = React.useMemo(() => {
    if (!pickClip) return [];
    const q = clipQuery.trim().toLowerCase();
    return (window.MOCK_VIDEOS || [])
      .filter((v) => (v.mediaUrl || v.url) && !v.pendingSync)
      .filter((v) => !q || [v.title, v.brand, v.product].some((x) => String(x || '').toLowerCase().includes(q)))
      .slice(0, 6);
  }, [pickClip, clipQuery]);

  // Things worth knowing before the test runs.
  const notes = [];
  const heavy = clips.map((v) => [v, testOnScreenHeavyWords(v)]).filter(([, w]) => w);
  if (heavy.length > 0) {
    const [v, words] = heavy[0];
    notes.push(clips.length === 1
      ? `This clip is ${words}. The older boxes can stutter or freeze on files like this, so a test is the right check.`
      : `"${v.title}" is ${words}. The older boxes can stutter or freeze on files like this.`);
  }
  if (screen && clips.length === 1 && clips[0].width && clips[0].height) {
    const clipPortrait = clips[0].height > clips[0].width;
    const screenPortrait = screen.orient === 'portrait';
    if (clipPortrait !== screenPortrait) {
      notes.push(`This clip is ${clipPortrait ? 'portrait' : 'landscape'} and ${screenLabel} is a ${screenPortrait ? 'portrait' : 'landscape'} screen, so it will play letterboxed.`);
    }
  }
  if (screen && pollSec && pollSec >= duration) {
    notes.push(`${screenLabel} only checks in ${testOnScreenEvery(pollSec)}, so a ${testOnScreenClock(duration)} test may end before it shows. Pick a longer test, or switch the screen to Fast in Screen detail.`);
  }

  const start = async () => {
    if (!screen || clips.length === 0) return;
    setError(null);
    setPhase('sending');
    try {
      const res = await previewOnScreen(screen.deviceId, clips, duration);
      try { localStorage.setItem(TEST_ON_SCREEN_LAST_KEY, screen.deviceId); } catch {}
      setEndsAt(Date.now() + (res.durationSec || duration) * 1000);
      setNow(Date.now());
      setPhase('running');
    } catch (e) {
      setError(e.message);
      setPhase('setup');
    }
  };

  const endNow = async () => {
    setError(null);
    setPhase('ending');
    try {
      await endPreviewOnScreen(screen.deviceId);
      setPhase('finished');
      showToast(`${screenLabel} is back on its playlist`, 'ok');
    } catch (e) {
      setError(e.message);
      setPhase('running');
    }
  };

  const totalSec = clips.reduce((a, v) => a + (v.durationSec || 15), 0);
  const inSetup = phase === 'setup' || phase === 'sending';

  return (
    <div
      onClick={(e) => { e.stopPropagation(); onClose(); }}
      style={{
        position: 'fixed', inset: 0, zIndex: 100,
        background: 'rgba(9, 9, 11, 0.6)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
      }}>
      <div
        role="dialog" aria-modal="true" aria-labelledby="test-on-screen-title"
        onClick={(e) => e.stopPropagation()}
        className="scr-modal-panel"
        style={{
          width: 'min(560px, 100%)', maxHeight: '90vh',
          display: 'flex', flexDirection: 'column',
          background: 'var(--ink-10)', border: 'var(--border-strong)', borderRadius: 8,
          boxShadow: '0 24px 64px -12px rgba(0,0,0,0.5)', overflow: 'hidden',
        }}>
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '16px 20px 14px', borderBottom: 'var(--border)' }}>
          <div style={{ flex: 1 }}>
            <h2 id="test-on-screen-title" style={{ margin: 0, fontSize: 16, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.01em' }}>
              Test on a screen
            </h2>
            <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 3 }}>
              Plays on one screen for a short time, then puts it back exactly as it was.
            </div>
          </div>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} title="Close" />
        </div>

        {inSetup ? (
          <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* Content */}
            <div>
              <TestOnScreenLabel right={clips.length > 1 ? `${clips.length} clips · ${testOnScreenClock(totalSec)} loop` : null}>
                Content
              </TestOnScreenLabel>
              {clips.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {clips.slice(0, 3).map((v) => (
                    <div key={v.id} style={{
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: 8, border: 'var(--border)', borderRadius: 4,
                    }}>
                      <div style={{
                        width: 56, aspectRatio: '16 / 9', flexShrink: 0, borderRadius: 2, overflow: 'hidden',
                        background: 'var(--ink-8)', color: 'var(--ink-4)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                      }}>
                        {v.type === 'image' && (v.mediaUrl || v.url)
                          ? <img src={v.mediaUrl || v.url} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
                          : <Icon.video size={14} />}
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</div>
                        <div className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>
                          {[v.brand, v.durationSec ? testOnScreenClock(v.durationSec) : null,
                            v.width && v.height ? `${v.width}×${v.height}` : null,
                            v.sizeMb ? `${v.sizeMb} MB` : null].filter(Boolean).join(' · ')}
                        </div>
                      </div>
                      {pickClip && (
                        <Button variant="ghost" size="sm" onClick={() => setClips([])}>Change</Button>
                      )}
                    </div>
                  ))}
                  {clips.length > 3 && (
                    <div style={{ fontSize: 11, color: 'var(--ink-4)', paddingLeft: 2 }}>+{clips.length - 3} more</div>
                  )}
                </div>
              ) : (
                <div>
                  <Input
                    placeholder="Search the library by title or brand"
                    value={clipQuery}
                    onChange={(e) => setClipQuery(e.target.value)}
                    leadingIcon={<Icon.search size={13} />}
                  />
                  <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {clipResults.length === 0 ? (
                      <div style={{ padding: '10px 2px', fontSize: 12, color: 'var(--ink-4)' }}>
                        {clipQuery ? `Nothing in the library matches "${clipQuery}".` : 'The library is empty.'}
                      </div>
                    ) : clipResults.map((v) => (
                      <button key={v.id} type="button" onClick={() => setClips([v])}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 10, minHeight: 40,
                          padding: '6px 10px', borderRadius: 3, textAlign: 'left', width: '100%',
                        }}
                        onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--ink-9)'; }}
                        onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}>
                        <span style={{ flex: 1, minWidth: 0, fontSize: 13, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
                        <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)', flexShrink: 0 }}>
                          {[v.brand, v.durationSec ? testOnScreenClock(v.durationSec) : null].filter(Boolean).join(' · ')}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* Screen */}
            {!givenDeviceId && (
              <div>
                <TestOnScreenLabel right={`${fleet.filter((s) => s.status === 'online').length} online`}>Screen</TestOnScreenLabel>
                {fleet.length > 6 && (
                  <div style={{ marginBottom: 6 }}>
                    <Input
                      placeholder="Search by screen code, name or store"
                      value={screenQuery}
                      onChange={(e) => setScreenQuery(e.target.value)}
                      leadingIcon={<Icon.search size={13} />}
                    />
                  </div>
                )}
                <div style={{ maxHeight: 232, overflow: 'auto', border: 'var(--border)', borderRadius: 4, padding: 4 }}>
                  {fleet.length === 0 ? (
                    <div style={{ padding: 12, fontSize: 12, color: 'var(--ink-4)' }}>No screens registered yet.</div>
                  ) : sortedFleet.length === 0 ? (
                    <div style={{ padding: 12, fontSize: 12, color: 'var(--ink-4)' }}>No screen matches "{screenQuery}".</div>
                  ) : sortedFleet.map((s) => (
                    <TestOnScreenScreenRow
                      key={s.deviceId}
                      screen={s}
                      storeName={storeNameOf(s.storeId)}
                      selected={s.deviceId === deviceId}
                      onPick={setDeviceId}
                    />
                  ))}
                </div>
              </div>
            )}
            {givenDeviceId && screen && (
              <div>
                <TestOnScreenLabel>Screen</TestOnScreenLabel>
                <div style={{ fontSize: 13, color: 'var(--ink-1)' }}>
                  <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-2)', marginRight: 8 }}>{screen.screenCode}</span>
                  {testOnScreenSameName(screen) ? null : screen.name}
                  <span style={{ color: 'var(--ink-4)' }}> · {screen.orient === 'portrait' ? 'Portrait' : 'Landscape'}</span>
                </div>
                {!screenOnline && (
                  <div style={{ fontSize: 12, color: 'var(--err)', marginTop: 4 }}>
                    This screen is offline. A test needs it connected so it can actually play.
                  </div>
                )}
              </div>
            )}

            {/* Duration */}
            <div>
              <TestOnScreenLabel>Play for</TestOnScreenLabel>
              <div role="radiogroup" aria-label="Test length" style={{
                display: 'inline-flex', gap: 2, padding: 3,
                border: 'var(--border)', borderRadius: 4,
              }}>
                {TEST_ON_SCREEN_DURATIONS.map((d) => {
                  const on = d.sec === duration;
                  return (
                    <button key={d.sec} type="button" role="radio" aria-checked={on}
                      onClick={() => setDuration(d.sec)}
                      style={{
                        minWidth: 56, height: 30, padding: '0 10px', borderRadius: 2,
                        fontSize: 12, fontWeight: on ? 500 : 400,
                        background: on ? 'var(--ink-8)' : 'transparent',
                        color: on ? 'var(--ink-1)' : 'var(--ink-3)',
                      }}>{d.label}</button>
                  );
                })}
              </div>
            </div>

            {notes.map((n, i) => <TestOnScreenNote key={i}>{n}</TestOnScreenNote>)}

            {error && (
              <div role="alert" style={{ padding: '9px 11px', borderRadius: 4, background: 'var(--err-bg)', color: 'var(--err)', fontSize: 12 }}>
                {error}
              </div>
            )}
          </div>
        ) : (
          /* Running / finished */
          <div style={{ padding: '22px 20px', display: 'flex', flexDirection: 'column', gap: 10 }} aria-live="polite">
            {phase === 'finished' ? (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 15, color: 'var(--ink-1)', fontWeight: 500 }}>
                  <span style={{ color: 'var(--ok)', display: 'flex' }}><Icon.check size={16} /></span>
                  Test finished
                </div>
                <div style={{ fontSize: 13, color: 'var(--ink-3)' }}>
                  {screenLabel} is back on its normal playlist. If the clip played cleanly, it's safe to push.
                </div>
              </>
            ) : (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 15, color: 'var(--ink-1)', fontWeight: 500 }}>
                  <span aria-hidden style={{
                    width: 7, height: 7, borderRadius: '50%', background: 'var(--accent-signal, var(--warn))',
                    animation: 'sidebar-live-pulse 2s ease-in-out infinite',
                  }} />
                  Playing on {screenLabel}
                </div>
                <div className="tnum" style={{ fontSize: 28, fontWeight: 500, color: 'var(--ink-1)', letterSpacing: '-0.02em' }}>
                  {testOnScreenClock(remainingSec)}
                  <span style={{ fontSize: 13, color: 'var(--ink-4)', fontWeight: 400, marginLeft: 8, letterSpacing: 0 }}>until it goes back</span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--ink-4)', lineHeight: 1.5 }}>
                  The screen switches on its next check-in{pollSec ? ` (${testOnScreenEvery(pollSec)})` : ''}.
                  Closing this window leaves the test running; it ends by itself, or from the screen's page.
                </div>
              </>
            )}
            {error && (
              <div role="alert" style={{ padding: '9px 11px', borderRadius: 4, background: 'var(--err-bg)', color: 'var(--err)', fontSize: 12 }}>
                {error}
              </div>
            )}
          </div>
        )}

        {/* Footer */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'flex-end',
          padding: '12px 20px', borderTop: 'var(--border)', background: 'var(--ink-9)',
        }}>
          {inSetup ? (
            <>
              <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
              <Button variant="primary" size="sm" icon={<Icon.play size={11} />}
                disabled={!screenOnline || clips.length === 0 || phase === 'sending'}
                onClick={start}>
                {phase === 'sending' ? 'Starting…'
                  : !screen ? 'Pick a screen'
                  : clips.length === 0 ? 'Pick a clip'
                  : `Play on ${screenLabel}`}
              </Button>
            </>
          ) : phase === 'finished' ? (
            <Button variant="primary" size="sm" onClick={onClose}>Done</Button>
          ) : (
            <>
              <Button variant="secondary" size="sm" disabled={phase === 'ending'} onClick={endNow}>
                {phase === 'ending' ? 'Ending…' : 'End test now'}
              </Button>
              <Button variant="primary" size="sm" onClick={onClose}>Done</Button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

Object.assign(window, { TestOnScreenModal, previewOnScreen, endPreviewOnScreen });
