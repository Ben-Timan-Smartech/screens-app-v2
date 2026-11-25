/*
 * Screens web player: reads a stored MP4 and cuts it into pieces that can be
 * fed to a video element one after another (Media Source Extensions).
 *
 * Why: Samsung's browser won't play a stored file directly, and its player
 * fetches the file again every time a video starts. Fed in pieces, the video
 * plays from the screen's own copy, loops without a break, and each loop can
 * be placed on the clock.
 *
 * H.264 video only; sound is left out. Written for Chromium M94.
 */
(function () {
  'use strict';

  function type(d, o) { return String.fromCharCode(d[o + 4], d[o + 5], d[o + 6], d[o + 7]); }

  function boxes(dv, d, from, to) {
    var out = [];
    while (from + 8 <= to) {
      var size = dv.getUint32(from), head = 8;
      if (size === 1) { size = dv.getUint32(from + 8) * 4294967296 + dv.getUint32(from + 12); head = 16; }
      if (size === 0) size = to - from;
      if (size < head || from + size > to) break;
      out.push({ type: type(d, from), start: from, body: from + head, end: from + size });
      from += size;
    }
    return out;
  }

  function one(list, t) {
    for (var i = 0; i < list.length; i++) if (list[i].type === t) return list[i];
    return null;
  }

  /** Everything needed to cut `buf` up, or throws with the reason. */
  function parse(buf) {
    var d = new Uint8Array(buf), dv = new DataView(buf);
    function kids(b) { return boxes(dv, d, b.body, b.end); }
    var moov = one(boxes(dv, d, 0, d.length), 'moov');
    if (!moov) throw new Error('no moov');
    var mdia = null, mdhd = null;
    kids(moov).forEach(function (b) {
      if (b.type !== 'trak' || mdia) return;
      var m = one(kids(b), 'mdia');
      var h = m && one(kids(m), 'hdlr');
      if (h && type(d, h.body + 4) === 'vide') mdia = m;
    });
    if (!mdia) throw new Error('no video');
    mdhd = one(kids(mdia), 'mdhd');
    var timescale = dv.getUint32(mdhd.body + (d[mdhd.body] ? 20 : 12));
    var stbl = kids(one(kids(one(kids(mdia), 'minf')), 'stbl'));

    var stsd = one(stbl, 'stsd');
    var entry = stsd.body + 8;
    if (type(d, entry) !== 'avc1') throw new Error('not avc1: ' + type(d, entry));
    var width = dv.getUint16(entry + 32), height = dv.getUint16(entry + 34);
    var avcC = one(boxes(dv, d, entry + 86, entry + dv.getUint32(entry)), 'avcC');
    if (!avcC) throw new Error('no avcC');
    function hex(n) { return (n < 16 ? '0' : '') + n.toString(16); }
    var codec = 'avc1.' + hex(d[avcC.body + 1]) + hex(d[avcC.body + 2]) + hex(d[avcC.body + 3]);

    var i, j, n, p;
    // Sizes
    var stsz = one(stbl, 'stsz'), sizes = [];
    var fixed = dv.getUint32(stsz.body + 4);
    n = dv.getUint32(stsz.body + 8);
    for (i = 0; i < n; i++) sizes.push(fixed || dv.getUint32(stsz.body + 12 + i * 4));
    // Places
    var stco = one(stbl, 'stco'), co64 = one(stbl, 'co64'), chunks = [];
    var cb = stco || co64;
    var cn = dv.getUint32(cb.body + 4);
    for (i = 0; i < cn; i++) {
      chunks.push(stco ? dv.getUint32(cb.body + 8 + i * 4)
        : dv.getUint32(cb.body + 8 + i * 8) * 4294967296 + dv.getUint32(cb.body + 12 + i * 8));
    }
    var stsc = one(stbl, 'stsc'), runs = [];
    var rn = dv.getUint32(stsc.body + 4);
    for (i = 0; i < rn; i++) runs.push([dv.getUint32(stsc.body + 8 + i * 12), dv.getUint32(stsc.body + 12 + i * 12)]);
    var offs = [], s = 0;
    for (i = 0; i < runs.length; i++) {
      var last = i + 1 < runs.length ? runs[i + 1][0] - 1 : chunks.length;
      for (j = runs[i][0] - 1; j < last; j++) {
        p = chunks[j];
        for (var k = 0; k < runs[i][1] && s < n; k++) { offs.push(p); p += sizes[s++]; }
      }
    }
    if (offs.length !== n) throw new Error('sample table');
    // Times
    var stts = one(stbl, 'stts'), durs = [];
    var tn = dv.getUint32(stts.body + 4);
    for (i = 0; i < tn; i++) {
      var c = dv.getUint32(stts.body + 8 + i * 8), dd = dv.getUint32(stts.body + 12 + i * 8);
      for (j = 0; j < c; j++) durs.push(dd);
    }
    var ctts = one(stbl, 'ctts'), ctos = [];
    if (ctts) {
      var on = dv.getUint32(ctts.body + 4);
      for (i = 0; i < on; i++) {
        var oc = dv.getUint32(ctts.body + 8 + i * 8), ov = dv.getInt32(ctts.body + 12 + i * 8);
        for (j = 0; j < oc; j++) ctos.push(ov);
      }
    }
    var stss = one(stbl, 'stss'), keys = {};
    if (stss) {
      var kn = dv.getUint32(stss.body + 4);
      for (i = 0; i < kn; i++) keys[dv.getUint32(stss.body + 8 + i * 4) - 1] = true;
    }
    var samples = [], dts = 0, first = Infinity;
    for (i = 0; i < n; i++) {
      var pts = dts + (ctos[i] || 0);
      if (pts < first) first = pts;
      samples.push({ off: offs[i], size: sizes[i], dts: dts, pts: pts, dur: durs[i] || 0, key: stss ? !!keys[i] : true });
      dts += durs[i] || 0;
    }
    samples.forEach(function (x) { x.dts -= first; x.pts -= first; });
    var gops = [];
    for (i = 0; i < n; i++) {
      if (samples[i].key || !gops.length) gops.push({ from: i, to: i + 1, pts: samples[i].pts });
      else gops[gops.length - 1].to = i + 1;
    }
    if (!n || !samples[0].key) throw new Error('does not start on a keyframe');
    return {
      data: d, timescale: timescale, width: width, height: height, codec: codec,
      mime: 'video/mp4; codecs="' + codec + '"',
      stsd: d.subarray(stsd.start, stsd.end),
      samples: samples, gops: gops, units: dts, durMs: dts * 1000 / timescale
    };
  }

  // ── Writing ────────────────────────────────────────────────────────
  function u32(n) { return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]; }
  function u16(n) { return [(n >>> 8) & 255, n & 255]; }
  function u64(n) { return u32(Math.floor(n / 4294967296)).concat(u32(n >>> 0)); }
  function zeros(n) { var a = []; while (n--) a.push(0); return a; }

  function box(t) {
    var parts = Array.prototype.slice.call(arguments, 1), len = 8, i;
    for (i = 0; i < parts.length; i++) len += parts[i].length;
    var out = new Uint8Array(len), o = 8;
    out.set(u32(len), 0);
    for (i = 0; i < 4; i++) out[4 + i] = t.charCodeAt(i);
    for (i = 0; i < parts.length; i++) { out.set(parts[i], o); o += parts[i].length; }
    return out;
  }

  function join(list) {
    var len = list.reduce(function (n, x) { return n + x.length; }, 0);
    var out = new Uint8Array(len), o = 0;
    list.forEach(function (x) { out.set(x, o); o += x.length; });
    return out;
  }

  var MATRIX = u32(0x10000).concat(zeros(12), u32(0x10000), zeros(12), u32(0x40000000));

  /** The header every stream starts with. */
  function init(t) {
    return join([
      box('ftyp', [0x69, 0x73, 0x6f, 0x35], u32(512), [0x69, 0x73, 0x6f, 0x35, 0x69, 0x73, 0x6f, 0x36, 0x6d, 0x70, 0x34, 0x31]),
      box('moov',
        box('mvhd', zeros(12), u32(1000), u32(0), u32(0x10000), u16(0x100), zeros(10), MATRIX, zeros(24), u32(2)),
        box('trak',
          box('tkhd', [0, 0, 0, 3], zeros(8), u32(1), zeros(8), zeros(16), MATRIX, u32(t.width * 65536), u32(t.height * 65536)),
          box('mdia',
            box('mdhd', zeros(12), u32(t.timescale), u32(0), u16(0x55c4), u16(0)),
            box('hdlr', zeros(8), [0x76, 0x69, 0x64, 0x65], zeros(12), [0x56, 0]),
            box('minf',
              box('vmhd', [0, 0, 0, 1], zeros(8)),
              box('dinf', box('dref', zeros(4), u32(1), box('url ', [0, 0, 0, 1]))),
              box('stbl', t.stsd, box('stts', zeros(8)), box('stsc', zeros(8)), box('stsz', zeros(12)), box('stco', zeros(8)))))),
        box('mvex', box('trex', zeros(4), u32(1), u32(1), zeros(12))))
    ]);
  }

  /** Samples `from`..`to` as one piece. `base` is where the loop starts on the
   *  stream, in the track's units. */
  function piece(t, seq, from, to, base, lastDur) {
    var entries = [], len = 0, i, x;
    for (i = from; i < to; i++) {
      x = t.samples[i];
      entries = entries.concat(u32(lastDur && i === to - 1 ? lastDur : x.dur), u32(x.size),
        u32(x.key ? 0x02000000 : 0x01010000), u32(x.pts - x.dts));
      len += x.size;
    }
    function moof(offset) {
      return box('moof',
        box('mfhd', zeros(4), u32(seq)),
        box('traf',
          box('tfhd', [0, 2, 0, 0], u32(1)),
          box('tfdt', [1, 0, 0, 0], u64(Math.round(base) + t.samples[from].dts)),
          box('trun', [1, 0, 0x0f, 1], u32(to - from), u32(offset), entries)));
    }
    var head = moof(0);
    head = moof(head.length + 8);
    var out = new Uint8Array(head.length + 8 + len), o = head.length;
    out.set(head, 0);
    out.set(u32(8 + len), o);
    out.set([0x6d, 0x64, 0x61, 0x74], o + 4);
    o += 8;
    for (i = from; i < to; i++) {
      x = t.samples[i];
      out.set(t.data.subarray(x.off, x.off + x.size), o);
      o += x.size;
    }
    return out;
  }

  /** The last group of pictures of a loop, with its last picture held until
   *  `until` (units from the loop's start). */
  function lastPiece(t, seq, gop, base, until) {
    var top = gop.from, i;
    for (i = gop.from; i < gop.to; i++) if (t.samples[i].pts > t.samples[top].pts) top = i;
    var hold = Math.round(until) - t.samples[top].pts;
    if (hold <= t.samples[top].dur) return piece(t, seq, gop.from, gop.to, base);
    var out = [piece(t, seq, gop.from, top + 1, base, hold)];
    for (i = top + 1; i < gop.to; i++) out.push(piece(t, seq, i, i + 1, base));
    return join(out);
  }

  window.ScreensMp4 = { parse: parse, init: init, piece: piece, lastPiece: lastPiece };
})();
