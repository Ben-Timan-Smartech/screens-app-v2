/*
 * Screens web player: service worker.
 *
 * The player stores the videos itself (player.js) and normally plays them
 * straight from there. This is for a start with no network, and for a screen
 * that won't play a stored file directly.
 *
 * - Videos (/media/, /uploaded/, /splash/): served from the cache when the
 *   player has stored them, including range requests (video elements ask for
 *   byte ranges). Otherwise from the network.
 * - The player's own files (/tv/): network first, cache as the fallback, so
 *   an update arrives on the next load but an offline reload still works.
 * - Everything else (/api/ etc.) is left alone.
 */
'use strict';

var MEDIA_CACHE = 'screens-media-v1';
var SHELL_CACHE = 'screens-shell-v1';
var SHELL_FILES = ['./', './index.html', './player.js'];

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
    event.respondWith(serveMedia(req));
  } else if (url.pathname.indexOf('/tv/') === 0) {
    event.respondWith(serveShell(req));
  }
});

function cacheKey(url) {
  var u = new URL(url);
  u.hash = '';
  return u.href;
}

function serveMedia(req) {
  var key = cacheKey(req.url);
  return caches.open(MEDIA_CACHE).then(function (cache) {
    return cache.match(key).then(function (hit) {
      // Tell the player, so it can report whether videos come from here.
      // (A download by the player itself isn't a video being played.)
      if (req.destination === 'video') {
        self.clients.matchAll().then(function (list) {
          list.forEach(function (c) { c.postMessage({ type: 'media', stored: !!hit }); });
        });
      }
      return hit ? fromCache(hit, req.headers.get('range')) : fetch(req);
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
