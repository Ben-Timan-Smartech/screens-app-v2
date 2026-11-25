/*
 * Screens web player, v1.
 *
 * Plays a screen's Screens playlist in a browser, using the same server API
 * as the Android player (register, heartbeat, /api/state). Built for Samsung
 * Tizen signage (Chromium M94) opened in the panel's own web browser, but it
 * runs in any modern browser.
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

  var VERSION = 'web-1.3.1';
  var FLAVOR = 'web';

  // ── Config from the address ─────────────────────────────────────────
  // Built-in stores (mirrors LocationTaxonomy). A store missing here still
  // works; its city and region just stay blank until set in the CMS.
  // `alias` is the short name used in the address (/tv/<alias>/<code>), and
  // `shortCode` expands a short position (L1) to the full screen code.
  var STORES = {
    'tmrw-holt-yorkdale': {
      city: 'TOR', region: 'CANADA', alias: 'holt',
      concept: 'Holt',        // its own splash (Settings → Splashes)
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
  var syncingEl = document.getElementById('syncing');

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
    // The quickest round trip of the last 90 s: long enough to ride out a slow
    // patch of Wi-Fi, short enough that the screen's own clock hasn't wandered.
    clock.samples.push({ offset: offset, rtt: rtt, at: t4 });
    clock.samples = clock.samples.filter(function (x) { return t4 - x.at < 90000; }).slice(-40);
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
    if (known.concept) loc.concept = known.concept;
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
    trimMs: 0,
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
    var trim = Math.max(-500, Math.min(500, Number(st.syncTrimMs) || 0));
    if (trim !== state.trimMs) { state.trimMs = trim; resetMeasure(); graceUntil = localNow() + 1500; }

    var same = playlistKey(items) === playlistKey(state.items) && anchor === state.anchorServerMs;
    var sameNext = state.next && playlistKey(items) === playlistKey(state.next.items) && anchor === state.next.anchor;
    if (same) {
      // Nothing new, or the server took back a start we were waiting for.
      if (state.next && !sameNext) clearNext();
    } else if (sameNext) {
      // Already waiting for exactly this.
    } else if (anchor && anchor - groupNow() > 800 && state.items.length && current.id) {
      // A new loop that starts in a moment: keep playing what's on until then,
      // so the picture never freezes, and open the new first video ready for
      // a straight cut at the start time every screen shares.
      state.next = newLoop;
      log('New loop of ' + items.length + ' item(s) starts in ' +
        Math.round((anchor - groupNow()) / 1000) + ' s');
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
    keepContent();

    if (!fromCache) {
      try {
        store('screens.lastState', JSON.stringify({
          items: st.items, revision: st.revision, syncGroup: st.syncGroup,
          playback: st.playback, rotation: st.rotation, audioOn: st.audioOn,
          pollMode: st.pollMode, splashUrl: st.splashUrl, syncTrimMs: st.syncTrimMs
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
          store('screens.localOff', null);
          store('screens.msOff', null);
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

  // Short requests that only fetch the time. A poll does other work on the
  // server, which makes its time less exact.
  var clockTimer = null;
  var timeApi = true;         // false if the server doesn't have it
  function sampleClock(n) {
    clearTimeout(clockTimer);
    if (n <= 0 || !timeApi) {
      clockTimer = setTimeout(function () { sampleClock(state.syncGroup ? 4 : 0); }, 20000);
      return;
    }
    var t1 = localNow(), t4 = 0;
    fetchWithTimeout('/api/time', { cache: 'no-store' }, 4000).then(function (r) {
      t4 = localNow();
      if (r.status === 404) timeApi = false;
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (j) {
      if (j && j.nowMs) recordClock(t1, Number(j.nowMs), t4);
    }).catch(function () { /* the polls still set the clock */ }).finally(function () {
      clockTimer = setTimeout(function () { sampleClock(n - 1); }, 150);
    });
  }

  // ── Heartbeat ───────────────────────────────────────────────────────
  var heartbeatTimer = null;

  /** The group's clock on this screen, with its adjustment from the CMS. */
  function groupNow() { return serverNow() + state.trimMs; }

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
      driftMs: (playing || (ms && ms.playing)) && lastDriftMs !== null ? Math.round(lastDriftMs) : null,
      offsetMs: clock.offsetMs === null ? null : Math.round(clock.offsetMs),
      rttMs: clock.bestRttMs === null ? null : Math.round(clock.bestRttMs),
      samples: clock.samples.length,
      speed: currentRate,
      trimMs: state.trimMs,
      startLeadMs: Math.round(startLeadMs),
      startErrMs: startErrMs === null ? null : Math.round(startErrMs),
      measure: measureBy + (ms ? ', stored stream' : calm ? ', ' + (calmPhase || 'calm') + ', spare ' + (calmSpareMs === null ? '?' : Math.round(calmSpareMs)) +
        ', back ' + (calmBackMs === null ? '?' : Math.round(calmBackMs)) + ', reopened ' + calmLoads : rateWorks === false ? ', no speed control' : ''),
      seeks: seekTimes.filter(function (t) { return localNow() - t < 600000; }).length,
      state: ms ? (ms.state === 'playing' ? 'playing' : 'loading') : calmPhase && calmPhase !== 'opening' ? (waiting ? 'waiting' : 'playing') : waiting ? 'waiting' : !item ? 'idle' : item.type === 'image' ? 'image' : playing ? 'playing' : 'loading'
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
      cachedVideoIds: state.items.filter(function (i) { return isKept(i.url); }).map(function (i) { return i.id; }),
      cacheBytes: keptBytes(),
      videoReport: videoReport(),
      sync: syncReport()
    }).catch(function () { /* the next one will try again */ });
  }

  // ── Content kept on this screen ─────────────────────────────────────
  // Every file on the playlist, and the splash, is downloaded whole into the
  // browser's storage and played from there, so the network only matters the
  // first time. A video that isn't here yet streams. The service worker keeps
  // the page itself, for a start with no network.
  var MEDIA_CACHE = 'screens-media-v1';
  var MAX_LOCAL_BYTES = 400 * 1024 * 1024;
  var kept = {};              // absolute url -> {state, src, bytes, tries, retryAt}
  var keepBusy = false;
  var swState = 'not supported';
  var served = { stored: 0, net: 0 };   // video requests the worker answered, and from where
  var localWhy = '';                    // why a stored file didn't play

  // Set when this browser won't play a stored file, so it streams instead.
  // Tried again after a day, or with a newer player.
  var localOff = (function () {
    var v = (store('screens.localOff') || '').split('|');
    return v[0] === VERSION && Math.abs(Date.now() - Number(v[1])) < 24 * 3600 * 1000;
  })();

  function registerServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.addEventListener('message', function (ev) {
      if (ev.data && ev.data.type === 'media') served[ev.data.stored ? 'stored' : 'net']++;
    });
    navigator.serviceWorker.register('/tv/sw.js', { scope: '/tv/' }).then(function () {
      swState = 'registered';
      return navigator.serviceWorker.ready;
    }).then(function () {
      swState = navigator.serviceWorker.controller ? 'active' : 'active (from next load)';
    }).catch(function (e) {
      swState = 'failed: ' + e.message;
      log('Service worker failed: ' + e.message);
    });
    if (navigator.storage && navigator.storage.persist) {
      navigator.storage.persist().catch(function () { /* best effort */ });
    }
  }

  function absUrl(u) { return new URL(u, location.href).href; }

  function wantedUrls() {
    var urls = [];
    function add(u) { u = absUrl(u); if (urls.indexOf(u) < 0) urls.push(u); }
    state.items.forEach(function (i) { if (i.url) add(i.url); });
    if (state.next) state.next.items.forEach(function (i) { if (i.url) add(i.url); });
    if (state.splashUrl) add(state.splashUrl);
    return urls;
  }

  function isKept(url) { var k = kept[absUrl(url)]; return !!k && k.state === 'ready'; }

  function keptBytes() {
    return Object.keys(kept).reduce(function (n, u) { return n + (kept[u].state === 'ready' ? kept[u].bytes : 0); }, 0);
  }

  /** The address to play `url` from: this screen's copy if it has one. */
  function srcFor(url) {
    var k = kept[absUrl(url)];
    return k && k.state === 'ready' && k.src && !localOff ? k.src : absUrl(url);
  }

  /** Download whatever is missing, one file at a time. */
  function keepContent() {
    if (keepBusy || !window.caches) return;
    var now = localNow();
    var urls = wantedUrls();
    var url = urls.filter(function (u) {
      var k = kept[u];
      return !k || (k.state !== 'ready' && now >= (k.retryAt || 0));
    })[0];
    if (!url) { tidyKept(urls); return; }
    var k = kept[url] = kept[url] || { tries: 0 };
    k.state = 'loading';
    keepBusy = true;
    keepOne(url, k).catch(function (e) {
      k.state = 'failed';
      k.tries++;
      k.retryAt = localNow() + Math.min(300000, 10000 * Math.pow(2, k.tries - 1));
      log('Could not store ' + url.split('/').pop().split('?')[0] + ': ' + (e && e.message || e));
    }).then(function () { keepBusy = false; keepContent(); });
  }

  function keepOne(url, k) {
    return caches.open(MEDIA_CACHE).then(function (c) {
      return c.match(url).then(function (hit) {
        if (hit && !k.again) return hit;
        return fetch(url, { cache: 'no-store' }).then(function (res) {
          if (res.status !== 200) throw new Error('HTTP ' + res.status);
          return c.put(url, res);        // fails if the download is cut short
        }).then(function () {
          if (k.again) k.downloads = (k.downloads || 0) + 1;
          k.again = false;
          return c.match(url);
        }).catch(function (e) {
          if (!hit) throw e;
          // It can't be replaced now (no network?): try the one that's here,
          // less and less often.
          k.notReplaced = (k.notReplaced || 0) + 1;
          k.readAt = localNow() + Math.min(1800000, 15000 * Math.pow(2, k.notReplaced));
          return hit;
        });
      }).then(function (hit) {
        if (!hit) throw new Error('not stored');
        return hit.blob().catch(function (e) {
          k.again = true;                // listed but not readable
          throw e;
        }).then(function (blob) {
          var want = Number(hit.headers.get('Content-Length')) || 0;
          if (!blob.size || (want && want !== blob.size)) {
            return c.delete(url).then(function () { throw new Error('incomplete'); });
          }
          k.bytes = blob.size;
          k.tries = 0;
          // Past the limit it stays stored, and plays through the worker.
          if (!k.src && !k.localFailed && keptBytes() + blob.size <= MAX_LOCAL_BYTES) k.src = URL.createObjectURL(blob);
          k.state = 'ready';
        });
      });
    });
  }

  /** Let go of files no longer on this screen, once everything else is here. */
  function tidyKept(urls) {
    if (!state.online || !urls.length) return;
    if (!urls.every(function (u) { return kept[u] && kept[u].state === 'ready'; })) return;
    Object.keys(kept).forEach(function (u) {
      if (urls.indexOf(u) >= 0 || vids.some(function (el) { return el._url === u; })) return;
      if (kept[u].src) URL.revokeObjectURL(kept[u].src);
      delete kept[u];
    });
    caches.open(MEDIA_CACHE).then(function (c) {
      return c.keys().then(function (keys) {
        return Promise.all(keys.filter(function (r) { return urls.indexOf(r.url) < 0 && !kept[r.url]; })
          .map(function (r) { return c.delete(r); }));
      });
    }).catch(function () { /* next time */ });
  }

  /** A stored file wouldn't play: stream that one. If the stream then plays,
   *  it was the browser and not the file (see the 'playing' listener). */
  function localFailed(el, why) {
    var k = kept[el._url];
    localWhy = why;
    log('Stored copy would not play (' + why + '), streaming it');
    if (k) {
      if (k.src) URL.revokeObjectURL(k.src);
      k.src = null;
      k.localFailed = true;
    }
    if (el === standbyEl()) { forgetCue(); return; }
    clearVideo(el);
    forceSwitch = true;
  }

  function turnOffLocal() {
    localOff = true;
    store('screens.localOff', VERSION + '|' + Date.now());
    log('This screen streams videos: it can play them, but not its stored copies');
  }

  /** One line for the CMS about what the video is doing. No addresses. */
  function videoReport() {
    var el = activeEl(), n = framesShown(el), urls = wantedUrls();
    return (el._url ? (el._mse ? 'stored stream' : el._local ? 'stored' : 'stream') : 'none') + ' ' + el.currentTime.toFixed(1) + '/' +
      (el.duration || 0).toFixed(1) + 's ' + videoState(el) + ', frames ' + (n === null ? '?' : n) +
      ' | kept ' + urls.filter(isKept).length + '/' + urls.length + (localOff ? ' off' : '') +
      (localWhy ? ' (' + localWhy + ')' : '') + (msNote ? ' [' + msNote.slice(0, 50) + ']' : '') +
      ' | worker ' + swState.slice(0, 24) + ' stored ' + served.stored + ' net ' + served.net +
      ' | ' + caps() + ' | stalls ' + stall.recoveries.length + (lastStall ? ', last: ' + lastStall : '');
  }

  /** What this browser can do, for choosing how to play on it. */
  function caps() {
    var mse = false;
    try { mse = !!window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="avc1.4d4029"'); } catch (e) { /* no */ }
    return 'mse ' + (mse ? 'yes' : 'no') + ', frame times ' + (activeEl().requestVideoFrameCallback ? 'yes' : 'no') +
      ', speed ' + (rateWorks === null ? '?' : rateWorks ? 'yes' : 'no') + ', gapless ' + (gapless ? 'yes' : 'no');
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
  // Samsung panels: one video at a time, and never skip inside a video. A skip
  // there takes over half a second and lands late, so correcting with skips
  // makes it worse (Holt, 24/11/2026). See calmTick.
  var calm = params.get('calm') === '1' || (/Tizen/i.test(navigator.userAgent) && params.get('calm') !== '0');
  var gapless = !calm && params.get('gapless') !== '0';
  var gaplessNote = gapless ? '' : calm ? 'off on Samsung screens' : 'turned off in the address';
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
  // How long this screen takes from play() to a moving picture. Learned at
  // each change, and the next video is started that much early.
  var MAX_LEAD_MS = 3000;
  var startLeadMs = Math.max(calm ? -1000 : 0, Math.min(MAX_LEAD_MS, parseFloat(store('screens.startLead') || '0') || 0));
  var MIN_LEAD_MS = -1000;    // a screen that runs early is started late
  var startErrMs = null;      // how far off the last start landed
  var startCheck = null;      // {el, from, list} while a start is measured
  var prerollTimer = null;
  var frame = null;           // {el, mediaMs, at} the last frame shown
  var frameToken = 0;
  var measureBy = 'time';     // what drift is measured from
  var driftLog = [];          // {at, drift} of the last second or so
  var lastPosSeen = { pos: -1, at: 0 };
  var posSteps = 0;           // times the position stood still between ticks
  var stall = { lastPos: -1, lastFrames: -1, framesSeen: 0, since: 0, recoveries: [] };
  var lastStall = '';         // what the last freeze looked like
  var splashStalls = 0;       // freezes in a row without getting going
  var splashPlayAt = 0;       // local time of the last play() on the splash
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
    if (state.anchorServerMs) elapsed = groupNow() - state.anchorServerMs;
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

  /** Open `url` on `el`, from this screen's own copy if it has one. */
  function setVideo(el, url) {
    if (el._mse) clearVideo(el);
    el._url = absUrl(url);
    var src = srcFor(url);
    el._local = src.indexOf('blob:') === 0;
    el._openedAt = localNow();
    el.src = src;
  }

  function sameUrl(el, url) { return !!el._url && el._url === absUrl(url); }

  /** Streaming a file that is now stored here? */
  function couldBeLocal(el) {
    return !!el._url && !el._local && srcFor(el._url).indexOf('blob:') === 0;
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
    if (el._mse) { el._mse.dead = true; if (ms === el._mse) ms = null; el._mse = null; }
    el._url = null;
    el._local = false;
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
    driftLog = [];
    currentRate = r;
    try { activeEl().playbackRate = r; } catch (e) { rateWorks = false; }
  }

  function seekVideo(el, posMs) {
    var target = posMs + seekLeadMs;
    if (el.duration && target / 1000 >= el.duration) target = el.duration * 1000 - 50;
    // Only learn from seeks made mid-play. One made while the file is still
    // opening includes the download, and would teach it to aim far ahead.
    resetMeasure();
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
      img.src = srcFor(item.url);     // warm the cache so the cut is instant
      cue = { idx: n, url: item.url, ready: true, image: true };
      return;
    }
    if (localNow() - (standbyFailed[item.url] || -1e9) < 4000) return;   // it just failed: not again yet
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
    setVideo(el, item.url);
    el.load();
  }

  /** Start the standby a little early, out of sight, so its picture is moving
   *  by the time it's shown. */
  function preroll(idx) {
    if (!cue || cue.idx !== idx || !cue.ready || cue.image || cue.started) return;
    var el = standbyEl();
    el.muted = true;
    try { el.playbackRate = 1; } catch (e) { /* ignore */ }
    cue.started = true;
    playVideo(el);
  }

  /** Report each frame `el` shows, where the browser can. */
  function watchFrames(el) {
    if (!el.requestVideoFrameCallback) return;
    var token = ++frameToken;
    function next() {
      el.requestVideoFrameCallback(function (now, meta) {
        if (token !== frameToken) return;
        frame = { el: el, mediaMs: meta.mediaTime * 1000, at: performance.timeOrigin + (meta.expectedDisplayTime || now) };
        next();
      });
    }
    next();
  }

  function resetMeasure() { driftLog = []; frame = null; startCheck = null; }

  /** How far `el` is from where the clock says it should be, + = ahead.
   *  Frames shown are exact. The video's own position can be a step behind on
   *  a panel that only updates it now and then, so there the highest reading
   *  of the last second counts. */
  function measureDrift(el, posMs, now) {
    var pos = el.currentTime;
    if (pos !== lastPosSeen.pos) lastPosSeen = { pos: pos, at: now };
    else if (now - lastPosSeen.at > 120 && posSteps < 100) posSteps++;
    var f = frame && frame.el === el && Math.abs(now - frame.at) < 250 ? frame : null;
    var drift = f ? f.mediaMs - (posMs - (now - f.at)) : pos * 1000 - posMs;
    var coarse = !f && posSteps >= 10;
    measureBy = f ? 'frames' : coarse ? 'steps' : 'time';
    driftLog.push({ at: now, drift: drift });
    driftLog = driftLog.filter(function (d) { return now - d.at < (coarse ? 1200 : 700); });
    var sorted = driftLog.map(function (d) { return d.drift; }).sort(function (a, b) { return a - b; });
    return coarse ? sorted[sorted.length - 1] : sorted[Math.floor(sorted.length / 2)];
  }

  /** Learn from the start just made: land on time at the next one. */
  function checkStart(el, drift, now) {
    if (!startCheck || startCheck.el !== el || now < startCheck.from) return;
    startCheck.list.push(drift);
    if (now < graceUntil - 250) return;
    var list = startCheck.list.sort(function (a, b) { return a - b; });
    startCheck = null;
    var err = list[Math.floor(list.length / 2)];
    if (Math.abs(err) > 800) return;      // a hiccup, not a start delay
    learnStart(err, 0.6);
  }

  function learnStart(err, share) {
    startErrMs = err;
    startLeadMs = Math.max(0, Math.min(MAX_LEAD_MS, startLeadMs - err * share));
    store('screens.startLead', startLeadMs.toFixed(1));
  }

  // ── One video in a loop, for panels that are slow to skip ────────────
  // Wait on the first frame, start at the group's moment less this screen's
  // start delay, then leave it alone until it ends. Nothing is corrected while
  // it plays; a start that lands off is put right at the next one. A slot a
  // little longer than the video gives the panel time to get back to the start.
  var calmPhase = '';         // '' | opening | back | ready | playing
  var calmTimer = null;
  var calmList = [];          // drift readings since the start
  var calmSince = 0;
  var CALM_TAIL_MS = 450;
  var calmLoads = 0;          // times the file had to be opened again
  var calmLeads = [];         // the start delay each of the last starts called for
  var calmLeadUsed = 0;
  var calmInStep = false;     // the last start landed where it should
  var calmSpareMs = null;     // time left over before the last start was due, - = late
  var calmSkippedAt = -1e9;   // when a loop was last sat out
  var calmBackMs = null;      // how long it took to get back to the start

  function calmPhaseTo(p) { calmPhase = p; calmSince = localNow(); restartStallWatch(calmSince); }

  function calmReset() {
    calmInStep = false;
    clearTimeout(calmTimer);
    calmTimer = null;
    calmPhase = '';
    calmList = [];
  }

  function calmBack(el) {
    var ended = el.ended;
    try { el.pause(); } catch (e) { /* ignore */ }
    if (ended) { calmLoad(el); return; }
    if (el.currentTime > 0.01) { try { el.currentTime = 0; } catch (e) { /* ignore */ } }
    calmPhaseTo('back');
  }

  /** Open the same file again: slower than going back, and it may blank. */
  function calmLoad(el) {
    calmLoads++;
    try { el.load(); } catch (e) { /* ignore */ }
    calmPhaseTo('opening');
  }

  function calmTick(e, el, item) {
    var now = localNow();
    if (current.id !== item.id || !sameUrl(el, item.url) || (couldBeLocal(el) && calmPhase !== 'playing') || !calmPhase) {
      calmReset();
      current = { idx: 0, id: item.id, since: now, skipped: false };
      forceSwitch = false;
      setRate(1);
      resetMeasure();
      hideIdle();
      showVideo(el);
      el.loop = false;
      el.muted = !state.audioOn;
      if (!sameUrl(el, item.url) || couldBeLocal(el)) { setVideo(el, item.url); el.load(); }
      else calmBack(el);
      if (calmPhase !== 'back') calmPhaseTo('opening');
      return;
    }
    if (document.hidden) { restartStallWatch(now); return; }
    var fileMs = (el.duration || 0) * 1000;

    if (calmPhase === 'opening') {
      if (el.readyState >= 2) calmBack(el);
      else if (now - calmSince > 30000) calmReopen(el, 'did not open');
      return;
    }
    if (calmPhase === 'back') {
      if (!el.seeking && el.readyState >= 2 && el.currentTime < 0.2) { calmBackMs = now - calmSince; calmList = []; calmPhaseTo('ready'); }
      else if (now - calmSince > 2500) { lastStall = 'did not go back to the start'; calmLoad(el); }
      return;
    }
    if (calmPhase === 'ready') {
      if (!calmTimer) {
        var wait = e.waitMs > 0 ? e.waitMs : item.durMs - e.posMs;
        if (wait <= 700 + Math.max(0, startLeadMs)) {
          // Too late for this start, usually because the last one was: sit one
          // loop out and start on time. Not again soon, in case there simply
          // isn't enough time between the end of the video and its next start.
          if (wait - startLeadMs < -60 && now - calmSkippedAt > 300000) {
            calmSkippedAt = now;
            calmInStep = false;
            wait += item.durMs;
            log('Waiting one loop to start on time');
          }
          calmSpareMs = wait - startLeadMs;
          calmLeadUsed = startLeadMs;
          calmTimer = setTimeout(function () {
            calmTimer = null;
            calmList = [];
            resetMeasure();
            watchFrames(el);
            playVideo(el);
            calmPhaseTo('playing');
          }, Math.max(0, wait - startLeadMs));
        }
      }
      return;
    }

    // Playing.
    // Go back just before the end: once a Samsung video has ended, it ignores
    // being sent to the start (QM43C, 24/11/2026).
    if (el.ended || (fileMs > 0 && el.currentTime * 1000 >= fileMs - CALM_TAIL_MS)) { calmLearn(); calmBack(el); return; }
    if (el.paused && !el.seeking && now - calmSince > 3000 && now - splashPlayAt > 2000) {
      splashPlayAt = now;
      playVideo(el);
    }
    if (!pictureMoved(el, now) && now - stall.since > 10000) {
      noteStall(now);
      calmReopen(el, 'stopped at ' + el.currentTime.toFixed(1) + ' s (' + videoState(el) + ')');
      return;
    }
    if (el.currentTime > 0.8) {
      // The video may be on the far side of the group's loop point from the
      // clock: take the nearer way round.
      var pos = e.waitMs > 0 ? -e.waitMs : e.posMs;
      var d = measureDrift(el, pos, now);
      if (d > item.durMs / 2) d -= item.durMs;
      else if (d < -item.durMs / 2) d += item.durMs;
      lastDriftMs = d;
      if (now - calmSince > 1500 && now - calmSince < 6000) calmList.push(d);
    }
  }

  /** How did this start land? The first one is taken as it is. */
  function calmLearn() {
    // A start that was already late says nothing about this screen's delay.
    if (calmList.length < 5 || calmSpareMs === null || calmSpareMs < 0) { calmInStep = false; return; }
    var list = calmList.sort(function (a, b) { return a - b; });
    calmList = [];
    var err = list[Math.floor(list.length / 2)];
    // Ahead with nothing left to take off, or a long way out: not a start delay.
    calmInStep = Math.abs(err) <= 120;
    if (Math.abs(err) > MAX_LEAD_MS) return;
    // The middle of the last five, so one odd start doesn't move it.
    startErrMs = err;
    calmLeads.push(calmLeadUsed - err);
    if (calmLeads.length > 5) calmLeads.shift();
    var sorted = calmLeads.slice().sort(function (a, b) { return a - b; });
    startLeadMs = Math.max(MIN_LEAD_MS, Math.min(MAX_LEAD_MS, sorted[Math.floor(sorted.length / 2)]));
    store('screens.startLead', startLeadMs.toFixed(1));
  }

  // ── The "lining up" mark ─────────────────────────────────────────────
  // Not for more than 3 minutes at a time: a screen that can't get in step
  // says so in the CMS, not to customers.
  var liningUpSince = 0;

  function isLiningUp() {
    if (!state.syncGroup || !(state.items.length || ms)) return false;
    if (state.next || clock.offsetMs === null) return true;
    if (ms) return lastDriftMs === null || Math.abs(lastDriftMs) > 120;
    var e = expected();
    if (!e) return false;
    if (e.waitMs > 0) return true;
    if (calmPhase) return !calmInStep;
    if (calm) return false;               // several videos: nothing to wait for
    var item = state.items[e.idx];
    if (!item || item.type === 'image' || current.skipped) return false;
    return lastDriftMs === null || Math.abs(lastDriftMs) > 150;
  }

  function showLiningUp() {
    var now = localNow();
    if (!isLiningUp()) liningUpSince = 0;
    else if (!liningUpSince) liningUpSince = now;
    syncingEl.className = liningUpSince && now - liningUpSince < 180000 ? 'on' : '';
  }

  // ── One video, fed from this screen's own copy ───────────────────────
  // The stored file is cut into pieces (mp4.js) and fed to the video element
  // as one stream that never ends. Nothing comes over the network, nothing
  // restarts, and the next loop is placed where the clock says it belongs: its
  // start is worked out a few seconds ahead from how far off this screen is
  // now, and the last picture is held, or the tail trimmed, to fit.
  var MS_START = 1;           // where the first loop starts on the stream, s
  var MS_AHEAD = 8;           // seconds kept ready
  var MS_DECIDE = 4;          // how long before a loop ends its successor is placed
  var MS_MAX_BYTES = 80 * 1024 * 1024;
  var ms = null;
  var msFails = [];
  var msNote = '';
  var msOff = (function () {
    if (params.get('stream') === '0' || !window.ScreensMp4 || !window.MediaSource || !window.caches) return true;
    var v = (store('screens.msOff') || '').split('|');
    if (v[0] === VERSION && Math.abs(Date.now() - Number(v[1])) < 24 * 3600 * 1000) { msNote = v[2] || ''; return true; }
    return false;
  })();

  function msUsable(url) {
    if (!calm || msOff || state.audioOn) return false;
    if (msFails.length && localNow() - msFails[msFails.length - 1] < 4000) return false;
    var k = kept[absUrl(url)];
    return !!k && k.state === 'ready' && k.bytes <= MS_MAX_BYTES && !k.msBad && localNow() >= (k.readAt || 0);
  }

  function msReset() {
    if (!ms) return;
    var m = ms;
    ms = null;
    m.dead = true;
    if (m.objectUrl) URL.revokeObjectURL(m.objectUrl);
    if (m.el._mse === m) { m.el._mse = null; clearVideo(m.el); }
    resetMeasure();
    lastDriftMs = null;
  }

  function msFail(why, fileIsBad) {
    log('Stored stream: ' + why);
    lastStall = 'stored stream ' + why;
    msNote = why;
    var url = ms && ms.url;
    msReset();
    if (fileIsBad && url && kept[url]) { kept[url].msBad = true; return; }
    var now = localNow();
    msFails = msFails.filter(function (t) { return now - t < 600000; });
    msFails.push(now);
    if (msFails.length >= 4) {
      msOff = true;
      store('screens.msOff', VERSION + '|' + Date.now() + '|' + why.replace(/\|/g, ' ').slice(0, 60));
      log('This screen plays without the stored stream for a day');
    }
  }

  /** The stored file can't be read. One TV at Holt came back from a night with
   *  the power off like that (25/11/2026): the file was listed, at the right
   *  size, and reading it failed every time. Read it once more, then download
   *  it again. Until that's done the video streams. */
  function msReadFail(m, why) {
    var k = kept[m.url];
    if (!k || k.downloads >= 3) { msFail('cannot read the stored file (' + why + ')'); return; }
    log('Stored stream: cannot read the stored file (' + why + ')');
    lastStall = msNote = 'cannot read the stored file (' + why + ')';
    msReset();
    k.readFails = (k.readFails || 0) + 1;
    k.readAt = localNow() + 4000;
    if (k.readFails < 2) return;
    k.readFails = 0;
    if (k.src) { URL.revokeObjectURL(k.src); k.src = null; }
    k.again = true;
    k.state = 'failed';
    k.retryAt = 0;
    keepContent();
  }

  function msOpen(el, url) {
    msReset();
    if (calmPhase) calmReset();
    clearVideo(el);
    setRate(1);
    resetMeasure();
    lastDriftMs = null;
    var m = ms = { el: el, url: absUrl(url), queue: [], loops: [], seq: 1, openedAt: localNow(), note: 'opening' };
    el.loop = false;
    el.muted = true;
    showVideo(el);
    caches.open(MEDIA_CACHE).then(function (c) { return c.match(m.url); }).then(function (hit) {
      if (!hit) throw new Error('not stored');
      return hit.arrayBuffer();
    }).then(function (buf) {
      if (m.dead) return;
      if (kept[m.url]) { kept[m.url].readFails = kept[m.url].notReplaced = 0; kept[m.url].again = false; }
      try { m.track = ScreensMp4.parse(buf); } catch (e) { msFail('cannot read this file (' + e.message + ')', true); return; }
      if (!MediaSource.isTypeSupported(m.track.mime)) { msFail('cannot play ' + m.track.codec, true); return; }
      m.media = new MediaSource();
      m.media.addEventListener('sourceopen', function () {
        if (m.dead || m.sb) return;
        try {
          m.sb = m.media.addSourceBuffer(m.track.mime);
          m.sb.mode = 'segments';
        } catch (e) { msFail('no buffer (' + e.name + ')'); return; }
        m.sb.addEventListener('updateend', function () { msPump(m); });
        m.sb.addEventListener('error', function () { if (!m.dead) msFail('buffer error'); });
        msQueue(m, ScreensMp4.init(m.track));
        m.note = 'ready';
        tick();
      });
      m.objectUrl = URL.createObjectURL(m.media);
      el._url = m.url;
      el._local = false;
      el._mse = m;
      el._openedAt = localNow();
      el.src = m.objectUrl;
    }, function (e) {
      if (!m.dead) msReadFail(m, e && e.message || String(e));
    }).catch(function (e) { if (!m.dead) msFail(e.message || String(e)); });
  }

  function msQueue(m, bytes) { m.queue.push(bytes); msPump(m); }

  function msPump(m) {
    if (m.dead || !m.sb || m.sb.updating) return;
    if (!m.queue.length) {
      // Let go of what has been played, now and then.
      var from = m.sb.buffered.length ? m.sb.buffered.start(0) : 0;
      if (m.el.currentTime - from > 30) { try { m.sb.remove(0, m.el.currentTime - 10); } catch (e) { /* later */ } }
      return;
    }
    try {
      m.sb.appendBuffer(m.queue[0]);
      m.queue.shift();
      m.full = 0;
    } catch (e) {
      if (e.name === 'QuotaExceededError' && (m.full = (m.full || 0) + 1) < 20) {
        try { m.sb.remove(0, Math.max(0.1, m.el.currentTime - 2)); } catch (x) { /* later */ }
        return;
      }
      msFail('append failed (' + e.name + ')');
    }
  }

  /** The loop on the stream at `t` seconds, or the first. */
  function msLoopAt(m, t) {
    for (var i = m.loops.length - 1; i >= 0; i--) if (m.loops[i].start <= t + 0.001) return m.loops[i];
    return m.loops[0];
  }

  /** Feed the piece after the last one fed. */
  function msFeed(m, periodMs, pos) {
    var t = m.track, loop = m.loops[m.loops.length - 1];
    var g = t.gops[m.nextGop];
    var lastGop = m.nextGop === t.gops.length - 1;
    var base = loop.start * t.timescale;
    if (!lastGop) {
      msQueue(m, ScreensMp4.piece(t, m.seq++, g.from, g.to, base));
      m.fedTo = loop.start + t.samples[t.gops[m.nextGop + 1].from].pts / t.timescale;
      m.nextGop++;
      return true;
    }
    // The end of a loop waits until it's known where the next one starts.
    var fileEnd = loop.start + t.durMs / 1000;
    var now = m.el.currentTime;
    if (fileEnd - now > MS_DECIDE && m.playing) return false;
    var next = fileEnd;
    if (pos !== null && periodMs) {
      // Ahead of the clock: start later on the stream. Behind: earlier.
      var off = m.playing && lastDriftMs !== null && msLoopAt(m, now) === loop ? lastDriftMs : 0;
      next = Math.max(now + 1, loop.start + 0.5, loop.start + (periodMs + off) / 1000);
    }
    msQueue(m, ScreensMp4.lastPiece(t, m.seq++, g, base, (next - loop.start) * t.timescale));
    m.loops.push({ start: next });
    if (m.loops.length > 6) m.loops.shift();
    m.nextGop = 0;
    m.fedTo = next;
    return true;
  }

  /** `periodMs`: length of a slot. `pos`: where in it the clock is, or null to
   *  just loop. `waitMs`: time until the group's first start. */
  function msTick(el, url, periodMs, pos, waitMs) {
    var now = localNow();
    if (!ms || ms.url !== absUrl(url) || ms.el !== el) { msOpen(el, url); return; }
    var m = ms;
    if (!m.sb || !m.track) {
      if (now - m.openedAt > 20000) msFail('did not open');
      return;
    }
    if (document.hidden) { restartStallWatch(now); return; }
    var t = m.track, i;
    var endsAt = t.durMs / 1000;

    if (!m.loops.length) {
      // Begin a little ahead of the clock, at the top of the next loop if
      // there's no time left in this one.
      var at = 0;
      m.startIn = null;
      if (pos !== null) {
        if (waitMs > 0) m.startIn = waitMs;
        else {
          at = (pos + 1500) / 1000;
          if (at * 1000 >= periodMs || at >= endsAt - 0.3) { at = 0; m.startIn = periodMs - pos; }
          else m.startIn = 1500;
        }
      }
      m.startAt = now + (m.startIn || 0);
      m.loops = [{ start: MS_START }];
      m.nextGop = 0;
      for (i = 0; i < t.gops.length; i++) if (t.gops[i].pts / t.timescale <= at + 0.001) m.nextGop = i;
      m.fedTo = MS_START + t.gops[m.nextGop].pts / t.timescale;
      m.target = MS_START + at;
      m.state = 'filling';
      restartStallWatch(now);
    }

    var head = m.state === 'playing' ? el.currentTime : m.target;
    for (i = 0; i < 6 && m.queue.length < 4 && m.fedTo - head < MS_AHEAD; i++) if (!msFeed(m, periodMs, pos)) break;
    if (m.dead) return;
    msPump(m);

    if (m.state === 'filling') {
      if (now - m.openedAt > 25000 + (m.startIn || 0)) { msFail('did not fill'); return; }
      if (!m.sb.buffered.length || m.sb.buffered.end(m.sb.buffered.length - 1) < m.target + 0.5) return;
      if (!m.sought) { m.sought = true; try { el.currentTime = m.target; } catch (e) { /* ignore */ } return; }
      if (el.seeking || el.readyState < 2) return;
      hideIdle();
      var left = m.startAt - now;
      if (left > 250) return;
      m.state = 'starting';
      setTimeout(function () {
        if (m.dead) return;
        watchFrames(el);
        playVideo(el);
        m.playing = true;
        m.state = 'playing';
        restartStallWatch(localNow());
      }, Math.max(0, left));
      return;
    }
    if (m.state !== 'playing') return;

    hideIdle();
    if (el.paused && !el.seeking && now - splashPlayAt > 2000) { splashPlayAt = now; playVideo(el); }
    if (!pictureMoved(el, now) && now - stall.since > 8000) {
      noteStall(now);
      msFail('stopped at ' + el.currentTime.toFixed(1) + ' s (' + videoState(el) + ')');
      return;
    }
    if (pos === null) { lastDriftMs = null; return; }

    // How far off is it? Not near a loop point, where the clock and the
    // stream can be on different sides of it.
    var loop = msLoopAt(m, el.currentTime);
    if (pos > 600 && pos < periodMs - 600) {
      var d = measureDrift(el, loop.start * 1000 + pos, now);
      if (d > periodMs / 2) d -= periodMs;
      else if (d < -periodMs / 2) d += periodMs;
      lastDriftMs = d;
      // Too far to take up at a loop point: begin again.
      if (Math.abs(d) > 2500) {
        if (!m.farSince) m.farSince = now;
        else if (now - m.farSince > 3000) { log('Stored stream ' + Math.round(d) + ' ms out, starting again'); msOpen(el, url); }
      } else m.farSince = 0;
    }
  }

  function calmReopen(el, why) {
    lastStall = why;
    log('Video ' + why + ', opening it again');
    calmReset();
    clearVideo(el);
  }

  /** Cut to the item `e` names. */
  function switchTo(e) {
    var item = state.items[e.idx];
    var cued = cue && cue.idx === e.idx && cue.url === item.url && cue.ready;
    var running = !!(cued && cue.started);
    cue = null;
    clearTimeout(prerollTimer);
    prerollTimer = null;
    resetMeasure();
    forceSwitch = false;
    setRate(1);
    rateCheck = null;
    current = { idx: e.idx, id: item.id, since: localNow(), skipped: false };
    hideIdle();

    if (item.type === 'image') {
      imageEl.src = srcFor(item.url);
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
      if (e.posMs > 250) { try { next.currentTime = e.posMs / 1000; } catch (x) { /* ignore */ } }
      if (!running) playVideo(next);
      showVideo(next);
      prev.pause();
      watchFrames(next);
      // Let it settle, see how far off it landed, and only then correct it.
      graceUntil = localNow() + 1600;
      startCheck = e.posMs > 250 ? null : { el: next, from: localNow() + 500, list: [] };
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
    watchFrames(el);
    el.muted = !state.audioOn;
    if (!sameUrl(el, item.url) || couldBeLocal(el)) {
      el.onloadeddata = null;
      el.onloadedmetadata = function () {
        el.onloadedmetadata = null;
        var now = expected();
        if (now && now.waitMs > 0) { el.currentTime = 0; return; }   // waiting for a group start
        if (now && now.idx === current.idx) seekVideo(el, now.posMs);
        playVideo(el);
      };
      setVideo(el, item.url);
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
      var k = el._url && kept[el._url];
      if (k && k.localFailed && !el._local && !localOff) turnOffLocal();
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
    el.addEventListener('ended', function () { if (calmPhase) tick(); });
    el.addEventListener('seeked', function () {
      if (calmPhase) tick();
      if (!pendingSeekTarget || el !== activeEl()) return;
      // Learn how long a seek takes to land, so the next one aims ahead.
      if (pendingSeekTarget.learn) {
        var landedLate = (localNow() - pendingSeekTarget.atLocal) - seekLeadMs;
        seekLeadMs = Math.max(0, Math.min(500, seekLeadMs + landedLate * 0.5));
      }
      pendingSeekTarget = null;
    });
    el.addEventListener('error', function () {
      if (el._mse) { if (el._mse === ms && el.error) msFail('error ' + el.error.code); return; }
      if (el._local) { localFailed(el, 'error ' + (el.error ? el.error.code : '?')); return; }
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
    if (calm && abs < 2500) return;       // it lines up again at the next video
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
    else if (abs > (measureBy === 'steps' ? 80 : 25) || (currentRate !== 1 && abs > 8)) target = drift > 0 ? 0.98 : 1.02;
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

  function startSplash(el) {
    el.loop = true;
    el.muted = !state.audioOn;
    setVideo(el, state.splashUrl);
    showVideo(el);
    playVideo(el);
    restartStallWatch(localNow());
  }

  /** The splash has no clock to follow, so just keep it moving: a panel can
   *  pause a video, stop at the end of a loop or freeze by itself. */
  function watchSplash(el) {
    var now = localNow();
    // It started as a stream and is now stored: change over as it loops.
    if (couldBeLocal(el) && (el.ended || el.currentTime < stall.lastPos - 1)) {
      log('Splash now plays from this screen');
      startSplash(el);
      return;
    }
    if (document.hidden) { restartStallWatch(now); return; }
    if (pictureMoved(el, now)) return;
    if (el.ended) { try { el.currentTime = 0; } catch (e) { /* ignore */ } }
    if (el.currentTime > 0.5) splashStalls = 0;
    if ((el.paused || el.ended) && el.readyState >= 2 && now - splashPlayAt > 2000) {
      splashPlayAt = now;
      playVideo(el);
    }
    // Give a slow download longer than a picture that has stopped.
    // Wait longer each time it doesn't get going: a slow start would
    // otherwise be cut off and started again for ever.
    var limit = (el.readyState < 3 && !el.error ? 30000 : 10000) * Math.min(4, Math.pow(2, splashStalls));
    if (now - stall.since <= limit) return;
    splashStalls++;
    var stuckMs = now - stall.since;
    noteStall(now);
    lastStall = (el._local ? 'stored' : 'stream') + ' stopped at ' + el.currentTime.toFixed(1) + ' s for ' +
      Math.round(stuckMs / 1000) + ' s (' + videoState(el) + ')';
    log('Splash ' + lastStall + ', reloading it');
    if (stall.recoveries.length >= 3 && mayReloadPage()) { location.reload(); return; }
    clearVideo(el);           // the next tick opens it again
    restartStallWatch(now);
  }

  function restartStallWatch(now) {
    stall.lastPos = -1;
    stall.lastFrames = -1;
    stall.framesSeen = 0;
    stall.since = now;
  }

  function noteStall(now) {
    stall.recoveries.push(now);
    stall.recoveries = stall.recoveries.filter(function (t) { return now - t < 300000; });
  }

  /** True if the picture has changed since the last tick. The frame counter
   *  only counts once it has been seen to work: some panels don't fill it in. */
  function pictureMoved(el, now) {
    var pos = el.currentTime;
    var frames = framesShown(el);
    var posMoved = pos !== stall.lastPos;
    var framesMoved = frames !== null && frames !== stall.lastFrames;
    if (framesMoved && stall.lastFrames >= 0) stall.framesSeen++;
    stall.lastPos = pos;
    stall.lastFrames = frames === null ? -1 : frames;
    if (!posMoved || (frames !== null && stall.framesSeen >= 10 && !framesMoved)) return false;
    stall.since = now;
    return true;
  }

  function framesShown(el) {
    try { return el.getVideoPlaybackQuality().totalVideoFrames || null; } catch (e) { return null; }
  }

  /** At most one page reload every 10 minutes for a splash that won't play,
   *  and none while offline. */
  function mayReloadPage() {
    if (!state.online) return false;
    var last = parseFloat(store('screens.splashReloadAt') || '0') || 0;
    if (Math.abs(Date.now() - last) < 600000) return false;
    store('screens.splashReloadAt', String(Date.now()));
    return store('screens.splashReloadAt') !== null;   // no storage: can't count, so don't
  }

  function videoState(el) {
    return (el.error ? 'error ' + el.error.code : el.ended ? 'ended' : el.paused ? 'paused' : 'playing') +
      ', ready ' + el.readyState + ', network ' + el.networkState;
  }

  function tick() {
    var e = expected();
    var el = activeEl();
    if (el._local && el.readyState < 1 && !document.hidden && localNow() - el._openedAt > 8000) {
      localFailed(el, 'it did not open');
    }
    if (state.next && !e && state.next.anchor <= groupNow()) { startNext(); return; }
    if (!e) {
      if (calmPhase) { calmReset(); if (!ms) clearVideo(el); }
      current = { idx: -1, id: null, since: 0 };
      if (cue) forgetCue();
      if (state.splashUrl) {
        var k = kept[absUrl(state.splashUrl)];
        if (msUsable(state.splashUrl)) {
          // In a group, everyone showing the same splash is in step too: the
          // file's own length and the clock give the same place on each.
          var lap = ms && ms.track && ms.url === absUrl(state.splashUrl) ? ms.track.durMs : 0;
          msTick(el, state.splashUrl, lap, state.syncGroup && clock.offsetMs !== null ? (lap ? groupNow() % lap : 0) : null, 0);
          return;
        }
        if (ms) msReset();
        if (sameUrl(el, state.splashUrl)) {
          watchSplash(el);
          hideIdle();
        } else if (!el._url && k && k.state === 'loading' && !localOff && localNow() - pageStart < 20000) {
          showIdle('Loading…');          // about to be stored: start from the copy
        } else {
          startSplash(el);
          hideIdle();
        }
      } else {
        if (ms) msReset();
        showIdle(state.online ? 'No content assigned to this screen yet.' : 'Connecting…');
      }
      return;
    }
    if (!ms) el.loop = false;

    // A new loop is waiting for the group's start time: play on, open its
    // first video on the standby, and cut on the exact millisecond.
    if (state.next) {
      var toStart = state.next.anchor - groupNow();
      if (toStart <= 0) { startNext(); return; }
      if (toStart < 5000 && state.next.items.length) openOnStandby(0, state.next.items[0]);
      if (toStart < 500 && !nextTimer) {
        nextTimer = setTimeout(function () { nextTimer = null; if (state.next) startNext(); }, toStart);
      }
    }

    if (calm && state.items.length === 1 && state.items[0].type === 'video' && msUsable(state.items[0].url)
        && (ms || calmPhase !== 'playing')) {
      var only = state.items[0];
      current = { idx: 0, id: only.id, since: current.id === only.id ? current.since : localNow(), skipped: false };
      forceSwitch = false;
      msTick(activeEl(), only.url, only.durMs, e.posMs, e.waitMs);
      return;
    }
    if (ms) msReset();
    if (calm && state.items.length === 1 && state.items[0].type === 'video') {
      calmTick(e, activeEl(), state.items[0]);
      return;
    }
    if (calmPhase) calmReset();

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
    var startSoon = state.next && state.next.anchor - groupNow() < 6000;
    if (remaining < 5000 && !startSoon) prepareNext(e.idx);   // the standby is the new loop's then
    if (remaining <= 350 + startLeadMs && !boundaryTimer) {
      if (startLeadMs >= 5 && cue && cue.idx === (e.idx + 1) % state.items.length) {
        clearTimeout(prerollTimer);
        prerollTimer = setTimeout(preroll, Math.max(0, remaining - startLeadMs), cue.idx);
      }
      boundaryTimer = setTimeout(function () {
        boundaryTimer = null;
        var b = expected();
        if (!b || b.waitMs) return;
        // A one-item playlist wraps onto the same item: that's a cut too.
        if (b.idx !== current.idx || (b.posMs < 400 && localNow() - current.since > 1000)) switchTo(b);
        else if (cue && cue.started) forgetCue();   // started early for a change that isn't happening
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
    if (rateWorks === null && !calm) probeRate(el);

    var now = localNow();
    if (el.seeking) { driftLog = []; return; }
    var drift = measureDrift(el, e.posMs, now);     // + = ahead
    checkStart(el, drift, now);
    if (now < graceUntil) return;

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
      'Start    this screen is started ' + Math.round(startLeadMs) + ' ms early  ·  last landed ' +
        (startErrMs === null ? '—' : Math.round(startErrMs) + ' ms') + (calmPhase ? '  ·  ' + calmPhase : '') +
        '  ·  adjustment ' + state.trimMs +
        ' ms  ·  measured by ' + measureBy,
      'Playing  ' + (item ? (e.idx + 1) + '/' + state.items.length + ' ' + (item.title || item.id) +
        ' @ ' + (e.posMs / 1000).toFixed(2) + ' s' : state.splashUrl ? 'splash' : '—'),
      'Video    ' + (function () {
        var el = activeEl(), n = framesShown(el);
        return el.currentTime.toFixed(1) + '/' + (el.duration || 0).toFixed(1) + ' s  ·  ' + videoState(el) +
          '  ·  ' + (n === null ? 'no frame count' : n + ' frames') + '  ·  ' + el.videoWidth + '×' + el.videoHeight;
      })(),
      'Next     ' + (!gapless ? 'seamless changes ' + gaplessNote
        : cue ? (cue.idx + 1) + '/' + state.items.length + (cue.ready ? ' ready' : ' opening…')
        : 'opens 5 s before the change'),
      'Stored   ' + wantedUrls().filter(isKept).length + '/' + wantedUrls().length + ' files  ·  ' +
        (keptBytes() / 1048576).toFixed(1) + ' MB  ·  playing ' +
        (localOff ? 'from the server (this screen could not play stored videos)'
          : activeEl()._local || activeEl()._mse ? 'from this screen' : 'from the server') + '  ·  worker ' + swState,
      'Stream   ' + (ms ? (ms.state || ms.note) + '  ·  ' + (ms.loops.length ? 'loop at ' + ms.loops.map(function (l) { return l.start.toFixed(2); }).slice(-2).join(', ') : '') +
        '  ·  ' + (ms.fedTo ? (ms.fedTo - activeEl().currentTime).toFixed(1) + ' s ready' : '') : msOff ? 'off' : 'not in use') + (msNote ? '  ·  ' + msNote : ''),
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
    register().then(function () { poll(); sampleClock(8); heartbeat(); scheduleHeartbeat(); });
    setInterval(tick, 200);
    setInterval(keepContent, 5000);
    setInterval(renderDebug, 500);
    setInterval(showLiningUp, 500);
    // If registration failed (offline at boot), keep trying.
    setInterval(function () { if (!registered) register(); }, 60000);
  })();
})();
