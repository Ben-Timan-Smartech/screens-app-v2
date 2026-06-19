/* eslint-disable */
// Content Library — brand view with upload flow as interactive sub-state.

// Friendly aspect-ratio label. Approximates common ratios; falls back to
// raw "WxH" when nothing matches.
const aspectLabel = (w, h) => {
  if (!w || !h) return null;
  const r = w / h;
  const close = (a, b) => Math.abs(a - b) < 0.02;
  if (close(r, 16 / 9))  return '16:9';
  if (close(r, 9 / 16))  return '9:16';
  if (close(r, 4 / 3))   return '4:3';
  if (close(r, 3 / 4))   return '3:4';
  if (close(r, 1))       return '1:1';
  if (close(r, 21 / 9))  return '21:9';
  return null;
};

// Small standardisation so common dimensions render with the canonical label
// (e.g. 1920x1080 → "1080p"). Anything non-canonical falls through to raw px.
const dimensionLabel = (w, h) => {
  if (!w || !h) return null;
  if (w === 1920 && h === 1080) return '1080p';
  if (w === 1080 && h === 1920) return '1080p · vertical';
  if (w === 1280 && h === 720)  return '720p';
  if (w === 3840 && h === 2160) return '4K';
  return `${w}×${h}`;
};

// ─────────────────────────────────────────────────────────────
// Video tile — real first-frame thumbnail + click-to-preview.
//
// Behaviour:
//   • Click the thumbnail (or play button) → open preview modal.
//   • Click anywhere below the thumbnail (or the checkmark) → toggle selection.
// Falls back to the generated text thumbnail if the video has no mediaUrl
// (legacy mock data).
// ─────────────────────────────────────────────────────────────
const VideoTile = ({ v, selected, onToggle, onPreview }) => {
  const [hover, setHover] = React.useState(false);
  // Prefer server-emitted dimensions (from scan-videos.py's MP4 atom parser);
  // fall back to browser probe via loadedmetadata if absent for some reason.
  const initialDims = (v.width && v.height) ? { w: v.width, h: v.height } : null;
  const [dims, setDims] = React.useState(initialDims);
  // Lazy-load: don't create the <video> element (and the network request it
  // implies) until the tile is in or near the viewport. Hundreds of
  // simultaneous video metadata fetches is what was killing the page.
  const [inView, setInView] = React.useState(false);
  const tileRef = React.useRef(null);
  const videoRef = React.useRef(null);
  const showCheck = selected || hover;
  const hasMedia = !!v.mediaUrl;

  React.useEffect(() => {
    if (!tileRef.current) return;
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          obs.disconnect();   // load once; once visible, keep it.
        }
      },
      { rootMargin: '200px' },   // start loading shortly before the tile scrolls in
    );
    obs.observe(tileRef.current);
    return () => obs.disconnect();
  }, []);

  React.useEffect(() => {
    if (!hasMedia || !videoRef.current) return;
    const el = videoRef.current;
    const onLoaded = () => {
      try { el.currentTime = 0.1; } catch (_) {}
      if (!dims && el.videoWidth && el.videoHeight) {
        const next = { w: el.videoWidth, h: el.videoHeight };
        setDims(next);
        v._dims = next;
      }
    };
    el.addEventListener('loadedmetadata', onLoaded);
    return () => el.removeEventListener('loadedmetadata', onLoaded);
  }, [hasMedia, dims, inView]);

  return (
    <div
      ref={tileRef}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        position: 'relative',
        border: selected ? '1.5px solid var(--ink-1)' : 'var(--border)',
        borderRadius: 8, padding: 6,
        background: 'var(--ink-10)',
        transform: hover && !selected ? 'translateY(-1px)' : 'none',
        transition: 'transform .12s, border-color .12s',
      }}>
      {/* Thumbnail area — click opens preview */}
      <div
        onClick={(e) => { e.stopPropagation(); onPreview && onPreview(v); }}
        style={{ position: 'relative', cursor: hasMedia ? 'pointer' : 'default', borderRadius: 8, overflow: 'hidden', aspectRatio: '16/9', background: '#0a0a0a' }}>
        {/* v0.1.56: dropped the inline `<video preload=metadata>` —
            it kept hitting the Drive proxy from every visible tile,
            which is heavy on slow links and rendered black more often
            than it rendered a real first-frame. Generated thumbnail
            is cheap and always renders correctly. Click still opens
            the detail panel where the operator can open the file in
            Drive natively. */}
        <Thumbnail title={v.title} brand={v.brand} duration={v.duration} />

        {/* Play overlay. Icon color is hardcoded #141414 (not var(--ink-0))
            so the dark-mode token flip doesn't render a white play
            icon on the white circle background. */}
        {hasMedia && hover && (
          <div style={{
            position: 'absolute', inset: 0,
            background: 'rgba(0,0,0,0.25)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            transition: 'background .12s',
          }}>
            <div style={{
              width: 48, height: 48, borderRadius: '50%',
              background: 'rgba(255,255,255,0.92)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: '#141414',
            }}>
              <Icon.play size={20} />
            </div>
          </div>
        )}

        {/* Selection checkbox — separate hit target */}
        {showCheck && (
          <div
            onClick={(e) => { e.stopPropagation(); onToggle && onToggle(); }}
            style={{
              position: 'absolute', top: 8, left: 8,
              width: 22, height: 22, borderRadius: 4,
              background: selected ? 'var(--ink-0)' : 'rgba(255,255,255,0.92)',
              border: selected ? 'none' : '1.5px solid rgba(255,255,255,0.92)',
              color: selected ? 'var(--on-accent)' : 'transparent',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer',
            }}>
            {selected && <Icon.check size={13} />}
          </div>
        )}

        {/* Duration badge */}
        {v.duration && (
          <div style={{ position: 'absolute', bottom: 6, right: 6,
            background: 'rgba(0,0,0,0.5)', color: '#fff',
            fontSize: 10, padding: '1px 5px', borderRadius: 3,
            fontVariantNumeric: 'tabular-nums', fontWeight: 500,
          }}>{v.duration}</div>
        )}
      </div>

      {/* Metadata — clicking here selects */}
      <div onClick={(e) => { e.stopPropagation(); onToggle && onToggle(); }} style={{ padding: '8px 4px 2px', cursor: 'pointer' }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)', marginBottom: 4, lineHeight: 1.3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</div>
        <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', overflow: 'hidden' }}>
          <Chip>{v.brand}</Chip>
          {dims && (
            <Chip tone="outline" style={{ fontFamily: 'var(--font-mono)' }}>
              {dimensionLabel(dims.w, dims.h)}
            </Chip>
          )}
        </div>
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// AddContentModal — popup version of the library used from the Screen
// detail page. Shows the brand list + video grid inside a modal so the
// user can pick content without leaving the screen they're on. On
// confirm, appends to the target screen and closes; the screen detail
// stays put and the playlist list updates within ~3 s on the next live
// poll. Reuses VideoTile for visual parity with the full library page.
// ─────────────────────────────────────────────────────────────
const AddContentModal = ({ targetDeviceId, targetName, onClose }) => {
  const [activeBrand, setActiveBrand] = React.useState('all');
  const [selected, setSelected] = React.useState(new Set());
  const [previewVideo, setPreviewVideo] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const [brandQuery, setBrandQuery] = React.useState('');
  // v0.1.57: on mobile the 220 px brand rail eats most of the viewport.
  // Collapse it to a horizontal chip strip above the video grid, and
  // pick brands from a select dropdown instead of the side-rail list.
  const vp = useViewport();
  const isMobile = vp.tier === 'mobile';

  const isAll = activeBrand === 'all';
  const brand = isAll ? null : MOCK_BRANDS.find(b => b.id === activeBrand);
  const filteredByBrand = isAll ? MOCK_VIDEOS : MOCK_VIDEOS.filter(v => v.brand === brand?.name);
  const visibleVideos = query
    ? filteredByBrand.filter(v => v.title.toLowerCase().includes(query.toLowerCase()))
    : filteredByBrand;

  const toggle = (id) => {
    const n = new Set(selected);
    n.has(id) ? n.delete(id) : n.add(id);
    setSelected(n);
  };

  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const confirm = async () => {
    if (selected.size === 0 || busy) return;
    setBusy(true);
    try {
      const chosen = MOCK_VIDEOS.filter((v) => selected.has(v.id));
      await setScreenPlaylist(targetDeviceId, chosen, 'append');
      showToast(`Added ${chosen.length} to ${targetName}`, 'ok');
      onClose(true);
    } catch (e) {
      showToast(`Add failed: ${e.message}`, 'err');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div onClick={() => onClose(false)} style={{
      position: 'absolute', inset: 0, zIndex: 30,
      background: 'rgba(9,9,11,0.4)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
    }}>
      <div onClick={(e) => e.stopPropagation()} className="scr-modal-panel" style={{
        width: 'min(1080px, 92%)', height: 'min(720px, 90%)',
        background: 'var(--ink-10)', borderRadius: 14, border: 'var(--border)',
        display: 'flex', flexDirection: 'column',
        boxShadow: '0 24px 64px rgba(9,9,11,0.24)',
        overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', padding: '14px 20px', borderBottom: 'var(--border)' }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)' }}>Add content</div>
            <div style={{ fontSize: 12, color: 'var(--ink-4)', marginTop: 2 }}>Adding to <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>{targetName}</span></div>
          </div>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={() => onClose(false)} />
        </div>

        {/* Body — split: brand rail | grid. On mobile the rail
            collapses to a Brand <select> at the top of the grid
            area. */}
        <div style={{ flex: 1, display: 'flex', minHeight: 0, flexDirection: isMobile ? 'column' : 'row' }}>
          {!isMobile && (
            <div style={{ width: 220, borderRight: 'var(--border)', padding: '14px 10px', background: 'var(--ink-9)', overflow: 'auto', flexShrink: 0 }}>
              <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '2px 10px 6px' }}>Brands</div>
              <div style={{ padding: '0 4px 8px' }}>
                <Input
                  placeholder="Filter brands…"
                  value={brandQuery}
                  onChange={(e) => setBrandQuery(e.target.value)}
                  leadingIcon={<Icon.search size={12} />}
                  size="sm"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                {!brandQuery && (
                  <BrandNavRow brand={{ id: 'all', name: 'All', videos: MOCK_VIDEOS.length }} active={isAll} onClick={() => setActiveBrand('all')} />
                )}
                {MOCK_BRANDS
                  .filter(b => !brandQuery || b.name.toLowerCase().includes(brandQuery.toLowerCase()))
                  .map(b => <BrandNavRow key={b.id} brand={b} active={b.id === activeBrand} onClick={() => setActiveBrand(b.id)} />)}
              </div>
            </div>
          )}

          <div style={{ flex: 1, minWidth: 0, overflow: 'auto', padding: isMobile ? '12px 14px 24px' : '16px 18px 24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' }}>
              {isMobile && (
                <select
                  value={activeBrand}
                  onChange={(e) => setActiveBrand(e.target.value)}
                  style={{
                    flex: '0 0 auto', minHeight: 32, padding: '4px 10px',
                    border: 'var(--border)', borderRadius: 6,
                    background: 'var(--ink-10)', color: 'var(--ink-1)',
                    fontSize: 12, maxWidth: '100%',
                  }}
                >
                  <option value="all">All brands ({MOCK_VIDEOS.length})</option>
                  {MOCK_BRANDS.map(b => (
                    <option key={b.id} value={b.id}>{b.name} ({b.videos})</option>
                  ))}
                </select>
              )}
              <Input placeholder={isAll ? 'Search videos…' : `Search ${brand?.name || ''}…`} leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 140, maxWidth: 280 }} value={query} onChange={(e) => setQuery(e.target.value)} />
              {!isMobile && <span style={{ flex: 1 }} />}
              <span style={{ fontSize: 12, color: 'var(--ink-4)' }}>{visibleVideos.length} video{visibleVideos.length === 1 ? '' : 's'}</span>
            </div>
            {visibleVideos.length === 0 ? (
              <div style={{ padding: '40px 16px', textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
                No videos match.
              </div>
            ) : (
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))',
                gap: 10,
              }}>
                {visibleVideos.map(v => (
                  <VideoTile key={v.id} v={v}
                    selected={selected.has(v.id)}
                    onToggle={() => toggle(v.id)}
                    onPreview={(vid) => setPreviewVideo(vid)} />
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Footer — v0.1.57: drop the target name from the primary
            button on mobile (it can easily exceed the viewport width
            for screens like "Toronto Yorkville #2"); selection count
            is enough. */}
        <div style={{ padding: isMobile ? '12px 14px' : '14px 20px', borderTop: 'var(--border)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ flex: 1, fontSize: 12, color: 'var(--ink-4)', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            <span className="tnum" style={{ color: 'var(--ink-1)', fontWeight: 500 }}>{selected.size}</span> selected
          </div>
          <Button variant="secondary" size="sm" onClick={() => onClose(false)}>Cancel</Button>
          <Button variant="primary" size="sm" disabled={selected.size === 0 || busy} onClick={confirm} icon={<Icon.arrowR size={12} />}>
            {busy ? 'Adding…' : isMobile ? `Add${selected.size ? ` (${selected.size})` : ''}` : `Add ${selected.size} to ${targetName}`}
          </Button>
        </div>

        {/* Preview overlay sits inside the modal so ESC still works. */}
        <PreviewModal video={previewVideo} onClose={() => setPreviewVideo(null)} />
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// VideoListRow — single-line row used by the list view.
// Tighter than VideoTile: 60×34 thumbnail, title, brand chip, optional
// product/dims, "on N screens", and the Push selection checkbox at the
// far right. Same selection semantics as VideoTile.
// ─────────────────────────────────────────────────────────────
// v0.1.56: list view row uses a clear column layout instead of the
// previous bullet-separated inline string. Operators want to scan
// resolution / length / size as columns. The hover-video thumbnail
// is gone — it was the source of the bad-preview problem (a flaky
// Drive stream playing in a 60×34 box mostly produced black). Click
// the row opens the detail panel instead.
const VideoListRow = ({ v, selected, onToggle, onPreview, divider }) => {
  const initialDims = (v.width && v.height) ? { w: v.width, h: v.height } : null;
  const lengthLabel = (() => {
    if (!v.durationSec) return '—';
    const s = Math.round(v.durationSec);
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  })();
  return (
    <div
      onClick={(e) => { e.stopPropagation(); onPreview && onPreview(v); }}
      style={{
        display: 'grid',
        gridTemplateColumns: '60px 1fr 110px 70px 80px 28px',
        alignItems: 'center', gap: 12,
        padding: '10px 12px',
        borderBottom: divider ? 'var(--border-faint)' : 'none',
        background: selected ? 'var(--ink-8)' : 'transparent',
        cursor: 'pointer',
      }}>
      <div style={{ width: 60, height: 34, borderRadius: 4, overflow: 'hidden', flexShrink: 0, background: '#0a0a0a' }}>
        <Thumbnail title={v.title} brand={v.brand} aspect="16/9" size="sm" />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.title}</span>
          {/* v0.1.64/65: tm:rw status. Orphan = in the Drive folder but
              not registered. Pending = registered in the asset manager
              but not in the Drive folder yet, so not pushable. We only
              flag the exceptions so a healthy list isn't noisy. */}
          {v.pendingSync ? (
            <span style={{ flexShrink: 0, fontSize: 9, fontWeight: 500, color: 'var(--info)', background: 'var(--info-bg)', padding: '1px 5px', borderRadius: 3, textTransform: 'uppercase', letterSpacing: 0.4 }} title="Registered in the asset manager but not in the Drive folder yet — can't push until it syncs">Pending</span>
          ) : v.tmrwAssigned === false && (
            <span style={{ flexShrink: 0, fontSize: 9, fontWeight: 500, color: 'var(--warn)', background: 'var(--warn-bg)', padding: '1px 5px', borderRadius: 3, textTransform: 'uppercase', letterSpacing: 0.4 }}>Orphan</span>
          )}
        </div>
        <div style={{ fontSize: 11, color: 'var(--ink-4)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {v.brand}{(v.productLine || v.product) ? ` · ${v.productLine || v.product}` : ''}
        </div>
      </div>
      <div className="tnum" style={{ fontSize: 12, color: 'var(--ink-3)' }}>
        {initialDims ? dimensionLabel(initialDims.w, initialDims.h) : '—'}
      </div>
      <div className="tnum" style={{ fontSize: 12, color: 'var(--ink-3)', textAlign: 'right' }}>
        {lengthLabel}
      </div>
      <div className="tnum" style={{ fontSize: 12, color: 'var(--ink-3)', textAlign: 'right' }}>
        {v.sizeMb ? `${v.sizeMb} MB` : '—'}
      </div>
      <div
        onClick={(e) => { e.stopPropagation(); onToggle && onToggle(); }}
        style={{
          width: 18, height: 18, borderRadius: 4,
          background: selected ? 'var(--ink-0)' : 'var(--ink-10)',
          border: selected ? 'none' : '1.5px solid var(--ink-6)',
          color: selected ? 'var(--on-accent)' : 'transparent',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0, justifySelf: 'end',
        }}>
        {selected && <Icon.check size={11} />}
      </div>
    </div>
  );
};

// v0.1.56: column header bar shown above the list-view rows.
const VideoListHeader = () => (
  <div style={{
    display: 'grid',
    gridTemplateColumns: '60px 1fr 110px 70px 80px 28px',
    gap: 12, padding: '8px 12px',
    borderBottom: 'var(--border)',
    background: 'var(--ink-9)',
    fontSize: 10, fontWeight: 500,
    color: 'var(--ink-4)', letterSpacing: 0.5, textTransform: 'uppercase',
  }}>
    <span />
    <span>Title</span>
    <span>Resolution</span>
    <span style={{ textAlign: 'right' }}>Length</span>
    <span style={{ textAlign: 'right' }}>Size</span>
    <span />
  </div>
);

// ─────────────────────────────────────────────────────────────
// Preview modal — full-bleed video player with native controls.
// ESC closes; clicking the dark backdrop closes; close button top-right.
// ─────────────────────────────────────────────────────────────
const PreviewModal = ({ video, onClose }) => {
  // Prefer server-emitted, then cached browser probe, then null.
  const initialDims = video
    ? (video.width && video.height
        ? { w: video.width, h: video.height }
        : video._dims || null)
    : null;
  const [dims, setDims] = React.useState(initialDims);
  // Per-video "default to unmute" flag. Reads from MOCK_VIDEOS so it
  // reflects whatever /api/library last returned; flipping the toggle
  // PATCHes the server and optimistically mutates the in-memory entry.
  const [defaultUnmute, setDefaultUnmute] = React.useState(!!video?.defaultUnmute);
  const [unmuteBusy, setUnmuteBusy] = React.useState(false);
  React.useEffect(() => { setDefaultUnmute(!!video?.defaultUnmute); }, [video]);
  const onToggleUnmute = async (next) => {
    if (!video || unmuteBusy) return;
    setUnmuteBusy(true);
    const prev = defaultUnmute;
    setDefaultUnmute(next);
    try {
      await setVideoDefaultUnmute(video.id, next);
      // Optimistic in-memory update so the change is visible without
      // waiting for the next /api/library poll.
      video.defaultUnmute = next;
      showToast(
        next ? 'Will play with sound by default' : 'Reverted to muted by default',
        'ok',
      );
    } catch (e) {
      setDefaultUnmute(prev);
      showToast(`Failed: ${e.message}`, 'err');
    } finally {
      setUnmuteBusy(false);
    }
  };
  const videoRef = React.useRef(null);

  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  React.useEffect(() => {
    if (!video) return setDims(null);
    if (video.width && video.height) setDims({ w: video.width, h: video.height });
    else setDims(video._dims || null);
  }, [video]);

  // If the tile didn't capture dims yet (modal opened before metadata
  // load finished), grab them off the modal's own player.
  React.useEffect(() => {
    if (dims || !videoRef.current) return;
    const el = videoRef.current;
    const onLoaded = () => {
      if (el.videoWidth && el.videoHeight) {
        const next = { w: el.videoWidth, h: el.videoHeight };
        setDims(next);
        if (video) video._dims = next;
      }
    };
    el.addEventListener('loadedmetadata', onLoaded);
    return () => el.removeEventListener('loadedmetadata', onLoaded);
  }, [dims, video]);

  if (!video) return null;
  return (
    <div
      onClick={onClose}
      style={{
        position: 'absolute', inset: 0, zIndex: 30,
        background: 'rgba(9,9,11,0.72)', backdropFilter: 'blur(4px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}>
      <div
        onClick={(e) => e.stopPropagation()}
        className="scr-modal-panel"
        style={{
          width: 'min(960px, 90%)', maxHeight: '90%',
          background: 'var(--ink-10)', border: 'var(--border)', borderRadius: 12,
          overflow: 'hidden', display: 'flex', flexDirection: 'column',
          boxShadow: '0 24px 64px rgba(0,0,0,0.5)',
        }}>
        <div style={{ display: 'flex', alignItems: 'center', padding: '12px 16px', borderBottom: 'var(--border)' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{video.title}</div>
            <div style={{ fontSize: 11, color: 'var(--ink-4)' }}>
              <Chip style={{ marginRight: 6 }}>{video.brand}</Chip>
              {video.product && <Chip style={{ marginRight: 6 }}>{video.product}</Chip>}
              {dims && (
                <Chip tone="outline" style={{ marginRight: 6, fontFamily: 'var(--font-mono)' }}>
                  {dimensionLabel(dims.w, dims.h)}
                  {aspectLabel(dims.w, dims.h) && ` · ${aspectLabel(dims.w, dims.h)}`}
                </Chip>
              )}
              <span className="tnum">{video.sizeMb ? `${video.sizeMb} MB · ` : ''}{video.filename || ''}</span>
            </div>
          </div>
          <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} />
        </div>
        {/* v0.1.56: in-page <video> playback dropped. Streaming
            through the Drive proxy in a 1080p modal was flaky on most
            office wifi setups — the buffer-then-stall behaviour made
            the preview feel broken. Replaced with a metadata sheet
            plus a "Open in Drive" button so the operator can confirm
            the right file is in the library and view it natively in
            Drive if they need to. */}
        {(() => {
          // mediaUrl is "/media/<driveId>" for Drive-synced videos,
          // "/uploaded/<filename>" for direct CMS uploads. Only the
          // Drive case has an external link to surface.
          const driveId = video.mediaUrl?.startsWith('/media/')
            ? decodeURIComponent(video.mediaUrl.slice('/media/'.length))
            : null;
          const driveLink = driveId && !driveId.includes('/')
            ? `https://drive.google.com/file/d/${driveId}/view`
            : null;
          const isUpload = video.mediaUrl?.startsWith('/uploaded/');
          return (
            <div style={{ padding: '20px 20px 24px', display: 'flex', flexDirection: 'column', gap: 16 }}>
              {/* Big thumbnail of the brand mark instead of a player. */}
              <div style={{
                width: '100%', aspectRatio: '16 / 9',
                background: 'var(--ink-9)', borderRadius: 8,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: 'var(--ink-3)', fontSize: 13, gap: 10,
                position: 'relative', overflow: 'hidden',
              }}>
                <Thumbnail title={video.title} brand={video.brand} aspect="16/9" size="lg" />
              </div>

              {/* Two-column property sheet. */}
              <div style={{
                display: 'grid', gridTemplateColumns: '120px 1fr', gap: '10px 16px',
                fontSize: 13,
              }}>
                <span style={{ color: 'var(--ink-4)' }}>Filename</span>
                <span style={{ color: 'var(--ink-2)', fontFamily: 'var(--font-mono)', wordBreak: 'break-all' }}>
                  {video.filename || '—'}
                </span>
                <span style={{ color: 'var(--ink-4)' }}>Resolution</span>
                <span className="tnum" style={{ color: 'var(--ink-2)' }}>
                  {dims ? `${dimensionLabel(dims.w, dims.h)}${aspectLabel(dims.w, dims.h) ? ` · ${aspectLabel(dims.w, dims.h)}` : ''}` : 'Unknown'}
                </span>
                <span style={{ color: 'var(--ink-4)' }}>Length</span>
                <span className="tnum" style={{ color: 'var(--ink-2)' }}>
                  {video.durationSec
                    ? `${Math.floor(video.durationSec / 60)}:${String(Math.round(video.durationSec % 60)).padStart(2, '0')}`
                    : 'Unknown'}
                </span>
                <span style={{ color: 'var(--ink-4)' }}>File size</span>
                <span className="tnum" style={{ color: 'var(--ink-2)' }}>
                  {video.sizeMb ? `${video.sizeMb} MB` : 'Unknown'}
                </span>
                <span style={{ color: 'var(--ink-4)' }}>Source</span>
                <span style={{ color: 'var(--ink-2)' }}>
                  {isUpload ? 'Uploaded directly to CMS' : driveId ? 'Google Drive' : 'Unknown'}
                </span>
              </div>

              {driveLink && (
                <a href={driveLink} target="_blank" rel="noopener noreferrer"
                   style={{
                     alignSelf: 'flex-start',
                     display: 'inline-flex', alignItems: 'center', gap: 8,
                     padding: '8px 14px', borderRadius: 6,
                     background: 'var(--ink-0)', color: 'var(--on-accent)',
                     fontSize: 13, fontWeight: 500, textDecoration: 'none',
                   }}>
                  <Icon.drive size={14} />
                  <span>Open in Google Drive</span>
                </a>
              )}
            </div>
          );
        })()}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '12px 16px', borderTop: 'var(--border)',
          background: 'var(--ink-9)',
        }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink-1)' }}>Default to unmute</div>
            <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 2 }}>
              When the screen is muted, this video still plays with sound.
            </div>
          </div>
          <button
            disabled={unmuteBusy}
            onClick={() => onToggleUnmute(!defaultUnmute)}
            style={{
              position: 'relative',
              width: 38, height: 22, borderRadius: 999,
              background: defaultUnmute ? 'var(--ink-0)' : 'var(--ink-6)',
              transition: 'background .15s',
              cursor: unmuteBusy ? 'wait' : 'pointer',
              opacity: unmuteBusy ? 0.6 : 1,
            }}>
            <span style={{
              position: 'absolute', top: 2, left: defaultUnmute ? 18 : 2,
              width: 18, height: 18, borderRadius: '50%',
              background: '#fff', transition: 'left .15s',
              boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
            }} />
          </button>
        </div>
      </div>
    </div>
  );
};

const BrandNavRow = ({ brand, active, onClick }) => (
  <button onClick={onClick} style={{
    display: 'flex', alignItems: 'center', gap: 10,
    width: '100%', padding: '7px 10px', borderRadius: 6,
    background: active ? 'var(--ink-8)' : 'transparent',
    textAlign: 'left',
  }}>
    <BrandMark brand={brand.name} size={18} logoUrl={brand.logoUrl} />
    <span style={{ flex: 1, fontSize: 13, fontWeight: active ? 500 : 400, color: active ? 'var(--ink-1)' : 'var(--ink-2)' }}>{brand.name}</span>
    <span className="tnum" style={{ fontSize: 11, color: 'var(--ink-4)' }}>{brand.videos}</span>
  </button>
);

// v0.1.64: product-line row in the brand's Products rail. Shows the
// line name, total count, and (when some are active in tm:rw) a small
// "N active" hint. `tone="warn"` styles the Orphans entry.
const ProductNavRow = ({ label, count, activeCount = 0, active, onClick, tone, title }) => (
  <button onClick={onClick} title={title} style={{
    display: 'flex', alignItems: 'center', gap: 8,
    width: '100%', padding: '6px 10px', borderRadius: 6,
    background: active ? 'var(--ink-8)' : 'transparent',
    textAlign: 'left', fontSize: 12,
    fontWeight: active ? 500 : 400,
    color: tone === 'warn' ? 'var(--warn)' : active ? 'var(--ink-1)' : 'var(--ink-3)',
  }}>
    <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</span>
    {activeCount > 0 && (
      <span className="tnum" style={{ fontSize: 10, color: 'var(--ok)' }}>{activeCount} active</span>
    )}
    <span className="tnum" style={{ color: 'var(--ink-4)' }}>{count}</span>
  </button>
);

// Upload panel — slide-over on the right when open. v0.1.20: real
// upload, replacing the v0.1.6 "Coming soon" placeholder. Posts
// multipart/form-data to /api/library/upload, streams progress via
// XHR upload events, and triggers a library-refresh so the new
// video appears in the grid immediately instead of waiting for the
// 60-second useLibrary tick.
const UploadPanel = ({ open, onClose }) => {
  const vp = useViewport();
  const compact = vp.isCompact;
  // Phone / small tablet: full-width sheet that takes the whole pane.
  // Laptop+: 420 px slide-over from the right.
  const panelWidth = compact ? '100%' : 420;

  // Form state
  const [file, setFile] = React.useState(null);
  const [brand, setBrand] = React.useState('');
  const [title, setTitle] = React.useState('');
  const [product, setProduct] = React.useState('');
  // Upload state
  const [uploading, setUploading] = React.useState(false);
  const [progress, setProgress] = React.useState(0);    // 0..1
  const [error, setError] = React.useState(null);
  const inflightRef = React.useRef(null);
  const fileInputRef = React.useRef(null);

  // Brand options sourced from the live library. Falls back to the
  // bare MOCK_BRANDS array if useLibrary hasn't synced yet. New
  // brands can still be created from the typed-text field below the
  // dropdown — useful when uploading the first asset for an asset-
  // free brand.
  const brands = (window.MOCK_BRANDS || []).map(b => ({
    id: b.id || b.name,
    name: b.name || b.id,
  }));

  // Reset state when the panel closes so re-opening starts fresh.
  React.useEffect(() => {
    if (!open) {
      if (inflightRef.current) try { inflightRef.current.abort(); } catch (_) {}
      inflightRef.current = null;
      setFile(null); setBrand(''); setTitle(''); setProduct('');
      setUploading(false); setProgress(0); setError(null);
    }
  }, [open]);

  const onPickFile = (f) => {
    setFile(f);
    setError(null);
    // Default the title from the filename stem if the user hasn't
    // typed something. They can still edit afterwards.
    if (f && !title) {
      const stem = f.name.replace(/\.[^.]+$/, '');
      setTitle(stem);
    }
  };

  const handleSubmit = async () => {
    if (!file || !brand) {
      setError(!file ? 'Pick a video file first.' : 'Pick or type a brand.');
      return;
    }
    setUploading(true);
    setProgress(0);
    setError(null);
    try {
      const promise = uploadVideo({
        file, brand, title, product,
        onProgress: (frac) => setProgress(frac),
      });
      inflightRef.current = promise;
      const result = await promise;
      // Optimistic insert: hand the new entry to useLibrary in the
      // event detail. It pushes the row into MOCK_VIDEOS straight
      // away and bumps the version counter, so the grid re-renders
      // *immediately* with no /api/library round-trip on the
      // critical path. The next interval tick reconciles with the
      // server. Previously we dispatched a bare event and waited
      // for the round-trip — perceptibly slow on a 450 kB library.
      window.dispatchEvent(new CustomEvent('library-refresh', {
        detail: { video: result?.video },
      }));
      showToast(`Uploaded "${result?.video?.title || file.name}"`, 'ok');
      onClose();
    } catch (e) {
      setError(e.message || 'Upload failed.');
    } finally {
      setUploading(false);
      inflightRef.current = null;
    }
  };

  return (
  <div style={{
    position: 'absolute', top: 0, right: 0, bottom: 0, left: compact && open ? 0 : 'auto',
    width: open ? panelWidth : 0,
    background: 'var(--ink-10)', borderLeft: open && !compact ? 'var(--border)' : 'none',
    overflow: 'hidden',
    transition: 'width .2s ease',
    display: 'flex', flexDirection: 'column',
    zIndex: 25,
  }}>
    <div style={{ width: panelWidth, maxWidth: '100%', display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '14px 20px', borderBottom: 'var(--border)' }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--ink-1)' }}>Upload video</div>
          <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 1 }}>
            MP4 / MOV / WebM · up to 1 GB
          </div>
        </div>
        <Button variant="ghost" size="sm" icon={<Icon.close size={14} />} onClick={onClose} disabled={uploading} />
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 20, display: 'flex', flexDirection: 'column', gap: 14 }}>
        {/* File picker — visual dropzone with hidden native input. */}
        <div
          onClick={() => !uploading && fileInputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); }}
          onDrop={(e) => {
            e.preventDefault();
            if (uploading) return;
            const f = e.dataTransfer?.files?.[0];
            if (f) onPickFile(f);
          }}
          style={{
            border: '1.5px dashed var(--ink-6)', borderRadius: 10,
            padding: 22, textAlign: 'center',
            background: file ? 'var(--ink-9)' : 'transparent',
            cursor: uploading ? 'not-allowed' : 'pointer',
            opacity: uploading ? 0.6 : 1,
          }}>
          <input
            ref={fileInputRef}
            type="file"
            accept="video/mp4,video/quicktime,video/webm,video/*"
            style={{ display: 'none' }}
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) onPickFile(f);
            }}
          />
          {file ? (
            <>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)', wordBreak: 'break-all' }}>
                {file.name}
              </div>
              <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 4 }}>
                {(file.size / 1_000_000).toFixed(1)} MB · click to change
              </div>
            </>
          ) : (
            <>
              <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--ink-1)' }}>
                Drop a video here, or click to browse
              </div>
              <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 4 }}>
                MP4 / MOV / WebM / MKV
              </div>
            </>
          )}
        </div>

        {/* Brand — dropdown of known brands, plus a free-type field
            so the operator can create one on the fly for a casual
            upload. Server auto-registers any new brand id. */}
        <div>
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-2)', marginBottom: 6 }}>
            Brand
          </div>
          {brands.length > 0 && (
            <select
              value={brand}
              onChange={(e) => setBrand(e.target.value)}
              disabled={uploading}
              style={{
                width: '100%', padding: '8px 10px', fontSize: 13,
                border: 'var(--border)', borderRadius: 6,
                background: 'var(--ink-10)', color: 'var(--ink-1)',
                marginBottom: 6,
              }}>
              <option value="">— pick a brand —</option>
              {brands.map(b => (
                <option key={b.id} value={b.id}>{b.name}</option>
              ))}
              <option value="__new__">+ Or type a new brand below</option>
            </select>
          )}
          <Input
            placeholder={brands.length ? 'Or type a brand id (lowercase, no spaces)' : 'Brand id (e.g. sonos)'}
            value={brand === '__new__' ? '' : brand}
            onChange={(e) => setBrand(e.target.value.trim().toLowerCase().replace(/\s+/g, '-'))}
            disabled={uploading}
          />
        </div>

        {/* Title — pre-filled from filename, editable. */}
        <div>
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-2)', marginBottom: 6 }}>
            Title
          </div>
          <Input
            placeholder="What this video is called in the library"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            disabled={uploading}
          />
        </div>

        {/* Product — optional sub-category. */}
        <div>
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-2)', marginBottom: 6 }}>
            Product <span style={{ color: 'var(--ink-4)', fontWeight: 400 }}>· optional</span>
          </div>
          <Input
            placeholder="e.g. Arc Ultra, Era 300"
            value={product}
            onChange={(e) => setProduct(e.target.value)}
            disabled={uploading}
          />
        </div>

        {/* Progress + error */}
        {uploading && (
          <div>
            <div style={{ height: 6, borderRadius: 3, background: 'var(--ink-8)', overflow: 'hidden' }}>
              <div style={{
                width: `${Math.round(progress * 100)}%`,
                height: '100%',
                background: 'var(--ink-0)',
                transition: 'width .1s linear',
              }} />
            </div>
            <div style={{ fontSize: 11, color: 'var(--ink-4)', marginTop: 6, textAlign: 'right', fontFamily: 'ui-monospace, monospace' }}>
              {Math.round(progress * 100)}%
            </div>
          </div>
        )}
        {error && (
          <div style={{
            padding: 10, borderRadius: 6,
            background: 'rgba(166, 56, 36, 0.08)',
            color: '#A63824', fontSize: 12,
          }}>
            {error}
          </div>
        )}
      </div>

      {/* Footer: cancel + submit. */}
      <div style={{ padding: '14px 20px', borderTop: 'var(--border)', display: 'flex', gap: 8, alignItems: 'center' }}>
        <div style={{ flex: 1 }} />
        <Button variant="ghost" size="sm" onClick={onClose} disabled={uploading}>
          Cancel
        </Button>
        <Button
          variant="primary" size="sm"
          icon={<Icon.upload size={13} />}
          onClick={handleSubmit}
          disabled={uploading || !file || !brand}>
          {uploading ? 'Uploading…' : 'Upload'}
        </Button>
      </div>
    </div>
  </div>
  );
};

