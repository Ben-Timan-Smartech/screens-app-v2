/*
 * Screens app for Samsung signage. A small shell that Custom App can install:
 * it opens this screen's web player (app/tv/player.js on our server) and stays
 * behind it as a watchdog. The player says it's alive every few seconds; if it
 * never starts (no network at power-on) or goes quiet, the shell opens it
 * again. The player updates from the server, so this app rarely changes.
 *
 * Plain ES5: the panel's browser is Chromium M94 at best.
 */
(function () {
  'use strict';

  var ORIGIN = '@ORIGIN@';
  var PATH = '@PATH@';          // /tv/holt/L1
  var CODE = '@CODE@';          // L1
  var VERSION = '@VERSION@';

  var FIRST_WAIT_MS = 30000;    // how long a fresh page gets to say hello
  var MAX_WAIT_MS = 60000;
  var QUIET_MS = 60000;         // a running player that goes quiet this long is reopened

  var cover = document.getElementById('cover');
  var text = document.getElementById('text');
  var frame = null;
  var openedAt = 0;
  var lastAlive = 0;            // 0 until this page has said hello
  var tries = 0;

  // A clock that only moves forwards: the panel's date jumps when it gets the
  // time from the network.
  function ticks() {
    return window.performance && performance.now ? performance.now() : Date.now();
  }

  function open() {
    if (frame) document.body.removeChild(frame);
    frame = document.createElement('iframe');
    frame.setAttribute('allow', 'autoplay; fullscreen');
    frame.src = ORIGIN + PATH + '?app=' + encodeURIComponent(VERSION);
    frame.onload = focusFrame;
    document.body.insertBefore(frame, cover);
    openedAt = ticks();
    lastAlive = 0;
    tries += 1;
    show();
  }

  function focusFrame() {
    try { frame.contentWindow.focus(); } catch (e) { /* not ready */ }
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function show() {
    if (lastAlive) { cover.style.display = 'none'; return; }
    var d = new Date();
    var clock = pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes());
    cover.style.display = 'block';
    // The clock is here because a wrong date blocks every secure address.
    text.innerHTML = '<b>Screen ' + CODE + '</b><br>' +
      (tries > 1 ? 'Can’t reach the Screens server yet. Trying again.' : 'Connecting to the Screens server…') +
      '<br>This screen’s clock says ' + clock;
  }

  window.addEventListener('message', function (ev) {
    if (ev.origin !== ORIGIN || !frame || ev.source !== frame.contentWindow) return;
    if (ev.data && ev.data.type === 'screens-alive') {
      lastAlive = ticks();
      tries = 0;
      show();
    }
  });

  // Keys land here only if the player isn't focused: pass OK on, and never let
  // Return (Back) close the app.
  document.addEventListener('keydown', function (ev) {
    if (ev.keyCode === 10009) ev.preventDefault();
    if (ev.keyCode === 13 && frame) {
      frame.contentWindow.postMessage({ type: 'screens-key', key: 'Enter' }, ORIGIN);
      focusFrame();
    }
  });

  function screenSaverOff() {
    try {
      var c = window.webapis && window.webapis.appcommon;
      if (c) c.setScreenSaver(c.AppCommonScreenSaverState.SCREEN_SAVER_OFF, function () {}, function () {});
    } catch (e) { /* the screen's own menu setting still applies */ }
  }

  setInterval(function () {
    var now = ticks();
    if (!lastAlive) {
      var wait = Math.min(FIRST_WAIT_MS + (tries - 1) * 10000, MAX_WAIT_MS);
      if (now - openedAt > wait) open(); else show();
    } else if (now - lastAlive > QUIET_MS) {
      open();
    }
  }, 2000);

  screenSaverOff();
  setInterval(screenSaverOff, 10 * 60000);
  open();
})();
