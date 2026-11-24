/*
 * Screens web player, v1.
 *
 * Plays a screen's Screens playlist in a browser, using the same server API
 * as the Android player (register, heartbeat, /api/state). Built for Samsung
 * Tizen signage (Chromium M94), inside the small app in samsung-app/ that the
 * panel's Custom App setting installs, but it runs in any modern browser.
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
  // Built-in stores (mirrors LocationTaxonomy). A store missing here still
  // works; its city and region just stay blank until set in the CMS.
  // `alias` is the short name used in the address (/tv/<alias>/<code>), and
  // `shortCode` expands a short position (L1) to the full screen code.
  var STORES = {
    'tmrw-holt-yorkdale': {
      city: 'TOR', region: 'CANADA', alias: 'holt',
      shortCode: function (c) {
        var m = /^(?:COL\.?)?([LR])-?(\d{1,2})$/i.exec(c);
        return m ? 'COL.' + m[1].toUpperCase() + '-' + parseInt(m[2], 10) : null;
      }
    },
    'tmrw-times-square': { city: 'NYC', region: 'USA' },
    'smartech-selfridges': { city: 'LDN', region: 'UK' },
    'smartech-kadewe': { city: 'BER', region: 'EU' },
    'tmrw-rinascente': { city: 'ROM', region: 'EU' },
    'events': { city: 'GLB', region: 'GLOBAL' },
    'test': { city: 'GLB', region: 'GLOBAL' }
  };

  function resolveStore(s) {
    if (!s) return '';
    var low = s.toLowerCase();
    if (STORES[low]) return low;
    for (var id in STORES) {
      if (STORES[id].alias === low) return id;
    }
    return s;
  }

  // Two ways to address a screen:
  //   short:  /tv/holt/L1          (easy to type on a TV remote)
  //   long:   /tv/?store=tmrw-holt-yorkdale&code=COL.L-1   (or ?s=…&c=…)
  // Both give the same screen id, so either can be used on the same panel.
  var params = new URLSearchParams(location.search);
  var pathParts = location.pathname.replace(/^\/tv\/?/, '').split('/')
    .filter(Boolean).map(function (p) { return decodeURIComponent(p); });
  var STORE = resolveStore((params.get('store') || params.get('s') || pathParts[0] || '').trim());
  var CODE = (params.get('code') || params.get('c') || pathParts[1] || '').trim();
  if (CODE && STORES[STORE] && STORES[STORE].shortCode) {
    CODE = STORES[STORE].shortCode(CODE) || CODE;
  }
  var debugOn = params.get('debug') === '1';

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
    next: null,           // {items, totalMs, anchor} waiting for its start time
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
    var syncGroup = st.syncGroup || null;
    var pb = st.playback;
    var anchor = (syncGroup && pb && pb.loopStartedAtMs > 0) ? Number(pb.loopStartedAtMs) : null;
    var newLoop = { items: items, totalMs: sumMs(items), anchor: anchor };
    state.revision = st.revision != null ? st.revision : state.revision;
    state.syncGroup = syncGroup;
    state.audioOn = !!st.audioOn;
    state.splashUrl = st.splashUrl || null;

    var same = playlistKey(items) === playlistKey(state.items) && anchor === state.anchorServerMs;
    var sameNext = state.next && playlistKey(items) === playlistKey(state.next.items) && anchor === state.next.anchor;
    if (same) {
      // Nothing new, or the server took back a start we were waiting for.
      if (state.next && !sameNext) clearNext();
    } else if (sameNext) {
      // Already waiting for exactly this.
    } else if (anchor && anchor - serverNow() > 800 && state.items.length && current.id) {
      // A new loop that starts in a moment: keep playing what's on until then,
      // so the picture never freezes, and open the new first video ready for
      // a straight cut at the start time every screen shares.
      state.next = newLoop;
      log('New loop of ' + items.length + ' item(s) starts in ' +
        Math.round((anchor - serverNow()) / 1000) + ' s');
      precache(items);
    } else {
      clearNext();
      useLoop(newLoop);
    }

    var mode = (st.pollMode || 'normal').toLowerCase();
    var ms = mode === 'fast' ? 10000 : mode === 'slow' ? 600000 : 60000;
    // A synced screen keeps polling at least every 15 s so its clock offset
    // stays fresh; the poll is also where the offset comes from.
    if (state.syncGroup) ms = Math.min(ms, 15000);
    state.pollMs = ms;

    setRotation(Number(st.rotation) || 0);
    activeEl().muted = !state.audioOn;

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

  function sumMs(items) { return items.reduce(function (a, i) { return a + i.durMs; }, 0); }

  /** Make `loop` the one being played, from now. */
  function useLoop(loop) {
    var contentChanged = playlistKey(loop.items) !== playlistKey(state.items);
    if (loop.anchor !== state.anchorServerMs && loop.anchor) {
      log('Sync group ' + state.syncGroup + ', loop start ' + loop.anchor);
    }
    state.items = loop.items;
    state.totalMs = loop.totalMs;
    state.anchorServerMs = loop.anchor;
    if (contentChanged) {
      log('Playlist: ' + loop.items.length + ' item(s), ' + Math.round(loop.totalMs / 1000) + ' s loop');
      state.anchorLocalMs = localNow();
      badItems = {};
      forgetCue();
      precache(loop.items);
    }
    forceSwitch = true;
  }

  function clearNext() {
    if (!state.next) return;
    state.next = null;
    clearTimeout(nextTimer);
    nextTimer = null;
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

  var heartbeatTimer = null;

  /** How this screen's sync is going, for the CMS's sync health view. */
  function syncReport() {
    if (!state.syncGroup) return null;
    var e = expected();
    var item = e ? state.items[e.idx] : null;
    var bad = 0;
    state.items.forEach(function (i) { if (badItems[i.id]) bad++; });
    var waiting = !!(state.next || (e && e.waitMs > 0));
    var el = activeEl();
    var playing = item && item.type === 'video' && current.idx === (e ? e.idx : -2) && !el.paused && el.readyState >= 2;
    return {
      group: state.syncGroup,
      anchorMs: state.anchorServerMs,
      itemIndex: e ? e.idx : null,
      itemId: item ? item.id : null,
      items: state.items.length,
      missing: bad,
      positionMs: e ? Math.round(e.posMs) : null,
      driftMs: playing && lastDriftMs !== null ? Math.round(lastDriftMs) : null,
      offsetMs: clock.offsetMs === null ? null : Math.round(clock.offsetMs),
      rttMs: clock.bestRttMs === null ? null : Math.round(clock.bestRttMs),
      samples: clock.samples.length,
      speed: currentRate,
      seeks: seekTimes.filter(function (t) { return localNow() - t < 600000; }).length,
      state: waiting ? 'waiting' : !item ? 'idle' : item.type === 'image' ? 'image' : playing ? 'playing' : 'loading'
    };
  }

  function scheduleHeartbeat() {
    clearTimeout(heartbeatTimer);
    heartbeatTimer = setTimeout(function () { heartbeat(); scheduleHeartbeat(); }, state.syncGroup ? 20000 : 60000);
  }

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
      cachedVideoIds: cachedIds,
      sync: syncReport()
    }).catch(function () { /* the next one will try again */ });
  }

  // ── Media cache (service worker) ────────────────────────────────────
  var MEDIA_CACHE = 'screens-media-v1';
  var swState = 'not supported';

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/tv/sw.js', { scope: '/tv/' }).then(function () {
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
  // Two video elements: the one on show, and a standby that opens the next
  // item a few seconds early. A change is then a straight cut on the group's
  // clock instead of a blank while the file opens, and every screen in the
  // group cuts on the same millisecond. If the panel can't run a second
  // video, the standby's errors switch this off and the player falls back to
  // one element. `?gapless=0` in the address turns it off by hand.
  var vids = [document.getElementById('videoA'), document.getElementById('videoB')];
  var act = 0;
  function activeEl() { return vids[act]; }
  function standbyEl() { return vids[1 - act]; }
  var gapless = params.get('gapless') !== '0';
  var gaplessNote = gapless ? '' : 'turned off in the address';
  var standbyErrors = 0;
  var cue = null;             // {idx, url, ready, image} opened on the standby
  var boundaryTimer = null;

  var current = { idx: -1, id: null, since: 0 };
  var forceSwitch = true;
  var graceUntil = 0;         // no drift checks until this local time
  var seekLeadMs = 150;       // learnt: how far ahead to seek to land on time
  var pendingSeekTarget = null;
  var rateWorks = null;       // null = untested, true/false after a probe
  var rateCheck = null;       // {since, abs} while a speed nudge runs
  var currentRate = 1;
  var lastDriftMs = null;
  var stall = { lastPos: -1, since: 0, recoveries: [] };
  var startTimer = null;
  var pageStart = localNow();
  var seekTimes = [];         // local times of corrective seeks (sync report)
  var standbyFailed = {};     // url -> when the standby last failed to open it
  var badItems = {};          // item id -> when the panel last failed to play it
  var nextTimer = null;       // fires at the new loop's start time

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

  /** The group's start time has come: swap the new loop in and cut to it. */
  function startNext() {
    var loop = state.next;
    if (!loop) return;
    state.next = null;
    clearTimeout(nextTimer);
    nextTimer = null;
    // Keep the standby: it already holds the new loop's first item.
    var keepCue = cue;
    var contentChanged = playlistKey(loop.items) !== playlistKey(state.items);
    state.items = loop.items;
    state.totalMs = loop.totalMs;
    state.anchorServerMs = loop.anchor;
    if (contentChanged) { badItems = {}; state.anchorLocalMs = localNow(); }
    log('New loop started');
    cue = keepCue;
    var now = expected();
    if (now && !now.waitMs) switchTo(now);
    else forceSwitch = true;
  }

  function showIdle(text) {
    idleEl.textContent = text;
    idleEl.style.display = 'flex';
  }

  function hideIdle() { idleEl.style.display = 'none'; }

  function sameUrl(el, url) {
    var src = el.getAttribute('src');
    return !!src && absUrl(src) === absUrl(url);
  }

  /** Put `el` on top. The other element stays underneath, out of sight. */
  function showVideo(el) {
    var other = el === vids[0] ? vids[1] : vids[0];
    el.style.zIndex = '2';
    other.style.zIndex = '1';
    imageEl.style.display = 'none';
  }

  function clearVideo(el) {
    try { el.pause(); } catch (e) { /* ignore */ }
    el.onloadedmetadata = null;
    el.onloadeddata = null;
    if (el.getAttribute('src')) {
      el.removeAttribute('src');
      try { el.load(); } catch (e) { /* ignore */ }
    }
  }

  function forgetCue() {
    cue = null;
    clearVideo(standbyEl());
  }

  function setRate(r) {
    if (r === currentRate) return;
    currentRate = r;
    try { activeEl().playbackRate = r; } catch (e) { rateWorks = false; }
  }

  function seekVideo(el, posMs) {
    var target = posMs + seekLeadMs;
    if (el.duration && target / 1000 >= el.duration) target = el.duration * 1000 - 50;
    // Only learn from seeks made mid-play. One made while the file is still
    // opening includes the download, and would teach it to aim far ahead.
    seekTimes.push(localNow());
    if (seekTimes.length > 60) seekTimes.shift();
    pendingSeekTarget = { atLocal: localNow(), targetMs: target, learn: el.readyState >= 3 && !el.paused };
    try { el.currentTime = Math.max(0, target / 1000); } catch (e) { /* not seekable yet */ }
    graceUntil = localNow() + 1500;
  }

  function playVideo(el) {
    var p = el.play();
    if (p && p.catch) {
      p.catch(function () {
        // Autoplay with sound refused: play muted rather than not at all.
        if (!el.muted) { el.muted = true; el.play().catch(function () {}); }
      });
    }
  }

  /** Open the item after `idx` on the standby, paused on its first frame. */
  function prepareNext(idx) {
    if (!gapless || !state.items.length) return;
    var n = (idx + 1) % state.items.length;
    openOnStandby(n, state.items[n]);
  }

  /** Open `item` (the loop's item `n`) paused on its first frame on the
   *  standby, ready for a straight cut. */
  function openOnStandby(n, item) {
    if (!gapless) return;
    if (cue && cue.idx === n && cue.url === item.url) return;
    if (item.type === 'image') {
      clearVideo(standbyEl());
      var img = new Image();
      img.src = item.url;     // warm the cache so the cut is instant
      cue = { idx: n, url: item.url, ready: true, image: true };
      return;
    }
    var el = standbyEl();
    var mine = { idx: n, url: item.url, ready: false, image: false };
    cue = mine;
    el.onloadedmetadata = null;
    el.onloadeddata = function () {
      el.onloadeddata = null;
      if (cue !== mine) return;
      try { el.currentTime = 0; } catch (e) { /* ignore */ }
      mine.ready = true;
    };
    el.muted = true;
    el.preload = 'auto';
    el.src = item.url;
    el.load();
  }

  /** Cut to the item `e` names. */
  function switchTo(e) {
    var item = state.items[e.idx];
    var cued = cue && cue.idx === e.idx && cue.url === item.url && cue.ready;
    cue = null;
    forceSwitch = false;
    setRate(1);
    rateCheck = null;
    current = { idx: e.idx, id: item.id, since: localNow(), skipped: false };
    hideIdle();

    if (item.type === 'image') {
      imageEl.src = item.url;
      imageEl.style.display = 'block';
      activeEl().pause();
      return;
    }

    if (cued && !e.waitMs) {
      // The standby already has this item open on its first frame: start it
      // and bring it to the front in the same moment.
      var next = standbyEl();
      var prev = activeEl();
      act = 1 - act;
      next.playbackRate = 1;
      next.muted = !state.audioOn;
      if (e.posMs > 120) { try { next.currentTime = e.posMs / 1000; } catch (x) { /* ignore */ } }
      playVideo(next);
      showVideo(next);
      prev.pause();
      graceUntil = localNow() + 1000;
      return;
    }

    // The panel failed to play this one a moment ago: don't reopen it over
    // and over. Its slot still runs, so the group stays in step; it gets
    // another go the next time round.
    if (badItems[item.id] && localNow() - badItems[item.id] < 30000) {
      log('Skipping ' + (item.title || item.id) + ' this time (this screen could not play it)');
      current.skipped = true;
      activeEl().pause();
      return;
    }

    // Nothing ready on the standby: open it on the element on show.
    var el = activeEl();
    showVideo(el);
    el.muted = !state.audioOn;
    if (!sameUrl(el, item.url)) {
      el.onloadeddata = null;
      el.onloadedmetadata = function () {
        el.onloadedmetadata = null;
        var now = expected();
        if (now && now.waitMs > 0) { el.currentTime = 0; return; }   // waiting for a group start
        if (now && now.idx === current.idx) seekVideo(el, now.posMs);
        playVideo(el);
      };
      el.src = item.url;
      el.load();
    } else if (e.waitMs > 0) {
      el.pause();
      el.currentTime = 0;
    } else {
      seekVideo(el, e.posMs);
      playVideo(el);
    }
  }

  vids.forEach(function (el) {
    el.addEventListener('playing', function () {
      if (el !== activeEl() || !current.id) return;
      delete badItems[current.id];
      var item = state.items[current.idx];
      var failedAt = item ? standbyFailed[item.url] : 0;
      if (failedAt && localNow() - failedAt < 600000) {
        // The main element plays it, the standby couldn't open it.
        delete standbyFailed[item.url];
        standbyErrors++;
        if (standbyErrors >= 2 && gapless) {
          gapless = false;
          gaplessNote = 'turned off: this screen could not open a second video';
          log('Seamless changes off: ' + gaplessNote);
          clearVideo(standbyEl());
        }
      }
    });
    el.addEventListener('seeked', function () {
      if (!pendingSeekTarget || el !== activeEl()) return;
      // Learn how long a seek takes to land, so the next one aims ahead.
      if (pendingSeekTarget.learn) {
        var landedLate = (localNow() - pendingSeekTarget.atLocal) - seekLeadMs;
        seekLeadMs = Math.max(0, Math.min(500, seekLeadMs + landedLate * 0.5));
      }
      pendingSeekTarget = null;
    });
    el.addEventListener('error', function () {
      if (el === activeEl()) {
        log('Video error on ' + (current.id || '?'));
        if (current.id) badItems[current.id] = localNow();
        forceSwitch = true;
        return;
      }
      // Not proof the panel can't run two videos: the file itself may be bad
      // or the network may have dropped. It's only evidence if the same video
      // then plays fine on the main element (see the 'playing' listener).
      var failedCue = cue;
      cue = null;
      if (failedCue && failedCue.url) standbyFailed[failedCue.url] = localNow();
      log('Standby video failed to open');
    });
  });

  function probeRate(el) {
    // Some engines refuse playbackRate outright: set it and read it back.
    // Engines that take it but still play at 1x are caught in correctDrift.
    try {
      el.playbackRate = 1.02;
      rateWorks = Math.abs(el.playbackRate - 1.02) < 0.001;
      el.playbackRate = 1;
    } catch (e) { rateWorks = false; }
    log('Fine speed control: ' + (rateWorks ? 'yes' : 'no'));
  }

  function correctDrift(el, drift, posMs, now) {
    var abs = Math.abs(drift);
    if (abs > 600 || (!rateWorks && abs > 200)) {
      setRate(1);
      rateCheck = null;
      seekVideo(el, posMs);
      return;
    }
    if (!rateWorks) return;
    // A small gap is closed with a speed change no one can see. Start at
    // 25 ms and keep going until it's under 8 ms, so the speed doesn't flick
    // on and off around one threshold.
    var target = 1;
    if (abs > 100) target = drift > 0 ? 0.95 : 1.05;
    else if (abs > 25 || (currentRate !== 1 && abs > 8)) target = drift > 0 ? 0.98 : 1.02;
    if (target === 1) rateCheck = null;
    else if (!rateCheck) rateCheck = { since: now, abs: abs };
    setRate(target);
    // Some engines accept a new speed but keep playing at 1x. If a nudge
    // hasn't closed the gap at all in 4 s, correct with seeks instead.
    if (rateCheck && now - rateCheck.since > 4000) {
      if (abs >= rateCheck.abs - 10) {
        rateWorks = false;
        setRate(1);
        log('Speed changes have no effect here, correcting with seeks');
      }
      rateCheck = null;
    }
  }

  function tick() {
    var e = expected();
    var el = activeEl();
    if (state.next && !e && state.next.anchor <= serverNow()) { startNext(); return; }
    if (!e) {
      current = { idx: -1, id: null, since: 0 };
      if (cue) forgetCue();
      if (state.splashUrl) {
        if (!sameUrl(el, state.splashUrl)) {
          el.loop = true;
          el.src = state.splashUrl;
          showVideo(el);
          playVideo(el);
        }
        hideIdle();
      } else {
        showIdle(state.online ? 'No content assigned to this screen yet.' : 'Connecting…');
      }
      return;
    }
    el.loop = false;

    // A new loop is waiting for the group's start time: play on, open its
    // first video on the standby, and cut on the exact millisecond.
    if (state.next) {
      var toStart = state.next.anchor - serverNow();
      if (toStart <= 0) { startNext(); return; }
      if (toStart < 5000 && state.next.items.length) openOnStandby(0, state.next.items[0]);
      if (toStart < 500 && !nextTimer) {
        nextTimer = setTimeout(function () { nextTimer = null; if (state.next) startNext(); }, toStart);
      }
    }

    if (e.waitMs > 0) {
      // Coordinated start: sit on frame 0 of the first item until the
      // group's start time, then every screen begins together.
      if (current.idx !== 0 || forceSwitch) switchTo({ idx: 0, posMs: 0, waitMs: e.waitMs });
      el = activeEl();
      if (state.items[0].type === 'video' && !el.paused) el.pause();
      // Start on the exact moment rather than on the next 200 ms tick.
      if (e.waitMs < 400 && !startTimer) {
        startTimer = setTimeout(function () {
          startTimer = null;
          graceUntil = localNow() + 1000;
          current.since = localNow();
          if (state.items[0] && state.items[0].type === 'video') playVideo(activeEl());
        }, e.waitMs);
      }
      return;
    }

    var item = state.items[e.idx];
    if (forceSwitch || current.idx !== e.idx || current.id !== item.id) {
      switchTo(e);
      return;
    }

    // Open the next item early, then cut to it on the exact millisecond the
    // group clock says, not on whichever 200 ms tick comes after it.
    var remaining = item.durMs - e.posMs;
    var startSoon = state.next && state.next.anchor - serverNow() < 6000;
    if (remaining < 5000 && !startSoon) prepareNext(e.idx);   // the standby is the new loop's then
    if (remaining <= 350 && !boundaryTimer) {
      boundaryTimer = setTimeout(function () {
        boundaryTimer = null;
        var b = expected();
        if (!b || b.waitMs) return;
        // A one-item playlist wraps onto the same item: that's a cut too.
        if (b.idx !== current.idx || (b.posMs < 400 && localNow() - current.since > 1000)) switchTo(b);
      }, Math.max(0, remaining));
    }

    if (item.type === 'image' || current.skipped) return;
    if (el.readyState < 2) return;            // still loading
    var durMs = (el.duration || 0) * 1000;
    if (durMs > 0 && e.posMs >= durMs - 40) {
      // The clock says we're past the file's real end (durationSec rounds
      // up): hold the last frame until the next item is due.
      if (!el.paused) el.pause();
      return;
    }
    if (el.paused && !el.seeking) playVideo(el);
    if (rateWorks === null) probeRate(el);

    var now = localNow();
    if (now < graceUntil || el.seeking) return;

    var drift = el.currentTime * 1000 - e.posMs;    // + = ahead
    lastDriftMs = drift;
    correctDrift(el, drift, e.posMs, now);

    // Watchdog: playing but the picture isn't moving.
    var pos = el.currentTime;
    if (pos !== stall.lastPos) { stall.lastPos = pos; stall.since = now; }
    else if (now - stall.since > 10000) {
      log('Stalled on ' + item.id + ', reloading the video');
      stall.since = now;
      stall.recoveries.push(now);
      stall.recoveries = stall.recoveries.filter(function (t) { return now - t < 300000; });
      if (stall.recoveries.length >= 3) { location.reload(); return; }
      clearVideo(el);
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
      'Screens web player ' + VERSION + (params.get('app') ? '  ·  Samsung app ' + params.get('app') : ''),
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
      'Next     ' + (!gapless ? 'seamless changes ' + gaplessNote
        : cue ? (cue.idx + 1) + '/' + state.items.length + (cue.ready ? ' ready' : ' opening…')
        : 'opens 5 s before the change'),
      'Cache    ' + cachedIds.length + '/' + state.items.length + ' videos on this screen  ·  worker ' + swState,
      'Display  ' + (function () { var i = screenInfo(); return i.screenWidth + '×' + i.screenHeight + ' ' + i.orientation; })() +
        '  ·  rotation ' + state.rotation + '°',
      'Agent    ' + navigator.userAgent,
      ''
    ].concat(logLines);
    debugEl.textContent = lines.join('\n');
  }

  function toggleDebug() { debugOn = !debugOn; renderDebug(); }

  document.addEventListener('keydown', function (ev) {
    // Enter / OK on the remote toggles the readout.
    if (ev.key === 'Enter' || ev.keyCode === 13) toggleDebug();
    // Return (Back) on a Samsung remote would close the app.
    if (ev.keyCode === 10009) ev.preventDefault();
  });

  // Inside the Samsung app (samsung-app/src/main.js): say we're alive, so it
  // knows not to open this page again, and take keys it caught.
  var embedded = window.parent !== window;
  function sayAlive() {
    if (embedded) window.parent.postMessage({ type: 'screens-alive', version: VERSION }, '*');
  }
  if (embedded) {
    window.addEventListener('message', function (ev) {
      if (ev.source !== window.parent || !ev.data) return;
      if (ev.data.type === 'screens-key' && ev.data.key === 'Enter') toggleDebug();
    });
  }

  // ── Start ───────────────────────────────────────────────────────────
  (function boot() {
    setRotation(0);
    // Resume from the last playlist straight away, even with no network.
    try {
      var cached = store('screens.lastState');
      if (cached) applyState(JSON.parse(cached), true);
    } catch (e) { /* ignore */ }
    registerServiceWorker();
    register().then(function () { poll(); warmUpClock(4); heartbeat(); scheduleHeartbeat(); });
    setInterval(tick, 200);
    sayAlive();
    setInterval(sayAlive, 5000);
    setInterval(refreshCacheStatus, 20000);
    setInterval(renderDebug, 500);
    // If registration failed (offline at boot), keep trying.
    setInterval(function () { if (!registered) register(); }, 60000);
  })();
})();
