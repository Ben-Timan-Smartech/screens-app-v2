/* eslint-disable */
// CommandPalette — slash-key activated command launcher with arg
// autocomplete and inline help.
//
// Pressing `/` (or Cmd/Ctrl-K) on any page opens a centred modal
// with a search input and a filterable list of commands. Linear /
// Vercel / GitHub pattern.
//
//   Open                /  or  Cmd/Ctrl-K
//   Filter              type into the input
//   Navigate            ↑ / ↓
//   Run / pick arg      ↵
//   Back from arg view  ⌫ (when input is empty) or Esc
//   Dismiss             Esc, or click the scrim
//
// Two-stage UX for argumented commands:
//
//   Stage 1: choose a command. Highlighted command's description +
//            argument hint show in the bottom help panel so you
//            know what it does and what it needs BEFORE pressing
//            Enter.
//
//   Stage 2: only fires for commands with `needs: {...}`. The list
//            switches to argument options pulled live from the
//            current fleet / library — type to filter, ↵ to run.
//            Stage label up top shows breadcrumb so you know where
//            you are.
//
// We intentionally don't open while focus is in another text input —
// otherwise typing "/foo" into the sync-group field or a CMS search
// would pop the palette. Matches the standard convention.
//
// Commands are gated on the current user's permissions, mirroring
// the sidebar's `can()` checks, so a Viewer never sees actions they
// can't run.

// ── Argument source pickers ──────────────────────────────────
// Pulled from `window.MOCK_VIDEOS` / `window.MOCK_BRANDS` (kept in
// sync by useLibrary) and the live screens registry (re-fetched from
// /api/screens via a quick fetch). Memoised at call-time so a stale
// fleet doesn't show after a screen registers.
const argSourceFor = async (type) => {
  switch (type) {
    case 'screen': {
      try {
        const res = await fetch('/api/screens');
        if (!res.ok) return [];
        const data = await res.json();
        const screens = Array.isArray(data.screens) ? data.screens : [];
        return screens.map((s) => ({
          id:    s.deviceId,
          label: s.name || s.deviceId,
          hint:  [s.location?.storeId, s.location?.screenCode].filter(Boolean).join(' · ')
                 || (s.online ? 'online' : 'offline'),
          payload: s,
        }));
      } catch { return []; }
    }
    case 'video': {
      const videos = window.MOCK_VIDEOS || [];
      return videos.map((v) => ({
        id:    v.id,
        label: v.title || v.id,
        hint:  [v.brand, v.product, v.duration].filter(Boolean).join(' · '),
        payload: v,
      }));
    }
    case 'syncGroup': {
      try {
        const res = await fetch('/api/screens');
        if (!res.ok) return [];
        const data = await res.json();
        const screens = Array.isArray(data.screens) ? data.screens : [];
        const groups = {};
        for (const s of screens) {
          if (!s.syncGroup) continue;
          (groups[s.syncGroup] ||= []).push(s);
        }
        return Object.entries(groups).map(([g, members]) => ({
          id:    g,
          label: g,
          hint:  `${members.length} screen${members.length === 1 ? '' : 's'}`,
          payload: { groupId: g, members },
        }));
      } catch { return []; }
    }
    default: return [];
  }
};

