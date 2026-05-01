# Screens — logo files

Concept A: bars + colon. Two horizontal bars from Smartech, the tm:rw colon between them.

Brand colours: `--ink: #141414` · `--bone: #F7F6F2`.

## Files

| File | Purpose |
|---|---|
| `screens-mark.svg` | Primary mark, 96-unit grid. Use for any size. |
| `screens-mark-white.svg` | White-on-dark variant. |
| `screens-mark-{64,128,256,512}.png` | Rasters on transparent. |
| `screens-mark-white-{128,256,512}.png` | White rasters on transparent. |
| `screens-lockup.svg` | Mark + "screens" wordmark. |
| `screens-lockup-white.svg` | White lockup. |
| `favicon.svg` | Favicon-tuned vector — heavier rows, snapped to 32-unit grid. |
| `favicon-{16,32,48}.png` | PNG fallbacks. |
| `favicon.ico` | Multi-resolution `.ico` (16 + 32 + 48). |
| `apple-touch-icon.png` | 180 × 180, ink on bone. |

## HTML

```html
<link rel="icon" href="/brand/favicon.svg" type="image/svg+xml">
<link rel="alternate icon" href="/brand/favicon.ico">
<link rel="apple-touch-icon" href="/brand/apple-touch-icon.png">
```

## Inline SVG (mark)

```html
<svg viewBox="0 0 96 96" width="32" height="32" aria-label="Screens">
  <rect x="10" y="6"  width="76" height="14" fill="currentColor"/>
  <circle cx="48" cy="42" r="6" fill="currentColor"/>
  <circle cx="48" cy="54" r="6" fill="currentColor"/>
  <rect x="10" y="76" width="76" height="14" fill="currentColor"/>
</svg>
```

`fill="currentColor"` lets the mark inherit text colour — drop it on any surface and it adapts.

## Clear space

Keep at least one bar-height (≈ 8% of mark width) clear on all sides.

## Don'ts

- Don't recolour. Ink on bone, or bone on ink. No tints, no gradients.
- Don't rotate or skew.
- Don't stretch; the mark is square (96 × 96).
- Don't pair with the wordmark below 24 px tall — use the mark alone.
