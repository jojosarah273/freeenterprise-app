# Design source assets

Originals used to build the app's FF4-style UI. Copies are baked into the app at
build time under `app/src/main/assets/`.

| File | Used as | Origin | Licence |
|---|---|---|---|
| `font_ff4.ttf` | `app/src/main/assets/fonts/ff4.ttf` | Reproduction of the game's typeface by the FontStruct user **`gameboyf9`** | **CC0 1.0** (public domain dedication) — no obligations; credit is a courtesy |
| `POINTER.png` | `app/src/main/assets/ff4/pointer.png` | **Hand-made by jojosarah273** | **MIT**, same as this project |
| `ICON.png` | generated into `app/src/main/res/mipmap-*` (see `scripts/make_icons.py`) | **Drawn by jojosarah273** (GIMP) — the launcher icon | **MIT**, same as this project |

`ICON.png` is a 108x108 pixel-art emblem with transparent corners, i.e. sized
for Android's adaptive-icon canvas; `scripts/make_icons.py` turns it into the
mipmap set (legacy, round, and adaptive foreground) and a background colour.

The font and pointer are **reproductions** — independently recreated, not extracted from the
game. Both are freely redistributable, so nothing here blocks distribution; see
`THIRD_PARTY_NOTICES.md` for the full picture.

*Final Fantasy IV* is a Square Enix trademark; this project is unofficial and
unaffiliated.
