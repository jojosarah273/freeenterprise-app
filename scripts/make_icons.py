#!/usr/bin/env python3
"""Generate the app icon set from design_assets/ICON.png.

The source is a 108x108 RGBA pixel-art emblem drawn for the adaptive-icon
canvas (108dp per density). What each output needs differs, so they are not all
the same image scaled:

  * adaptive foreground - the canvas as drawn, margins included, because the
                          launcher's mask crops the outer ring: the emblem has to
                          sit inside the safe zone or the mask eats it
  * adaptive background - a single colour (values/ic_launcher_background.xml),
                          visible only at the masked edge
  * legacy mipmaps      - cropped to the emblem, so it fills the tile. Legacy
                          icons are never masked, so the safe-zone margin would
                          otherwise render as a small emblem in a large flat
                          square on API 24-25 launchers
  * round variants      - the same crop, gentler, because the emblem has to clear
                          the inscribed circle

Integer upscales use NEAREST so pixel art stays crisp; non-integer scales use
LANCZOS. Run from the project root:

    python3 scripts/make_icons.py [--background '#000000']
"""
import argparse, os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'design_assets', 'ICON.png')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')

# dp -> px per density
DENSITY = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
LEGACY_DP = 48        # launcher icon (pre-adaptive)
FOREGROUND_DP = 108   # adaptive foreground canvas
LEGACY_FILL = 0.86    # emblem share of a square legacy tile
ROUND_FILL = 0.68     # gentler: the emblem must clear the inscribed circle


def scaled(img, size):
    """Scale, preferring NEAREST when the factor is a whole number."""
    factor = size / img.width
    resample = Image.NEAREST if abs(factor - round(factor)) < 1e-9 and factor >= 1 else Image.LANCZOS
    method = 'NEAREST' if resample == Image.NEAREST else 'LANCZOS'
    print(f"    {img.width} -> {size}px  ({factor:g}x, {method})")
    return img.resize((size, size), resample)


def content_box(img):
    """Bounding box of everything that is not the flat corner colour."""
    bg = img.getpixel((0, 0))
    px = img.load()
    xs, ys = [], []
    for y in range(img.height):
        for x in range(img.width):
            if px[x, y] != bg:
                xs.append(x)
                ys.append(y)
    if not xs:
        return (0, 0, img.width, img.height)
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1)


def emblem_crop(src, fill):
    """Crop to the emblem, sized so it occupies `fill` of the result."""
    box = content_box(src)
    widest = max(box[2] - box[0], box[3] - box[1])
    side = min(src.width, int(round(widest / fill)))
    cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    left = max(0, min(src.width - side, cx - side // 2))
    top = max(0, min(src.height - side, cy - side // 2))
    return src.crop((left, top, left + side, top + side))


def circular(img):
    mask = Image.new('L', img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(Image.composite(img.getchannel('A'), Image.new('L', img.size, 0), mask))
    return out


def report(img, what):
    """Say how much of the tile the emblem actually covers, so a crop that
    silently failed to apply cannot pass unnoticed."""
    box = content_box(img)
    share = 100.0 * max(box[2] - box[0], box[3] - box[1]) / img.width
    print(f"    {what}: {img.width}px, emblem {share:.0f}% of the tile")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--background", default="#455998",
                    help="adaptive background colour (visible only at the mask edge)")
    args = ap.parse_args()

    if not os.path.exists(SRC):
        raise SystemExit(f"missing source artwork: {SRC}")
    src = Image.open(SRC).convert('RGBA')
    box = content_box(src)
    print(f"source artwork: {src.size[0]}x{src.size[1]} RGBA, "
          f"emblem {box[2] - box[0]}px "
          f"({100.0 * (box[2] - box[0]) / src.width:.0f}% of the canvas)")

    legacy_src = emblem_crop(src, LEGACY_FILL)
    round_src = emblem_crop(src, ROUND_FILL)

    for name, factor in DENSITY.items():
        d = os.path.join(RES, f'mipmap-{name}')
        os.makedirs(d, exist_ok=True)

        print(f"  {name}:")
        legacy = scaled(legacy_src, round(LEGACY_DP * factor))
        legacy.save(os.path.join(d, 'ic_launcher.png'))
        report(legacy, 'ic_launcher')
        round_img = scaled(round_src, round(LEGACY_DP * factor))
        report(round_img, 'ic_launcher_round (before masking)')
        circular(round_img).save(os.path.join(d, 'ic_launcher_round.png'))
        foreground = scaled(src, round(FOREGROUND_DP * factor))
        foreground.save(os.path.join(d, 'ic_launcher_foreground.png'))
        report(foreground, 'ic_launcher_foreground (uncropped, for the mask)')

    # adaptive icon definitions (API 26+)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )
    for fn in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(RES, 'mipmap-anydpi-v26', fn), 'w') as f:
            f.write(adaptive)
        print(f"  wrote mipmap-anydpi-v26/{fn}")

    with open(os.path.join(RES, 'values', 'ic_launcher_background.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- Adaptive-icon background. Only visible at the masked edge and in\n'
                '     any transparent corners, so it barely shows on API 26+. -->\n'
                '<resources>\n'
                f'    <color name="ic_launcher_background">{args.background}</color>\n'
                '</resources>\n')
    print(f"  wrote values/ic_launcher_background.xml  ({args.background})")
    print("done.")


if __name__ == '__main__':
    main()