// ── Command catalogue ────────────────────────────────────────
// Each entry:
//   id          stable key for React lists + telemetry
//   label       primary text in the row
//   hint        secondary text (where it lives or what it does)
//   description longer explanation shown in the bottom help panel
//               while this command is highlighted
//   keywords    extra terms the filter matches against
//   perm        required permission key, or null = everyone
//   needs       optional: { type: 'screen'|'video'|'syncGroup',
//                           label: stage-2 prompt, hint: argument
//                           description (what the user is picking) }
//   run         called with the chosen argument's `payload` (or
//               undefined for argument-free commands)
const buildCommands = () => [
  // ── Navigation ─────────────────────────────────────────
  {
    id: 'nav.dashboard',
    label: 'Go to Dashboard',
    hint: 'Fleet status overview',
    description: 'Jump to the dashboard — live screen status, recent activity, quick push to your store.',
    keywords: 'home overview start',
    perm: null,
    run: () => navigate('/dashboard'),
  },
  {
    id: 'nav.library',
    label: 'Go to Content library',
    hint: 'Browse and upload videos',
    description: 'The full video catalogue across brands. Filter by brand, preview, push to a screen, upload new content.',
    keywords: 'videos brands content',
    perm: 'library.view',
    run: () => navigate('/library'),
  },
  {
    id: 'nav.screens',
    label: 'Go to Screens',
    hint: 'All registered tablets',
    description: 'Every tablet + TV box that has ever called home. Shows online/offline state, current playlist, sync group.',
    keywords: 'tablets devices fleet',
    perm: 'screens.view',
    run: () => navigate('/screens'),
  },
  {
    id: 'nav.sync-groups',
    label: 'Go to Sync groups',
    hint: 'Every group + member screens at a glance',
    description: 'Top-level view of every sync group across the fleet. Shows members with online state, current playlist size, and a Calibrate button per group. Orphan screens (not in any group) appear at the bottom.',
    keywords: 'sync groups synchronised stores frame locked',
    perm: 'screens.view',
    run: () => navigate('/sync-groups'),
  },
  {
    id: 'nav.activity',
    label: 'Go to Activity log',
    hint: 'Recent CMS + screen events',
    description: 'Time-ordered log of every push, command, registration, and crash report across the fleet.',
    keywords: 'logs history audit',
    perm: 'activity.view',
    run: () => navigate('/activity'),
  },
  {
    id: 'nav.users',
    label: 'Go to Users & permissions',
    hint: 'Invite, edit roles',
    description: 'Manage who has access to the CMS. Owner can promote / demote / remove. Roles are gated against a central permissions matrix.',
    keywords: 'people invite role permissions team',
    perm: 'users.view',
    run: () => navigate('/users'),
  },
  {
    id: 'nav.settings',
    label: 'Go to Settings',
    hint: 'Drive sync, brands, splashes',
    description: 'Configure Drive Sync, brand catalogue, per-location splash videos, and miscellaneous CMS preferences.',
    keywords: 'config preferences drive sync',
    perm: 'settings.view',
    run: () => navigate('/settings'),
  },

  // ── Argumented navigation ──────────────────────────────
  {
    id: 'open.screen',
    label: 'Open a screen…',
    hint: 'Pick from the fleet',
    description: 'Jump straight to a specific screen\'s detail page — its current playlist, sync group, audio, poll mode, danger zone.',
    keywords: 'screen detail tablet open',
    perm: 'screens.view',
    needs: { type: 'screen', label: 'Which screen?',
             hint: 'Pick a registered tablet from the fleet.' },
    run: (s) => navigate(`/screens/${encodeURIComponent(s.location?.storeId || 'unassigned')}/${encodeURIComponent(s.deviceId)}`),
  },
  {
    id: 'open.video',
    label: 'Open a video…',
    hint: 'Pick from the library',
    description: 'Jump to a video\'s preview in the Content Library. Useful for "show me the Era 300 promo" without browsing brands.',
    keywords: 'video preview library find',
    perm: 'library.view',
    needs: { type: 'video', label: 'Which video?',
             hint: 'Filter by title, brand, or product.' },
    run: (v) => {
      navigate('/library');
      // Library listens for this and opens the preview modal. Defer one
      // tick so ContentLibrary has mounted + attached its listener before
      // we fire (a synchronous dispatch would land before the mount).
      setTimeout(() => window.dispatchEvent(new CustomEvent('open-video-preview', { detail: { videoId: v.id } })), 0);
    },
  },

  // ── Actions ────────────────────────────────────────────
  {
    id: 'act.upload',
    label: 'Upload video',
    hint: 'Open the upload panel',
    description: 'Opens the Content Library upload panel — pick a file from your desktop, choose a brand, click Upload. The new video appears in the grid the instant the progress bar finishes.',
    keywords: 'add new content desktop file',
    perm: 'library.sync',
    run: () => {
      navigate('/library');
      // Defer one tick so ContentLibrary has mounted + attached its
      // `open-upload-panel` listener before we fire (a synchronous
      // dispatch would land before the mount and be missed).
      setTimeout(() => window.dispatchEvent(new Event('open-upload-panel')), 0);
    },
  },
  {
    id: 'act.drive-sync',
    label: 'Run Drive Sync now',
    hint: 'Pull latest from Google Drive',
    description: 'Triggers a background scan of the shared Drive folder. New videos appear in the library within ~1 minute. Cost: a Drive API call per brand folder.',
    keywords: 'refresh library scan import',
    perm: 'library.sync',
    run: () => {
      // ?tab=drive selects the Drive sync tab (the router parses the
      // query, and Settings seeds its tab from it). Defer the event
      // one tick so the DriveSyncTab has mounted + attached its
      // `run-drive-sync` listener before we fire.
      navigate('/settings?tab=drive');
      setTimeout(() => window.dispatchEvent(new Event('run-drive-sync')), 0);
    },
  },
  {
    id: 'act.refresh-screen',
    label: 'Refresh a screen now…',
    hint: 'Force the next poll immediately',
    description: 'Tells the chosen screen to skip its scheduled poll wait and re-fetch /api/state right now. Useful in Slow mode (10-min poll) when a push needs to land immediately.',
    keywords: 'force poll refresh sync nudge',
    perm: 'screens.command',
    needs: { type: 'screen', label: 'Refresh which screen?',
             hint: 'The chosen tablet picks up changes on its next tick.' },
    run: async (s) => {
      try {
        await sendScreenCommand(s.deviceId, 'refresh');
        showToast(`Refresh queued for ${s.name || s.deviceId}`, 'ok');
      } catch (e) { showToast(`Refresh failed: ${e.message}`, 'err'); }
    },
  },
  {
    id: 'act.update-screen',
    label: 'Update player APK on a screen…',
    hint: 'Push the latest tablet build',
    description: 'Tells the chosen tablet to check for a newer APK and self-update. Same flow as the 6-hour automatic check, just triggered now.',
    keywords: 'apk update tablet install build',
    perm: 'screens.command',
    needs: { type: 'screen', label: 'Update which screen?',
             hint: 'Updater runs immediately; tablet self-installs on success.' },
    run: async (s) => {
      try {
        await sendScreenCommand(s.deviceId, 'update');
        showToast(`Update triggered on ${s.name || s.deviceId}`, 'ok');
      } catch (e) { showToast(`Update failed: ${e.message}`, 'err'); }
    },
  },
  {
    id: 'act.reboot-screen',
    label: 'Reboot a screen…',
    hint: 'Restart the player activity',
    description: 'Kills the player activity and relaunches it. Player state rebuilds from scratch — cache survives, registration survives. Use when the player gets stuck.',
    keywords: 'reboot restart power cycle hang',
    perm: 'screens.command',
    needs: { type: 'screen', label: 'Reboot which screen?',
             hint: 'Player activity restarts; cache + registration survive.' },
    run: async (s) => {
      try {
        await sendScreenCommand(s.deviceId, 'reboot');
        showToast(`Reboot triggered on ${s.name || s.deviceId}`, 'ok');
      } catch (e) { showToast(`Reboot failed: ${e.message}`, 'err'); }
    },
  },
  {
    id: 'act.calibrate',
    label: 'Calibrate a sync group…',
    hint: 'Show synchronised clock',
    description: 'Puts every screen in the chosen sync group into calibration mode for 60 s — they all display a giant ticking server-corrected clock. Stand between them; if the digits match to the same fractional second, clock sync is healthy.',
    keywords: 'sync clock calibration test diagnose',
    perm: 'screens.command',
    needs: { type: 'syncGroup', label: 'Calibrate which group?',
             hint: 'Every member shows a synchronised clock for 60 s.' },
    run: async (g) => {
      try {
        const res = await calibrateSyncGroup(g.groupId, 60);
        const n = res.screensTargeted || 0;
        showToast(`Calibration started on ${n} screen${n === 1 ? '' : 's'} in ${g.groupId}`, 'ok');
      } catch (e) { showToast(`Calibrate failed: ${e.message}`, 'err'); }
    },
  },

  // ── Diagnostics ────────────────────────────────────────
  {
    id: 'diag.crashes',
    label: 'Open crash log',
    hint: '/api/crashes in a new tab',
    description: 'Tablet uncaught-exception reports — stack trace, app version, device, last 40 log lines per crash. Use after a screen has gone dark unexpectedly.',
    keywords: 'errors debug crash exception stack',
    perm: 'activity.view',
    run: () => window.open('/api/crashes', '_blank'),
  },
  {
    id: 'diag.logs',
    label: 'Open warnings + errors log',
    hint: '/api/logs in a new tab',
    description: 'Non-fatal warnings shipped from the fleet — decoder fallbacks, drift-skip catches, bitrate filter trips. Pre-crash signal that explains why a screen behaved oddly.',
    keywords: 'warnings debug logs noise',
    perm: 'activity.view',
    run: () => window.open('/api/logs', '_blank'),
  },

  // ── Preferences / misc ─────────────────────────────────
  {
    id: 'pref.dark',
    label: 'Toggle dark mode',
    hint: 'Light ↔ dark theme',
    description: 'Switches the CMS between Bone (light) and Ink (dark) themes. Saves your choice in localStorage so it persists.',
    keywords: 'theme appearance light dark colour',
    perm: null,
    run: () => window.dispatchEvent(new Event('toggle-dark-mode')),
  },
  {
    id: 'pref.apk',
    label: 'Download player APK',
    hint: 'For Android tablets / TV boxes',
    description: 'Direct download of the latest player APK. Pick the modern build for arm64 hardware (most retail tablets); /apk/legacy if you\'re on an older armv7 box like a TX3 Mini.',
    keywords: 'install apk android download tablet',
    perm: null,
    run: () => window.open('/apk', '_blank'),
  },
];