const ContentLibrary = () => {
  // 'all' = show every brand. Otherwise, brand id (e.g. 'sonos').
  const [activeBrand, setActiveBrand] = React.useState('all');
  // v0.1.64: product-line filter within a brand. null = all products;
  // a productLine string filters to that line; '__orphans__' shows
  // only videos tm:rw doesn't recognise as assigned. Driven by the
  // Products rail. Picking a real product line also auto-selects its
  // active videos (see pickProduct).
  const [activeProduct, setActiveProduct] = React.useState(null);
  const [selected, setSelected] = React.useState(new Set());
  const [uploadOpen, setUploadOpen] = React.useState(false);
  // v0.1.27: command palette can fire `open-upload-panel` to pop
  // the panel from anywhere — registered here because the panel
  // state lives in this component. Listener only attaches while
  // the library page is mounted, so dispatching from another
  // page after the palette navigates here works.
  React.useEffect(() => {
    const open = () => setUploadOpen(true);
    window.addEventListener('open-upload-panel', open);
    return () => window.removeEventListener('open-upload-panel', open);
  }, []);
  const [previewVideo, setPreviewVideo] = React.useState(null);
  const [pushPickerOpen, setPushPickerOpen] = React.useState(false);
  // Brand sidebar search + grid/list view toggle + pagination.
  const [brandQuery, setBrandQuery] = React.useState('');
  // v0.1.56: list view is now the default and the choice persists
  // across sessions. List view shows the columns operators actually
  // need (resolution, length, file size) more clearly than the grid.
  const [view, setView] = React.useState(() => {
    try { return localStorage.getItem('library.viewMode') || 'list'; } catch { return 'list'; }
  });
  const setViewMode = (m) => {
    setView(m);
    try { localStorage.setItem('library.viewMode', m); } catch {}
  };
  const [page, setPage] = React.useState(0);
  // Mobile collapses the brand sidebar into a slide-down panel
  // toggled by a chip in the toolbar; this tracks the open/closed
  // state. On tablet+ the sidebar is always visible and this is
  // ignored.
  const [brandsOpenMobile, setBrandsOpenMobile] = React.useState(false);
  const vp = useViewport();
  const compact = vp.isCompact;
  const isMobile = vp.tier === 'mobile';
  const PAGE_SIZE = isMobile ? 12 : 24;

  // When navigated here from a screen detail page (Add content), the URL
  // carries ?target=<deviceId>. In that mode the Push button skips the
  // multi-screen PushPicker and goes straight to /api/screens/<id>/playlist.
  const route = useRoute();
  const targetDeviceId = route.params?.target || null;
  const live = useLiveScreens();
  const targetScreen = targetDeviceId
    ? live.screens?.find((s) => s.deviceId === targetDeviceId)
    : null;

  const toggle = (id) => {
    // v0.1.65: pendingSync videos (assigned in tm:rw but not yet in the
    // Drive folder) have no streamable file, so they can't be pushed —
    // ignore attempts to tick them.
    const vid = MOCK_VIDEOS.find(v => v.id === id);
    if (vid && vid.pendingSync) return;
    const n = new Set(selected);
    n.has(id) ? n.delete(id) : n.add(id);
    setSelected(n);
  };

  const isAll = activeBrand === 'all';
  const brand = isAll ? null : MOCK_BRANDS.find(b => b.id === activeBrand);
  // Videos for the current brand (or all). Product filtering applies on
  // top of this.
  const brandVideos = isAll
    ? MOCK_VIDEOS
    : MOCK_VIDEOS.filter(v => v.brand === brand?.name);
  const totalCount = MOCK_VIDEOS.length;

  // v0.1.69: the brand's video set is sectioned by the tm:rw tags the
  // server merges into each record (tmrwScope / productLine / tmrwActive
  // / tmrwAssigned):
  //   • Brand global — scope "brand": apply to the whole brand.
  //   • Products      — scope "family"/"product", grouped by productLine.
  //   • Orphans       — in Screens/Brand Content/{brand} but unknown to
  //                     the asset manager (tmrwAssigned === false).
  //   • All           — the flat set.
  // Only meaningful inside one brand; the rail hides sections in "All
  // brands".
  const ORPHAN = '__orphans__';
  const BRAND_GLOBAL = '__brand_global__';
  const isBrandGlobal = (v) => v.tmrwAssigned !== false && v.tmrwScope === 'brand';
  const isProductVid = (v) => v.tmrwAssigned !== false && v.tmrwScope !== 'brand' && !!v.productLine;
  const brandGlobalCount = isAll ? 0 : brandVideos.filter(isBrandGlobal).length;
  const productLines = React.useMemo(() => {
    if (isAll) return [];
    const byLine = new Map();
    for (const v of brandVideos) {
      if (!isProductVid(v)) continue;
      const cur = byLine.get(v.productLine) || { name: v.productLine, count: 0, active: 0 };
      cur.count += 1;
      if (v.tmrwActive) cur.active += 1;
      byLine.set(v.productLine, cur);
    }
    return [...byLine.values()].sort((a, b) => a.name.localeCompare(b.name));
  }, [brandVideos, isAll]);
  const orphanCount = isAll ? 0 : brandVideos.filter(v => v.tmrwAssigned === false).length;
  // Whether tm:rw tagging is present at all for this brand. When the
  // asset manager has no videos for the brand yet, every record is an
  // orphan — we soften the empty-state copy in that case rather than
  // implying something's broken.
  const hasTmrwData = !isAll && brandVideos.some(v => v.tmrwAssigned === true);

  const visibleVideos = React.useMemo(() => {
    if (isAll || !activeProduct) return brandVideos;
    if (activeProduct === ORPHAN) return brandVideos.filter(v => v.tmrwAssigned === false);
    if (activeProduct === BRAND_GLOBAL) return brandVideos.filter(isBrandGlobal);
    return brandVideos.filter(v => isProductVid(v) && v.productLine === activeProduct);
  }, [brandVideos, isAll, activeProduct]);

  // Picking a product (or Brand global) filters to it AND auto-selects
  // its active, pushable videos. Picking Orphans / All just filters.
  const pickProduct = (line) => {
    setActiveProduct(line);
    setBrandsOpenMobile(false);
    if (line && line !== ORPHAN) {
      const match = line === BRAND_GLOBAL
        ? isBrandGlobal
        : (v) => isProductVid(v) && v.productLine === line;
      const next = new Set();
      for (const v of brandVideos) {
        // Skip pendingSync — assigned in tm:rw but no streamable file yet.
        if (match(v) && v.tmrwActive && !v.pendingSync) next.add(v.id);
      }
      setSelected(next);
    }
  };

  // Brand sidebar filter — case-insensitive substring on display name.
  const filteredBrands = brandQuery.trim()
    ? MOCK_BRANDS.filter(b => b.name.toLowerCase().includes(brandQuery.toLowerCase()))
    : MOCK_BRANDS;

  // Reset pagination whenever the visible-set changes shape.
  React.useEffect(() => { setPage(0); }, [activeBrand, view, activeProduct]);
  // v0.1.64: switching brand clears the product filter + selection so
  // we don't carry one brand's product line / ticks into another.
  React.useEffect(() => { setActiveProduct(null); setSelected(new Set()); }, [activeBrand]);

  const totalPages = Math.max(1, Math.ceil(visibleVideos.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pagedVideos = visibleVideos.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);
  const startIdx = visibleVideos.length === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const endIdx = Math.min((safePage + 1) * PAGE_SIZE, visibleVideos.length);

  return (
    <AppShell current="library">
      <PageHeader
        crumbs={isAll ? ['Content library', 'All brands'] : ['Content library', brand.name]}
        title={isAll ? 'All brands' : brand.name}
        actions={
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--ink-4)', marginRight: 6 }}>
              <Icon.drive size={13} />
              <span>Drive synced · 2 min ago</span>
            </div>
            {/* Filters button removed in v0.1.6 — filtering wasn't wired up
                and was confusing users. Restore when there's real filter
                logic to expose. */}
            <Button variant="primary" size="sm" icon={<Icon.upload size={13} />} onClick={() => setUploadOpen(true)}>Upload content</Button>
          </>
        }
      />
      {/* Scoped-push banner — appears only when ?target= is in the URL.
          Push button behaviour (below) sends straight to that screen,
          skipping the multi-screen tickbox picker. */}
      {targetDeviceId && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 12,
          padding: '10px 24px', background: 'var(--ink-9)',
          borderBottom: 'var(--border)',
        }}>
          <StatusDot status={targetScreen?.online ? 'online' : 'offline'} />
          <div style={{ flex: 1, fontSize: 12, color: 'var(--ink-2)' }}>
            Adding to <span style={{ fontWeight: 500, color: 'var(--ink-1)' }}>{targetScreen?.name || targetDeviceId}</span>
            {!targetScreen && ' (not currently registered)'}
          </div>
          <Button variant="ghost" size="sm" onClick={() => navigate('/library')}>Push to multiple instead</Button>
        </div>
      )}
      <div style={{ flex: 1, display: 'flex', position: 'relative', minHeight: 0 }}>
        {/* Left pane — brand + product nav. On compact viewports
            (tablet / mobile) this is hidden by default and exposed
            via a "Brands" chip in the toolbar that slides it down
            from the top of the main pane. */}
        <div style={{
          // On compact viewports, render as an overlay slide-down
          // panel instead of a column. On laptop+ it's always
          // visible as a left rail.
          width: compact ? '100%' : 220,
          borderRight: compact ? 'none' : 'var(--border)',
          borderBottom: compact ? 'var(--border)' : 'none',
          padding: '14px 10px', background: 'var(--ink-9)',
          overflow: 'auto', flexShrink: 0,
          display: compact && !brandsOpenMobile ? 'none' : 'block',
          position: compact ? 'absolute' : 'static',
          top: 0, left: 0, right: 0,
          maxHeight: compact ? '60%' : 'none',
          zIndex: compact ? 20 : 'auto',
          boxShadow: compact ? '0 12px 32px -8px rgba(20,20,20,0.18)' : 'none',
        }}>
          <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '2px 10px 6px' }}>Brands</div>
          <div style={{ padding: '0 4px 8px' }}>
            <Input
              placeholder="Filter brands…"
              value={brandQuery}
              onChange={(e) => setBrandQuery(e.target.value)}
              leadingIcon={<Icon.search size={12} />}
              size="sm"
            />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 1, marginBottom: 16 }}>
            {!brandQuery && (
              <BrandNavRow brand={{ id: 'all', name: 'All', videos: totalCount }} active={isAll} onClick={() => { setActiveBrand('all'); setBrandsOpenMobile(false); }} />
            )}
            {filteredBrands.map(b => <BrandNavRow key={b.id} brand={b} active={b.id === activeBrand} onClick={() => { setActiveBrand(b.id); setBrandsOpenMobile(false); }} />)}
            {brandQuery && filteredBrands.length === 0 && (
              <div style={{ padding: '12px 10px', fontSize: 11, color: 'var(--ink-4)', fontStyle: 'italic' }}>
                No brands match "{brandQuery}"
              </div>
            )}
          </div>
          {brand && (
            <>
              {/* v0.1.69: brand content sectioned as Brand global /
                  Products (per product line) / Orphans / All — all
                  derived from the tm:rw asset-manager tags the server
                  merges into each video. */}
              <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '2px 10px 8px' }}>{brand.name}</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                <ProductNavRow
                  label="All" count={brandVideos.length}
                  active={activeProduct === null}
                  onClick={() => { setActiveProduct(null); setBrandsOpenMobile(false); }}
                />
                {brandGlobalCount > 0 && (
                  <ProductNavRow
                    label="Brand global videos" count={brandGlobalCount}
                    title="Brand-scope videos — apply to the whole brand"
                    active={activeProduct === BRAND_GLOBAL}
                    onClick={() => pickProduct(BRAND_GLOBAL)}
                  />
                )}
                {productLines.length > 0 && (
                  <div style={{ fontSize: 10, fontWeight: 500, color: 'var(--ink-4)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '10px 10px 4px' }}>Products</div>
                )}
                {productLines.map(p => (
                  <ProductNavRow
                    key={p.name} label={p.name} count={p.count}
                    activeCount={p.active}
                    active={activeProduct === p.name}
                    onClick={() => pickProduct(p.name)}
                  />
                ))}
                {orphanCount > 0 && (
                  <ProductNavRow
                    label="Orphans" count={orphanCount} tone="warn"
                    title="Files in Screens/Brand Content that aren't matched to any assigned video"
                    active={activeProduct === ORPHAN}
                    onClick={() => pickProduct(ORPHAN)}
                  />
                )}
                {!hasTmrwData && orphanCount > 0 && (
                  <div style={{ fontSize: 10, color: 'var(--ink-4)', padding: '8px 10px 2px', lineHeight: 1.4 }}>
                    No assigned videos in the asset manager for {brand.name} yet — everything shows as an orphan until they're registered.
                  </div>
                )}
              </div>
            </>
          )}
        </div>

        {/* Main grid */}
        <div style={{
          flex: 1, minWidth: 0, overflow: 'auto',
          padding: isMobile ? '14px 12px 100px' : compact ? '16px 18px 100px' : '20px 24px 100px',
        }}>
          <div style={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 10,
            marginBottom: 16,
          }}>
            {/* Brands chip — only on compact viewports. Tapping it
                slides the brand picker down from the top of the
                main pane. Hidden on laptop+ where the sidebar is
                always visible. */}
            {compact && (
              <button
                onClick={() => setBrandsOpenMobile((v) => !v)}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 6,
                  height: 30, padding: '0 12px',
                  border: 'var(--border-strong)', borderRadius: 6,
                  background: brandsOpenMobile ? 'var(--ink-8)' : 'var(--ink-10)',
                  color: 'var(--ink-1)', fontSize: 12, fontWeight: 500,
                  cursor: 'pointer',
                }}>
                <Icon.library size={13} />
                <span>{isAll ? 'All brands' : brand.name}</span>
                <Icon.chevD size={11} />
              </button>
            )}
            <Input placeholder={isAll ? 'Search all videos…' : `Search ${brand?.name || ''} videos…`} leadingIcon={<Icon.search size={13} />} size="sm" style={{ flex: 1, minWidth: 140, maxWidth: 280 }} />
            <span style={{ flex: 1 }} className="scr-mobile-hide" />
            <span style={{ fontSize: 12, color: 'var(--ink-4)' }} className="scr-mobile-hide">{visibleVideos.length} video{visibleVideos.length === 1 ? '' : 's'} · sorted by recent</span>
            <Button
              variant={view === 'grid' ? 'secondary' : 'ghost'}
              size="sm"
              icon={<Icon.grid size={13} />}
              onClick={() => setViewMode('grid')}
              title="Grid view"
            />
            <Button
              variant={view === 'list' ? 'secondary' : 'ghost'}
              size="sm"
              icon={<Icon.list size={13} />}
              onClick={() => setViewMode('list')}
              title="List view"
            />
          </div>
          {visibleVideos.length === 0 ? (
            <div style={{ padding: '40px 16px', textAlign: 'center', color: 'var(--ink-4)', fontSize: 13 }}>
              No videos for this brand yet.
            </div>
          ) : view === 'grid' ? (
            <div style={{
              display: 'grid',
              // Smaller minmax on phones so we get 2 columns rather than
              // 1 huge tile per row. minmax handles the wrap from 2 → 3
              // → 4+ as the viewport grows.
              gridTemplateColumns: isMobile
                ? 'repeat(auto-fill, minmax(140px, 1fr))'
                : 'repeat(auto-fill, minmax(200px, 1fr))',
              gap: isMobile ? 8 : 10,
            }}>
              {pagedVideos.map(v => (
                <VideoTile
                  key={v.id} v={v}
                  selected={selected.has(v.id)}
                  onToggle={() => toggle(v.id)}
                  onPreview={(vid) => setPreviewVideo(vid)}
                />
              ))}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', border: 'var(--border)', borderRadius: 8, overflow: 'hidden', background: 'var(--ink-10)' }}>
              <VideoListHeader />
              {pagedVideos.map((v, i) => (
                <VideoListRow
                  key={v.id} v={v} divider={i < pagedVideos.length - 1}
                  selected={selected.has(v.id)}
                  onToggle={() => toggle(v.id)}
                  onPreview={(vid) => setPreviewVideo(vid)}
                />
              ))}
            </div>
          )}

          {visibleVideos.length > PAGE_SIZE && (
            <div style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '20px 4px 8px',
              fontSize: 12, color: 'var(--ink-4)',
            }}>
              <span className="tnum">
                Showing {startIdx}–{endIdx} of {visibleVideos.length}
              </span>
              <span style={{ flex: 1 }} />
              <Button
                variant="ghost" size="sm"
                icon={<Icon.chevL size={12} />}
                disabled={safePage === 0}
                onClick={() => setPage(p => Math.max(0, p - 1))}>
                Prev
              </Button>
              <span className="tnum" style={{ fontSize: 12, color: 'var(--ink-2)', minWidth: 80, textAlign: 'center' }}>
                Page {safePage + 1} of {totalPages}
              </span>
              <Button
                variant="ghost" size="sm"
                iconRight={<Icon.chevR size={12} />}
                disabled={safePage >= totalPages - 1}
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}>
                Next
              </Button>
            </div>
          )}
        </div>

        {/* Selection action bar */}
        {selected.size > 0 && (
          <div style={{
            position: 'absolute', bottom: 20, left: '50%', transform: 'translateX(-50%)',
            background: 'var(--ink-0)', color: 'var(--on-accent)',
            borderRadius: 10, padding: '8px 8px 8px 16px',
            display: 'flex', alignItems: 'center', gap: 12,
            boxShadow: '0 8px 24px rgba(9,9,11,0.18), 0 2px 4px rgba(9,9,11,0.1)',
            zIndex: 5,
          }}>
            <span style={{ fontSize: 12, fontWeight: 500 }} className="tnum">{selected.size} selected</span>
            <span style={{ width: 1, height: 16, background: 'rgba(255,255,255,0.18)' }} />
            <button onClick={() => setSelected(new Set())} style={{ fontSize: 12, color: 'rgba(250,250,250,0.7)', padding: '4px 6px' }}>Clear</button>
            {/* "Add to schedule" removed in v0.1.6 — scheduling lands in
                v0.1.7. Hidden rather than disabled to keep the action bar
                short. */}
            <button
              onClick={async () => {
                // Direct path when scoped to one screen — no multi-screen picker.
                // The server queues per deviceId, so an offline tablet just
                // picks the push up on its next poll.
                if (targetDeviceId) {
                  try {
                    const chosen = MOCK_VIDEOS.filter((v) => selected.has(v.id));
                    await setScreenPlaylist(targetDeviceId, chosen, 'append');
                    const name = targetScreen?.name || 'screen';
                    showToast(
                      targetScreen?.online
                        ? `Added ${chosen.length} to ${name}`
                        : `Queued ${chosen.length} for ${name} — applies on reconnect`,
                      'ok',
                    );
                    setSelected(new Set());
                  } catch (e) {
                    showToast(`Push failed: ${e.message}`, 'err');
                  }
                } else {
                  setPushPickerOpen(true);
                }
              }}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                background: 'var(--ink-10)', color: 'var(--ink-0)',
                padding: '6px 12px', borderRadius: 6,
                fontSize: 12, fontWeight: 500, cursor: 'pointer',
              }}>
              <Icon.arrowR size={12} />
              {targetDeviceId
                ? `Add ${selected.size} to ${targetScreen?.name || 'this screen'}`
                : `Push ${selected.size} to screens`}
            </button>
          </div>
        )}

        <UploadPanel open={uploadOpen} onClose={() => setUploadOpen(false)} />
        <PreviewModal video={previewVideo} onClose={() => setPreviewVideo(null)} />
        {pushPickerOpen && (
          <PushPicker
            videos={MOCK_VIDEOS.filter((v) => selected.has(v.id))}
            onClose={(success) => {
              setPushPickerOpen(false);
              if (success) setSelected(new Set());
            }}
          />
        )}
      </div>
    </AppShell>
  );
};

Object.assign(window, { ContentLibrary, AddContentModal });
