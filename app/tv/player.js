/*
 * Screens web player, v1.
 *
 * Plays a screen's Screens playlist in a browser, using the same server API
 * as the Android player (register, heartbeat, /api/state). Built for Samsung
 * Tizen signage (Chromium M94) opened through the panel's Custom App setting,
 * but it runs in any modern browser.
 *
 * Open it as:
 *   https://screens.smartechworld.com/tv/?store=tmrw-holt-yorkdale&code=COL.L-1
 * `store` + `code` make a stable screen id, so a panel keeps its identity in
 * the CMS even if its browser storage is wiped. Add `&debug=1` (or press
 * Enter / OK on the remote) for the readout: clock offset, drift, cache.
 *
 * Sync: same maths as the Android player. Position in the loop is
 * (server time - loopStartedAt) mod (sum of item durations), using the
 * server's integer durationSec for every item so every screen in a group
 * agrees. Item changes are driven by that clock, not by the video ending.
 * Drift is closed with a small playback-rate nudge where the browser honours
 * it, and with a seek otherwise.
 *
 * Written for Chromium M94: no structuredClone, findLast, AbortSignal.timeout,
 * :has() or ES modules.
 */
(function () {
  'use strict';

  var VERSION = 'web-1.0.0';
  var FLAVOR = 'web';

  // ── Config from the address ─────────────────────────────────────────
  var params = new URLSearchParams(location.search);
  var STORE = (params.get('store') || params.get('s') || '').trim();
  var CODE = (params.get('code') || params.get('c') || '').trim();
  var debugOn = params.get('debug') === '1';

  // Built-in stores (mirrors LocationTaxonomy). A store missing here still
  // works; its city and region just stay blank until set in the CMS.
  var STORES = {
    'tmrw-holt-yorkdale': { city: 'TOR', region: 'CANADA' },
    'tmrw-times-square': { city: 'NYC', region: 'USA' },
    'smartech-selfridges': { city: 'LDN', region: 'UK' },
    'smartech-kadewe': { city: 'BER', region: 'EU' },
    'tmrw-rinascente': { city: 'ROM', region: 'EU' },
    'events': { city: 'GLB', region: 'GLOBAL' },
    'test': { city: 'GLB', region: 'GLOBAL' }
  };

  function slug(s) {
    return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  }

  function store(key, val) {
    try {
      if (val === undefined) return localStorage.getItem(key);
      if (val === null) localStorage.removeItem(key); else localStorage.setItem(key, val);
    } catch (e) { /* storage blocked: run without it */ }
    return null;
  }

  var deviceId = (function () {
    if (CODE) return 'web-' + (STORE ? slug(STORE) + '-' : '') + slug(CODE);
    var saved = store('screens.deviceId');
    if (saved) return saved;
    var id = 'web-' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
    store('screens.deviceId', id);
    return id;
  })();

  // ── Elements ────────────────────────────────────────────────────────
  var stageEl = document.getElementById('stage');
  var videoEl = document.getElementById('video');
  var imageEl = document.getElementById('image');
  var idleEl = document.getElementById('idle');
  var debugEl = document.getElementById('debug');

  // ── Log ring (shown in the debug readout) ───────────────────────────
  var logLines = [];
  function log(msg) {
    var line = new Date().toISOString().slice(11, 19) + ' ' + msg;
    logLines.push(line);
    if (logLines.length > 8) logLines.shift();
    try { console.log('[screens] ' + msg); } catch (e) { /* ignore */ }
  }

  // ── Clock ───────────────────────────────────────────────────────────
  // Monotonic local time in epoch-ms. Date.now() can jump when the panel
  // corrects its own clock; performance.now() never does.
  function localNow() { return performance.timeOrigin + performance.now(); }

  var clock = { samples: [], offsetMs: null, bestRttMs: null };

  function recordClock(t1, t3, t4) {
    var rtt = Math.max(0, t4 - t1);
    var offset = t3 + rtt / 2 - t4;
    clock.samples.push({ offset: offset, rtt: rtt });
    if (clock.samples.length > 8) clock.samples.shift();
    var best = clock.samples[0];
    for (var i = 1; i < clock.samples.length; i++) {
      if (clock.samples[i].rtt < best.rtt) best = clock.samples[i];
    }
    clock.offsetMs = best.offset;
    clock.bestRttMs = best.rtt;
    // Remember the offset against the wall clock for an offline cold start.
    store('screens.wallOffset', String(localNow() + clock.offsetMs - Date.now()));
  }

  function serverNow() {
    if (clock.offsetMs !== null) return localNow() + clock.offsetMs;
    var wall = parseFloat(store('screens.wallOffset') || '0') || 0;
    return Date.now() + wall;
  }

  // ── Network helpers ─────────────────────────────────────────────────
  var deviceSecret = store('screens.secret.' + deviceId);

  function fetchWithTimeout(url, opts, ms) {
    var ctl = new AbortController();
    var timer = setTimeout(function () { ctl.abort(); }, ms || 15000);
    opts = opts || {};
    opts.signal = ctl.signal;
    return fetch(url, opts).finally(function () { clearTimeout(timer); });
  }

  function postJson(path, body) {
    var headers = { 'Content-Type': 'application/json' };
    if (deviceSecret) headers['X-Device-Secret'] = deviceSecret;
    return fetchWithTimeout(path, { method: 'POST', headers: headers, body: JSON.stringify(body) }, 15000);
  }

  function screenInfo() {
    var w = Math.round(screen.width * (window.devicePixelRatio || 1));
    var h = Math.round(screen.height * (window.devicePixelRatio || 1));
    var ua = navigator.userAgent || '';
    var model = /Tizen/i.test(ua) ? 'Samsung Tizen' : 'Web browser';
    var m = ua.match(/Tizen\s*([\d.]+)/i);
    if (m) model += ' ' + m[1];
    var c = ua.match(/Chrome\/(\d+)/);
    if (c) model += ' (Chromium ' + c[1] + ')';
    return { screenWidth: w, screenHeight: h, orientation: h > w ? 'PORTRAIT' : 'LANDSCAPE', deviceModel: model };
  }

  function locationJson() {
    var known = STORES[STORE] || {};
    var loc = {};
    if (known.region) loc.region = known.region;
    if (known.city) loc.city = known.city;
    if (STORE) loc.storeId = STORE;
    if (CODE) loc.screenCode = CODE;
    return loc;
  }

  var registered = false;
  function register() {
    var info = screenInfo();
    var body = {
      deviceId: deviceId,
      name: CODE || 'Web screen',
      location: locationJson(),
      appVersion: VERSION,
      appFlavor: FLAVOR,
      deviceModel: info.deviceModel,
      screenWidth: info.screenWidth,
      screenHeight: info.screenHeight,
      orientation: info.orientation
    };
    return postJson('/api/screens/register', body)
      .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
      .then(function (j) {
        registered = true;
        if (j && j.deviceSecret) {
          deviceSecret = j.deviceSecret;
          store('screens.secret.' + deviceId, deviceSecret);
        }
        log('Registered as ' + deviceId);
      })
      .catch(function (e) { log('Register failed: ' + e.message); });
  }

  // ── Playlist state ──────────────────────────────────────────────────
  var state = {
    revision: -1,
    items: [],            // [{id, type, url, durMs, title}]
    totalMs: 0,
    syncGroup: null,
    anchorServerMs: null, // group loop start (server clock), sync mode
    anchorLocalMs: null,  // own loop start (local clock), free-running mode
    rotation: 0,
    audioOn: false,
    pollMs: 60000,
    splashUrl: null,
    online: false,
    lastPollOk: 0,
    lastError: ''
  };

  function playlistKey(items) {
    return items.map(function (i) { return i.id + '@' + i.durMs; }).join('|');
  }

  function applyState(st, fromCache) {
    if (!fromCache && st.commands && st.commands.length) {
      st.commands.forEach(function (c) { runCommand(c && c.command); });
    }

    var items = (st.items || []).filter(function (i) { return i && i.url; }).map(function (i) {
      return {
        id: String(i.id),
        type: i.type === 'image' ? 'image' : 'video',
        url: i.url,
        durMs: Math.max(1, Number(i.durationSec) || 15) * 1000,
        title: i.title || ''
      };
    });
    var changed = playlistKey(items) !== playlistKey(state.items);
    state.items = items;
    state.totalMs = items.reduce(function (a, i) { return a + i.durMs; }, 0);
    state.revision = st.revision != null ? st.revision : state.revision;
    state.syncGroup = st.syncGroup || null;
    state.audioOn = !!st.audioOn;
    state.splashUrl = st.splashUrl || null;

    var pb = st.playback;
    var anchor = (state.syncGroup && pb && pb.loopStartedAtMs > 0) ? Number(pb.loopStartedAtMs) : null;
    if (anchor !== state.anchorServerMs) {
      if (anchor) log('Sync group ' + state.syncGroup + ', loop start ' + anchor);
      state.anchorServerMs = anchor;
      forceSwitch = true;
    }
    if (changed) {
      log('Playlist: ' + items.length + ' item(s), ' + Math.round(state.totalMs / 1000) + ' s loop');
      state.anchorLocalMs = localNow();
      forceSwitch = true;
      precache(items);
    }

    var mode = (st.pollMode || 'normal').toLowerCase();
    var ms = mode === 'fast' ? 10000 : mode === 'slow' ? 600000 : 60000;
    // A synced screen keeps polling at least every 15 s so its clock offset
    // stays fresh; the poll is also where the offset comes from.
    if (state.syncGroup) ms = Math.min(ms, 15000);
    state.pollMs = ms;

    setRotation(Number(st.rotation) || 0);
    videoEl.muted = !state.audioOn;

    if (!fromCache) {
      try {
        store('screens.lastState', JSON.stringify({
          items: st.items, revision: st.revision, syncGroup: st.syncGroup,
          playback: st.playback, rotation: st.rotation, audioOn: st.audioOn,
          pollMode: st.pollMode, splashUrl: st.splashUrl
        }));
      } catch (e) { /* too big for storage: fine */ }
    }
  }

  function setRotation(deg) {
    if (deg === state.rotation && stageEl.className) return;
    state.rotation = deg;
    stageEl.className = deg === 90 ? 'rot90' : deg === 180 ? 'rot180' : deg === 270 ? 'rot270' : 'rot0';
  }

  // ── Commands from the CMS ───────────────────────────────────────────
  function runCommand(cmd) {
    log('Command: ' + cmd);
    switch (cmd) {
      case 'reboot':
      case 'restartPlayer':
      case 'update':
        // A web page can't reboot the panel; a full reload is the closest
        // and also picks up a newer version of this player.
        setTimeout(function () { location.reload(); }, 300);
        break;
      case 'refresh':
        setTimeout(poll, 100);
        break;
      case 'clearCache':
        if (window.caches) {
          caches.delete(MEDIA_CACHE).then(function () { location.reload(); });
        }
        break;
      default:
        // sendLogs and anything newer: not supported in v1.
        break;
    }
  }

  // ── Poll loop ───────────────────────────────────────────────────────
  var pollTimer = null;
  var polling = false;

  function poll() {
    if (polling) return;
    polling = true;
    clearTimeout(pollTimer);
    var t1 = localNow();
    fetchWithTimeout('/api/state?screenId=' + encodeURIComponent(deviceId), {
      cache: 'no-store',
      headers: deviceSecret ? { 'X-Device-Secret': deviceSecret } : {}
    }, 15000)
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      })
      .then(function (txt) {
        var t4 = localNow();
        var st = JSON.parse(txt);
        if (st.serverNowMs) recordClock(t1, Number(st.serverNowMs), t4);
        state.online = true;
        state.lastPollOk = Date.now();
        applyState(st, false);
      })
      .catch(function (e) {
        state.online = false;
        state.lastError = e.message || String(e);
        log('Poll failed: ' + state.lastError);
      })
      .finally(function () {
        polling = false;
        pollTimer = setTimeout(poll, state.online ? state.pollMs : 15000);
      });
  }

  // A few quick polls at start so the clock offset settles in seconds,
  // not minutes.
  function warmUpClock(n) {
    if (n <= 0) return;
    setTimeout(function () { poll(); warmUpClock(n - 1); }, 1500);
  }

  // ── Heartbeat ───────────────────────────────────────────────────────
  var cachedIds = [];

  function heartbeat() {
    var info = screenInfo();
    postJson('/api/screens/heartbeat', {
      deviceId: deviceId,
      appVersion: VERSION,
      appFlavor: FLAVOR,
      deviceModel: info.deviceModel,
      screenWidth: info.screenWidth,
      screenHeight: info.screenHeight,
      orientation: info.orientation,
      status: 'ONLINE',
      currentRevision: state.revision,
      cachedVideoIds: cachedIds
    }).catch(function () { /* the next one will try again */ });
  }

  // ── Media cache (service worker) ────────────────────────────────────
  var MEDIA_CACHE = 'screens-media-v1';
  var swState = 'not supported';

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('sw.js').then(function () {
      swState = 'registered';
      return navigator.serviceWorker.ready;
    }).then(function () {
      swState = navigator.serviceWorker.controller ? 'active' : 'active (from next load)';
      if (state.items.length) precache(state.items);
    }).catch(function (e) {
      swState = 'failed: ' + e.message;
      log('Service worker failed: ' + e.message);
    });
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist().catch(function () { /* best effort */ });
    }
  }

  function precache(items) {
    var urls = items.map(function (i) { return absUrl(i.url); });
    if (state.splashUrl) urls.push(absUrl(state.splashUrl));
    if (navigator.serviceWorker && navigator.serviceWorker.controller) {
      navigator.serviceWorker.controller.postMessage({ type: 'precache', urls: urls });
    }
  }

  function absUrl(u) { return new URL(u, location.href).href; }

  function refreshCacheStatus() {
    if (!window.caches) return;
    caches.open(MEDIA_CACHE).then(function (c) { return c.keys(); }).then(function (keys) {
      var have = {};
      keys.forEach(function (k) { have[k.url] = true; });
      cachedIds = state.items.filter(function (i) { return have[absUrl(i.url)]; }).map(function (i) { return i.id; });
    }).catch(function () { /* ignore */ });
  }

  // ── Playback engine ─────────────────────────────────────────────────
  var current = { idx: -1, id: null, url: null };
  var forceSwitch = true;
  var graceUntil = 0;         // no drift checks until this local time
  var seekLeadMs = 150;       // learnt: how far ahead to seek to land on time
  var pendingSeekTarget = null;
  var rateWorks = null;       // null = untested, true/false after a probe
  var currentRate = 1;
  var lastDriftMs = null;
  var stall = { lastPos: -1, since: 0, recoveries: [] };
  var startTimer = null;
  var pageStart = localNow();

  /** Where should this screen be right now? {idx, posMs, waitMs}. */
  function expected() {
    if (!state.items.length || state.totalMs <= 0) return null;
    var elapsed;
    if (state.anchorServerMs) elapsed = serverNow() - state.anchorServerMs;
    else elapsed = localNow() - (state.anchorLocalMs || localNow());
    if (elapsed < 0) return { idx: 0, posMs: 0, waitMs: -elapsed };
    var off = elapsed % state.totalMs;
    var acc = 0;
    for (var i = 0; i < state.items.length; i++) {
      var d = state.items[i].durMs;
      if (off < acc + d) return { idx: i, posMs: off - acc, waitMs: 0 };
      acc += d;
    }
    return { idx: state.items.length - 1, posMs: state.items[state.items.length - 1].durMs - 1, waitMs: 0 };
  }

  function showIdle(text) {
    idleEl.textContent = text;
    idleEl.style.display = 'flex';
  }

  function hideIdle() { idleEl.style.display = 'none'; }

  function setRate(r) {
    if (r === currentRate) return;
    currentRate = r;
    try { videoEl.playbackRate = r; } catch (e) { rateWorks = false; }
  }

  function seekVideo(posMs) {
    var target = posMs + seekLeadMs;
    if (videoEl.duration && target / 1000 >= videoEl.duration) target = videoEl.duration * 1000 - 50;
    pendingSeekTarget = { atLocal: localNow(), targetMs: target };
    try { videoEl.currentTime = Math.max(0, target / 1000); } catch (e) { /* not seekable yet */ }
    graceUntil = localNow() + 1500;
  }

  function switchTo(e) {
    var item = state.items[e.idx];
    current = { idx: e.idx, id: item.id, url: item.url };
    forceSwitch = false;
    setRate(1);
    if (item.type === 'image') {
      videoEl.pause();
      videoEl.style.display = 'none';
      imageEl.src = item.url;
      imageEl.style.display = 'block';
      hideIdle();
      return;
    }
    imageEl.style.display = 'none';
    videoEl.style.display = 'block';
    var startAt = e.posMs;
    if (absUrl(videoEl.currentSrc || videoEl.src || '') !== absUrl(item.url)) {
      videoEl.src = item.url;
      // Position once the browser knows the length.
      videoEl.onloadedmetadata = function () {
        videoEl.onloadedmetadata = null;
        var now = expected();
        if (now && now.waitMs > 0) { videoEl.currentTime = 0; return; }   // waiting for a group start
        if (now && now.idx === current.idx) seekVideo(now.posMs);
        playVideo();
      };
      videoEl.load();
    } else if (e.waitMs > 0) {
      videoEl.currentTime = 0;
    } else {
      seekVideo(startAt);
      playVideo();
    }
    hideIdle();
  }

  function playVideo() {
    var p = videoEl.play();
    if (p && p.catch) {
      p.catch(function () {
        // Autoplay with sound refused: play muted rather than not at all.
        if (!videoEl.muted) { videoEl.muted = true; videoEl.play().catch(function () {}); }
      });
    }
  }

  videoEl.addEventListener('seeked', function () {
    if (!pendingSeekTarget) return;
    // Learn how long a seek takes to land, so the next one aims ahead.
    var landedLate = (localNow() - pendingSeekTarget.atLocal) - seekLeadMs;
    seekLeadMs = Math.max(0, Math.min(1000, seekLeadMs + landedLate * 0.5));
    pendingSeekTarget = null;
  });

  videoEl.addEventListener('error', function () {
    log('Video error on ' + (current.id || '?'));
    forceSwitch = true;
  });

  function probeRate() {
    // Some engines accept playbackRate but ignore it. Set it and read back;
    // whether it changes real speed is checked by watching drift shrink.
    try {
      videoEl.playbackRate = 1.02;
      rateWorks = Math.abs(videoEl.playbackRate - 1.02) < 0.001;
      videoEl.playbackRate = 1;
    } catch (e) { rateWorks = false; }
    log('Fine speed control: ' + (rateWorks ? 'yes' : 'no'));
  }

  function tick() {
    var e = expected();
    if (!e) {
      current = { idx: -1, id: null, url: null };
      if (state.splashUrl) {
        if (absUrl(videoEl.src || '') !== absUrl(state.splashUrl)) {
          videoEl.loop = true;
          videoEl.src = state.splashUrl;
          videoEl.style.display = 'block';
          imageEl.style.display = 'none';
          playVideo();
        }
        hideIdle();
      } else {
        showIdle(state.online ? 'No content assigned to this screen yet.' : 'Connecting…');
      }
      return;
    }
    videoEl.loop = false;

    if (e.waitMs > 0) {
      // Coordinated start: sit on frame 0 of the first item until the
      // group's start time, then every screen begins together.
      if (current.idx !== 0 || forceSwitch) switchTo({ idx: 0, posMs: 0, waitMs: e.waitMs });
      if (state.items[0].type === 'video' && !videoEl.paused) videoEl.pause();
      // Start on the exact moment rather than on the next 200 ms tick.
      if (e.waitMs < 400 && !startTimer) {
        startTimer = setTimeout(function () {
          startTimer = null;
          graceUntil = localNow() + 1000;
          if (state.items[0] && state.items[0].type === 'video') playVideo();
        }, e.waitMs);
      }
      return;
    }

    var item = state.items[e.idx];
    if (forceSwitch || current.idx !== e.idx || current.id !== item.id) {
      switchTo(e);
      return;
    }
    if (item.type === 'image') return;

    if (videoEl.readyState < 2) return;            // still loading
    var durMs = (videoEl.duration || 0) * 1000;
    if (durMs > 0 && e.posMs >= durMs - 40) {
      // The clock says we're past the file's real end (durationSec rounds
      // up): hold the last frame until the next item is due.
      if (!videoEl.paused) videoEl.pause();
      return;
    }
    if (videoEl.paused && !videoEl.seeking) playVideo();
    if (rateWorks === null) probeRate();

    var now = localNow();
    if (now < graceUntil || videoEl.seeking) return;

    var drift = videoEl.currentTime * 1000 - e.posMs;    // + = ahead
    lastDriftMs = drift;
    var abs = Math.abs(drift);
    if (abs > 600) {
      setRate(1);
      seekVideo(e.posMs);
    } else if (abs > 100 && rateWorks) {
      // Bigger gap (typically just after a video change): close it in a
      // couple of seconds. 5 % on a muted video isn't visible.
      setRate(drift > 0 ? 0.95 : 1.05);
    } else if (abs > 20 && rateWorks) {
      setRate(drift > 0 ? 0.98 : 1.02);
    } else {
      setRate(1);
    }

    // Watchdog: playing but the picture isn't moving.
    var pos = videoEl.currentTime;
    if (pos !== stall.lastPos) { stall.lastPos = pos; stall.since = now; }
    else if (now - stall.since > 10000) {
      log('Stalled on ' + item.id + ', reloading the video');
      stall.since = now;
      stall.recoveries.push(now);
      stall.recoveries = stall.recoveries.filter(function (t) { return now - t < 300000; });
      if (stall.recoveries.length >= 3) { location.reload(); return; }
      videoEl.removeAttribute('src');
      videoEl.load();
      forceSwitch = true;
    }

    // A fresh page once a day, at the top of an item, keeps a long-running
    // browser from slowly leaking memory.
    if (now - pageStart > 24 * 3600 * 1000 && e.posMs < 300 && state.online) location.reload();
  }

  // ── Debug readout ───────────────────────────────────────────────────
  function renderDebug() {
    if (!debugOn) { debugEl.style.display = 'none'; return; }
    debugEl.style.display = 'block';
    var e = expected();
    var item = e ? state.items[e.idx] : null;
    var lines = [
      'Screens web player ' + VERSION,
      'Screen   ' + (CODE || '(no code)') + '  ·  ' + deviceId,
      'Store    ' + (STORE || '(none)'),
      'Server   ' + (state.online ? 'online' : 'offline' + (state.lastError ? ' (' + state.lastError + ')' : '')) +
        (registered ? '' : '  ·  not registered'),
      'Sync     ' + (state.syncGroup ? state.syncGroup : 'not in a sync group'),
      'Clock    offset ' + (clock.offsetMs === null ? '—' : Math.round(clock.offsetMs) + ' ms') +
        '  ·  best round trip ' + (clock.bestRttMs === null ? '—' : Math.round(clock.bestRttMs) + ' ms') +
        '  ·  ' + clock.samples.length + ' samples',
      'Drift    ' + (lastDriftMs === null ? '—' : Math.round(lastDriftMs) + ' ms') +
        '  ·  speed ' + currentRate.toFixed(2) + '  ·  fine speed control ' +
        (rateWorks === null ? 'untested' : rateWorks ? 'yes' : 'no') + '  ·  seek lead ' + Math.round(seekLeadMs) + ' ms',
      'Playing  ' + (item ? (e.idx + 1) + '/' + state.items.length + ' ' + (item.title || item.id) +
        ' @ ' + (e.posMs / 1000).toFixed(2) + ' s' : '—'),
      'Cache    ' + cachedIds.length + '/' + state.items.length + ' videos on this screen  ·  worker ' + swState,
      'Display  ' + (function () { var i = screenInfo(); return i.screenWidth + '×' + i.screenHeight + ' ' + i.orientation; })() +
        '  ·  rotation ' + state.rotation + '°',
      'Agent    ' + navigator.userAgent,
      ''
    ].concat(logLines);
    debugEl.textContent = lines.join('\n');
  }

  document.addEventListener('keydown', function (ev) {
    // Enter / OK on the remote toggles the readout.
    if (ev.key === 'Enter' || ev.keyCode === 13) { debugOn = !debugOn; renderDebug(); }
  });

  // ── Start ───────────────────────────────────────────────────────────
  (function boot() {
    setRotation(0);
    // Resume from the last playlist straight away, even with no network.
    try {
      var cached = store('screens.lastState');
      if (cached) applyState(JSON.parse(cached), true);
    } catch (e) { /* ignore */ }
    registerServiceWorker();
    register().then(function () { poll(); warmUpClock(4); heartbeat(); });
    setInterval(tick, 200);
    setInterval(heartbeat, 60000);
    setInterval(refreshCacheStatus, 20000);
    setInterval(renderDebug, 500);
    // If registration failed (offline at boot), keep trying.
    setInterval(function () { if (!registered) register(); }, 60000);
  })();
})();
