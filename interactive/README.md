# Guided brand experiences (interactive content)

Self-contained, **single-file** HTML experiences that run on a screen and are
**touch-interactive** — the first example is the WHOOP demo for tm:rw Times Square.

Served read-only + public at `https://<host>/interactive/<name>.html`. The tablet
downloads a file **once, caches it locally, and renders from that copy**, so the
experience keeps working with no network — the same guarantee cached videos get.

## The one hard rule: no external references

**Everything must be inline.** No external `<script src>`, `<link rel=stylesheet>`,
`<img src>`, webfonts, or any other network fetch. Graphics are CSS/SVG, drawn
inline. This is what makes offline work — an external reference is a blank box on
a screen with no wifi, in a store, in front of a customer.

Verify before committing (should print nothing):

```bash
grep -oE '(src|href)[[:space:]]*=[[:space:]]*"(https?:)?//[^"]+' interactive/*.html
```

Keep files lean — they're re-fetched on change and held in device storage.
Current sizes: `whoop-demo.html` ~37 KB, `whoop-loop-45s.html` ~12 KB.

## Naming

Filenames must match `[A-Za-z0-9_-]{1,64}\.html` (the server rejects anything
else, and the strict pattern is also what blocks path traversal — see
`_serve_interactive` in `serve.py`). Prefix with the brand: `whoop-demo.html`.

## Wiring one to a screen

Set the screen's `experienceUrl` to the served URL:

```
POST /api/screens/<deviceId>/experience   { "experienceUrl": "https://<host>/interactive/whoop-demo.html" }
```

`null` clears it back to a plain video screen. https-only, and CMS-only
(permissioned `screens.push`) — deciding what a shop-floor kiosk browser loads
is not something a tablet may set for itself.

## Current files

| File | What it is |
|---|---|
| `whoop-demo.html` | Format 01 — the Guided Demo: recovery, sleep, strain, stress, healthspan, heart, coach + Specialist Mode. Customer-facing, no pitch wrapper. |
| `whoop-loop-45s.html` | Format 02 — the 45-second ambient loop. Kept for reference / future use as an in-bundle attract screen. |

Both were captured from the `tmrwwhoopdemo.netlify.app` pitch site so the content
lives here, versioned, rather than depending on that deployment staying up.
