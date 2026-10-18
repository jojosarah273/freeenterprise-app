#!/usr/bin/env python3
"""Generate the app icon set from design_assets/ICON.png.

The source is a 108x108 RGBA pixel-art emblem with transparent corners, i.e.
sized for the adaptive-icon canvas (108dp). We keep it faithful:

  * legacy mipmaps      - the artwork itself, transparency preserved
  * adaptive foreground - the artwork at 108dp per density
  * adaptive background - a single colour (values/ic_launcher_background.xml),
                          visible only at the masked edge
  * round variants      - the artwork masked to a circle for pre-API-26 launchers

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


def scaled(img, size):
    """Scale, preferring NEAREST when the factor is a whole number."""
    factor = size / img.width
    resample = Image.NEAREST if abs(factor - round(factor)) < 1e-9 and factor >= 1 else Image.LANCZOS
    method = 'NEAREST' if resample == Image.NEAREST else 'LANCZOS'
    print(f"    {img.width} -> {size}px  ({factor:g}x, {method})")
    return img.resize((size, size), resample)


def circular(img):
    mask = Image.new('L', img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(Image.composite(img.getchannel('A'), Image.new('L', img.size, 0), mask))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--background", default="#455998",
                    help="adaptive background colour (visible only at the mask edge)")
    args = ap.parse_args()

    if not os.path.exists(SRC):
        raise SystemExit(f"missing source artwork: {SRC}")
    src = Image.open(SRC).convert('RGBA')
    print(f"source artwork: {src.size[0]}x{src.size[1]} RGBA")

    for name, factor in DENSITY.items():
        d = os.path.join(RES, f'mipmap-{name}')
        os.makedirs(d, exist_ok=True)

        print(f"  {name}:")
        legacy = scaled(src, round(LEGACY_DP * factor))
        legacy.save(os.path.join(d, 'ic_launcher.png'))
        circular(legacy).save(os.path.join(d, 'ic_launcher_round.png'))
        scaled(src, round(FOREGROUND_DP * factor)).save(
            os.path.join(d, 'ic_launcher_foreground.png'))

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
