/*
 * Screens web player: service worker.
 *
 * Keeps each playlist video on the screen so it plays from local storage,
 * keeps playing when the network drops, and doesn't re-download every loop.
 *
 * - Videos (/media/, /uploaded/, /splash/): served from the cache when we
 *   have them, including range requests (video elements ask for byte
 *   ranges). On a miss the request goes to the network as normal, and the
 *   whole file is fetched once in the background for next time.
 * - The player's own files (/tv/): network first, cache as the fallback, so
 *   an update arrives on the next load but an offline reload still works.
 * - Everything else (/api/ etc.) is left alone.
 */
'use strict';

var MEDIA_CACHE = 'screens-media-v1';
var SHELL_CACHE = 'screens-shell-v1';
var SHELL_FILES = ['./', './index.html', './player.js'];
var filling = {};      // url -> true while a background download runs
var queue = [];        // precache queue, downloaded one at a time
var queueRunning = false;

self.addEventListener('install', function (event) {
  self.skipWaiting();
  event.waitUntil(caches.open(SHELL_CACHE).then(function (c) { return c.addAll(SHELL_FILES); }).catch(function () {}));
});

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim());
});

function isMedia(url) {
  return url.pathname.indexOf('/media/') === 0 ||
         url.pathname.indexOf('/uploaded/') === 0 ||
         url.pathname.indexOf('/splash/') === 0;
}

self.addEventListener('fetch', function (event) {
  var req = event.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (isMedia(url)) {
    event.respondWith(serveMedia(event, req));
  } else if (url.pathname.indexOf('/tv/') === 0) {
    event.respondWith(serveShell(req));
  }
});

function cacheKey(url) {
  var u = new URL(url);
  u.hash = '';
  return u.href;
}

function serveMedia(event, req) {
  var key = cacheKey(req.url);
  return caches.open(MEDIA_CACHE).then(function (cache) {
    return cache.match(key).then(function (hit) {
      if (hit) return fromCache(hit, req.headers.get('range'));
      event.waitUntil(fill(key));
      return fetch(req);
    });
  });
}

/** Answer a (possibly ranged) request from a cached full response. */
function fromCache(hit, range) {
  return hit.blob().then(function (blob) {
    var type = hit.headers.get('Content-Type') || 'video/mp4';
    var size = blob.size;
    if (!range) {
      return new Response(blob, {
        status: 200,
        headers: { 'Content-Type': type, 'Content-Length': String(size), 'Accept-Ranges': 'bytes' }
      });
    }
    var m = /bytes=(\d*)-(\d*)/.exec(range);
    var start = m && m[1] ? parseInt(m[1], 10) : 0;
    var end = m && m[2] ? parseInt(m[2], 10) : size - 1;
    if (m && !m[1] && m[2]) { start = Math.max(0, size - parseInt(m[2], 10)); end = size - 1; } // suffix range
    end = Math.min(end, size - 1);
    if (start > end || start >= size) {
      return new Response('', { status: 416, headers: { 'Content-Range': 'bytes */' + size } });
    }
    return new Response(blob.slice(start, end + 1), {
      status: 206,
      headers: {
        'Content-Type': type,
        'Content-Length': String(end - start + 1),
        'Content-Range': 'bytes ' + start + '-' + end + '/' + size,
        'Accept-Ranges': 'bytes'
      }
    });
  });
}

/** Download the whole file once and keep it. */
function fill(key) {
  if (filling[key]) return Promise.resolve();
  filling[key] = true;
  return caches.open(MEDIA_CACHE).then(function (cache) {
    return cache.match(key).then(function (hit) {
      if (hit) return;
      return fetch(key, { cache: 'no-store' }).then(function (res) {
        // Only a complete 200 is worth keeping; a 206 would be a fragment.
        if (res.status === 200) return cache.put(key, res);
      });
    });
  }).catch(function () { /* try again next time it plays */ })
    .then(function () { delete filling[key]; });
}

function runQueue() {
  if (queueRunning) return Promise.resolve();
  queueRunning = true;
  var next = function () {
    var key = queue.shift();
    if (!key) { queueRunning = false; return Promise.resolve(); }
    return fill(key).then(next);
  };
  return next();
}

self.addEventListener('message', function (event) {
  var data = event.data || {};
  if (data.type !== 'precache' || !Array.isArray(data.urls)) return;
  var keep = {};
  data.urls.forEach(function (u) {
    var key = cacheKey(u);
    keep[key] = true;
    if (queue.indexOf(key) < 0) queue.push(key);
  });
  event.waitUntil(
    caches.open(MEDIA_CACHE).then(function (cache) {
      // Drop videos that are no longer on this screen's playlist so the
      // panel's storage doesn't fill up over the months.
      return cache.keys().then(function (keys) {
        return Promise.all(keys.filter(function (k) { return !keep[k.url]; })
          .map(function (k) { return cache.delete(k); }));
      });
    }).then(runQueue)
  );
});

function serveShell(req) {
  return fetch(req).then(function (res) {
    if (res.ok) {
      var copy = res.clone();
      caches.open(SHELL_CACHE).then(function (c) { c.put(req, copy); });
    }
    return res;
  }).catch(function () {
    return caches.match(req).then(function (hit) {
      return hit || caches.match('./index.html');
    });
  });
}