// ── Filter ───────────────────────────────────────────────────
// Each query word must appear somewhere in label / hint /
// description / keywords. Word-AND beats substring matching when
// users type "open scr" expecting "Open a screen" without having
// to type a contiguous substring.
const filterByQuery = (items, query) => {
  const q = query.trim().toLowerCase();
  if (!q) return items;
  const words = q.split(/\s+/);
  return items.filter((it) => {
    const blob = `${it.label} ${it.hint || ''} ${it.description || ''} ${it.keywords || ''}`.toLowerCase();
    return words.every((w) => blob.includes(w));
  });
};

// ── Component ────────────────────────────────────────────────
const CommandPalette = () => {
  const auth = useAuth();
  const user = auth.user;
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const [activeIdx, setActiveIdx] = React.useState(0);
  // null = command stage, otherwise the command we're picking an
  // argument for.
  const [pendingCmd, setPendingCmd] = React.useState(null);
  const [argOptions, setArgOptions] = React.useState([]);
  const [argLoading, setArgLoading] = React.useState(false);
  const inputRef = React.useRef(null);

  // Hotkeys at document scope in the **capture** phase so we fire
  // BEFORE any element-level handler that might consume the key —
  // notably the focused `<video controls>` in the library preview
  // and the on-tablet preview page's stage handlers. The browser
  // wouldn't normally swallow `/`, but a focused video element +
  // Firefox's "find as you type" or a parent <div> that calls
  // stopPropagation could both eat it before the bubbling phase
  // reaches our listener. Capture-phase + document-level wins
  // every time.
  //
  // Cmd/Ctrl-K is a backup hotkey for when `/` is awkward — same
  // suppression rules so typing it into a search input still works
  // as Ctrl-K (some inputs use it for "clear line").
  React.useEffect(() => {
    const inTextField = (el) => {
      if (!el) return false;
      const tag = (el.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return true;
      if (el.isContentEditable) return true;
      return false;
    };
    const onKey = (e) => {
      if (!open && !inTextField(e.target)) {
        if (e.key === '/') {
          // `stopPropagation` keeps the keystroke from bubbling to
          // page-level handlers (e.g. tablet-preview stage routers
          // that listen for digit + slash inputs). `preventDefault`
          // suppresses the browser's built-in find-as-you-type
          // (Firefox) and any other default action.
          e.preventDefault();
          e.stopPropagation();
          setOpen(true);
          setQuery('');
          setActiveIdx(0);
          setPendingCmd(null);
          return;
        }
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
          e.preventDefault();
          e.stopPropagation();
          setOpen(true);
          setQuery('');
          setActiveIdx(0);
          setPendingCmd(null);
          return;
        }
      }
      if (open && e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        if (pendingCmd) {
          // Esc from arg stage backs out to command stage instead
          // of closing the whole palette — saves a keystroke when
          // you picked the wrong command.
          setPendingCmd(null);
          setQuery('');
          setActiveIdx(0);
        } else {
          setOpen(false);
        }
      }
    };
    document.addEventListener('keydown', onKey, true);   // <-- capture phase
    return () => document.removeEventListener('keydown', onKey, true);
  }, [open, pendingCmd]);

  // Focus the input on every open + every stage transition.
  React.useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 0);
  }, [open, pendingCmd]);

  // When a pending command is set, fetch its argument options.
  React.useEffect(() => {
    if (!pendingCmd) { setArgOptions([]); return; }
    let cancelled = false;
    setArgLoading(true);
    argSourceFor(pendingCmd.needs.type).then((opts) => {
      if (!cancelled) {
        setArgOptions(opts);
        setArgLoading(false);
      }
    });
    return () => { cancelled = true; };
  }, [pendingCmd]);

  // Resolved + permission-gated command list.
  const allCommands = React.useMemo(() => buildCommands(), []);
  const permitted = React.useMemo(() => {
    if (!user) return allCommands.filter((c) => !c.perm);
    return allCommands.filter((c) => !c.perm || can(user, c.perm));
  }, [allCommands, user]);

  // Visible items depend on stage.
  const visible = React.useMemo(() => (
    pendingCmd ? filterByQuery(argOptions, query) : filterByQuery(permitted, query)
  ), [pendingCmd, argOptions, permitted, query]);

  // Clamp the cursor when filter shrinks the list.
  React.useEffect(() => {
    if (activeIdx >= visible.length) setActiveIdx(Math.max(0, visible.length - 1));
  }, [visible, activeIdx]);

  const close = () => {
    setOpen(false);
    setQuery('');
    setActiveIdx(0);
    setPendingCmd(null);
  };

  const executeCommand = (cmd) => {
    if (cmd.needs) {
      // Enter the argument stage; don't close the modal.
      setPendingCmd(cmd);
      setQuery('');
      setActiveIdx(0);
      return;
    }
    close();
    try { cmd.run(); }
    catch (e) { showToast(`Couldn't run that: ${e.message}`, 'err'); }
  };

  const executeArg = (option) => {
    const cmd = pendingCmd;
    close();
    try { cmd?.run(option.payload); }
    catch (e) { showToast(`Couldn't run that: ${e.message}`, 'err'); }
  };

  const onListKey = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIdx((i) => Math.min(visible.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIdx((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const cur = visible[activeIdx];
      if (!cur) return;
      if (pendingCmd) executeArg(cur);
      else executeCommand(cur);
    } else if (e.key === 'Backspace' && pendingCmd && query === '') {
      // Backspace at empty query in arg stage backs to command
      // stage — the same gesture browsers use for "back" in
      // address-bar-like inputs.
      e.preventDefault();
      setPendingCmd(null);
      setActiveIdx(0);
    }
  };

  if (!open) return null;

  // Currently-highlighted item (for the help panel).
  const focused = visible[activeIdx];
  const placeholder = pendingCmd
    ? `Search ${pendingCmd.needs.type === 'syncGroup' ? 'sync groups' : pendingCmd.needs.type + 's'}…`
    : "Search commands… (try 'screens', 'upload', 'sync')";

  return (
    <div
      onClick={close}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        background: 'rgba(20, 20, 20, 0.55)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
        paddingTop: '10vh',
      }}>
      <div
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onListKey}
        role="dialog"
        aria-label="Command palette"
        style={{
          width: 'min(680px, 92vw)',
          background: 'var(--bone)',
          border: 'var(--border)',
          borderRadius: 12,
          boxShadow: '0 30px 80px rgba(0,0,0,0.35)',
          overflow: 'hidden',
          display: 'flex', flexDirection: 'column',
          maxHeight: '80vh',
        }}>

        {/* Breadcrumb (stage 2 only) */}
        {pendingCmd && (
          <div style={{
            padding: '10px 16px',
            borderBottom: 'var(--border)',
            display: 'flex', alignItems: 'center', gap: 10,
            fontSize: 12, color: 'var(--ink-3)',
            background: 'var(--ink-9)',
          }}>
            <span
              onClick={() => { setPendingCmd(null); setQuery(''); setActiveIdx(0); }}
              style={{ cursor: 'pointer', color: 'var(--ink-1)', fontWeight: 500 }}>
              {pendingCmd.label}
            </span>
            <span style={{ color: 'var(--ink-5)' }}>›</span>
            <span style={{ color: 'var(--ink-0)', fontWeight: 500 }}>
              {pendingCmd.needs.label}
            </span>
            <span style={{ flex: 1 }} />
            <span style={{ fontSize: 10.5, fontFamily: 'ui-monospace, monospace', color: 'var(--ink-4)' }}>
              ⌫ back
            </span>
          </div>
        )}

        {/* Search input */}
        <div style={{ padding: '14px 16px', borderBottom: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ color: 'var(--ink-4)', display: 'inline-flex' }}>
            <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <circle cx="9" cy="9" r="6" /><path d="M14 14l4 4" />
            </svg>
          </span>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => { setQuery(e.target.value); setActiveIdx(0); }}
            placeholder={placeholder}
            style={{
              flex: 1, border: 'none', outline: 'none',
              background: 'transparent', color: 'var(--ink-0)',
              fontSize: 15, fontFamily: 'inherit',
            }}
          />
          <span style={{ fontSize: 11, color: 'var(--ink-4)', fontFamily: 'ui-monospace, monospace' }}>esc</span>
        </div>

        {/* Results */}
        <div style={{ flex: 1, overflow: 'auto', padding: 6, minHeight: 0 }}>
          {argLoading ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
              Loading…
            </div>
          ) : visible.length === 0 ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
              {query
                ? `No ${pendingCmd ? 'options' : 'commands'} match "${query}".`
                : (pendingCmd ? 'Nothing to pick — none registered yet.' : 'No commands available.')}
            </div>
          ) : (
            visible.map((item, i) => {
              const active = i === activeIdx;
              return (
                <div
                  key={item.id}
                  onMouseEnter={() => setActiveIdx(i)}
                  onClick={() => pendingCmd ? executeArg(item) : executeCommand(item)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 12,
                    padding: '10px 12px', borderRadius: 8,
                    background: active ? 'var(--ink-9)' : 'transparent',
                    cursor: 'pointer',
                  }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--ink-0)' }}>
                      {item.label}
                    </div>
                    {item.hint && (
                      <div style={{ fontSize: 11.5, color: 'var(--ink-4)', marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {item.hint}
                      </div>
                    )}
                  </div>
                  {item.needs && (
                    <span style={{ fontSize: 10, fontFamily: 'ui-monospace, monospace', color: 'var(--ink-4)', padding: '2px 6px', border: 'var(--border)', borderRadius: 4 }}>
                      needs {item.needs.type}
                    </span>
                  )}
                  {active && (
                    <span style={{ fontSize: 10.5, fontFamily: 'ui-monospace, monospace', color: 'var(--ink-4)', flexShrink: 0 }}>
                      ↵
                    </span>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Help panel — description of the focused command/argument. */}
        {focused && (focused.description || focused.needs) && (
          <div style={{
            padding: '12px 16px',
            borderTop: 'var(--border)',
            background: 'var(--ink-10)',
            fontSize: 12, color: 'var(--ink-2)', lineHeight: 1.5,
          }}>
            {pendingCmd ? (
              // Arg stage: explain what we're picking.
              <>
                <div style={{ color: 'var(--ink-0)', fontWeight: 500, marginBottom: 3 }}>{focused.label}</div>
                <div>{focused.hint || `Selects ${pendingCmd.needs.type} for "${pendingCmd.label}".`}</div>
              </>
            ) : (
              // Command stage: full description.
              <>
                {focused.description && <div>{focused.description}</div>}
                {focused.needs && (
                  <div style={{ marginTop: 6, color: 'var(--ink-3)' }}>
                    <span style={{ color: 'var(--ink-4)' }}>Needs:</span>{' '}
                    <strong style={{ color: 'var(--ink-1)' }}>{focused.needs.hint || `a ${focused.needs.type}`}</strong>
                    {' — '}you'll pick after pressing ↵.
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* Footer key hints */}
        <div style={{
          padding: '8px 14px',
          borderTop: 'var(--border)',
          display: 'flex', gap: 14,
          fontSize: 11, color: 'var(--ink-4)',
          fontFamily: 'ui-monospace, monospace',
        }}>
          <span>↑ ↓ navigate</span>
          <span>↵ {pendingCmd ? 'run' : (focused?.needs ? 'pick' : 'run')}</span>
          {pendingCmd && <span>⌫ back</span>}
          <span>esc close</span>
          <span style={{ flex: 1 }} />
          <span>/ to open</span>
        </div>
      </div>
    </div>
  );
};

// Mount globally so main.jsx can use it next to <ToastHost />.
window.CommandPalette = CommandPalette;
