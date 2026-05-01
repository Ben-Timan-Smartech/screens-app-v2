"""
Generate legacy launcher icon PNGs for the player APK's `legacy` flavor.

Renders the Screens mark (bone bars + colon on ink) at the five Android
density buckets and writes them into
`player/app/src/legacy/res/mipmap-*/ic_launcher.png`.

Run once on the dev machine (or whenever the brand mark changes):
    python gen-legacy-icons.py

Why not Android Studio's Image Asset wizard? Either is fine — this script
just keeps the icon definition in code so it's reproducible from a clean
checkout.
"""

from pathlib import Path
from PIL import Image, ImageDraw

PROJECT = Path(__file__).resolve().parent
LEGACY_RES = PROJECT / "player" / "app" / "src" / "legacy" / "res"

# Density bucket → square pixel size for the launcher icon.
DENSITIES = {
    "mdpi":     48,
    "hdpi":     72,
    "xhdpi":    96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

INK  = (20, 20, 20)        # #141414
BONE = (247, 246, 242)     # #F7F6F2

# Mark geometry (matches brand/screens-mark.svg, 96-unit grid):
#   top bar    — rect at (10, 6)   size 76 × 14
#   colon dots — circles at (48,42) and (48,54), radius 6
#   bottom bar — rect at (10, 76)  size 76 × 14
# The mark fills the canvas completely on the legacy icon — adaptive icons
# need a 72/108 safe zone, raster icons don't.
def render_mark(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), INK + (255,))
    d = ImageDraw.Draw(img)
    s = size / 96.0  # scale factor

    # Top bar
    d.rectangle(
        [10 * s, 6 * s, (10 + 76) * s, (6 + 14) * s],
        fill=BONE,
    )
    # Upper colon dot
    d.ellipse(
        [(48 - 6) * s, (42 - 6) * s, (48 + 6) * s, (42 + 6) * s],
        fill=BONE,
    )
    # Lower colon dot
    d.ellipse(
        [(48 - 6) * s, (54 - 6) * s, (48 + 6) * s, (54 + 6) * s],
        fill=BONE,
    )
    # Bottom bar
    d.rectangle(
        [10 * s, 76 * s, (10 + 76) * s, (76 + 14) * s],
        fill=BONE,
    )
    return img


def main() -> None:
    for bucket, px in DENSITIES.items():
        out_dir = LEGACY_RES / f"mipmap-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        img = render_mark(px)
        # Standard Android pre-26 launcher uses these two filenames; some
        # pre-Material launchers also look for `_round`. Same image — round
        # vs square is the launcher's job to mask.
        img.save(out_dir / "ic_launcher.png")
        img.save(out_dir / "ic_launcher_round.png")
        print(f"  mipmap-{bucket:8s}  {px}x{px}")
    print(f"Wrote {len(DENSITIES) * 2} files to {LEGACY_RES}")


if __name__ == "__main__":
    main()
