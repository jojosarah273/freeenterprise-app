# Third-party notices

This app bundles third-party code and artwork. Nothing here is covered by a
single project licence; the components are licensed separately as below.

---

## Software

### Randomizer engine — Free Enterprise

- Source: https://github.com/galeswift/FreeEnterpriseGaleswift (branch `DoorsRando`, v4.7.0.Gale),
  a fork of https://github.com/HungryTenor/FreeEnterprise4
- Licence: **MIT** (Copyright (c) 2018-2023 Henry Truong)
- Bundled at `app/src/main/assets/python_ext/FreeEnt` and `.../f4c`, with its data files.
- Credits (upstream README): PinkPuff, Grimoire LD, Chillyfeez, Aexoden,
  b0ardface/HungryTenor, Crow, Myself086, Myria, mxzv, Wylem, SchalaKitty,
  Steph Sybydlo, riversmccown, and others.

### SNES emulator — Snes9x

- Source: https://github.com/snes9xgit/snes9x (v1.63, `libretro` port)
- Licence: Snes9x's own licence (permissive, **not** MIT) — see
  `app/src/main/cpp/` and the upstream `LICENSE`. Retain the upstream notices.

### AES — pyaes

- Licence: **MIT**. Bundled at `app/src/main/assets/python_ext/pyaes`
  (used by the engine for its embedded report encryption).

### Lark parser

- Vendored inside the engine at `app/src/main/assets/python_ext/f4c/lark`.
- Licence: **MIT**.

---

## Artwork

Both art assets are **reproductions**, not files extracted from the game —
independently recreated in the game's visual style — and both are freely
redistributable.

### Cursor sprite — `app/src/main/assets/ff4/pointer.png`

- **Hand-made by jojosarah273** for this project.
- Licence: **MIT**, the same as this project's own code (see `LICENSE`).
- Source art kept at `design_assets/POINTER.png`.

### Font — `app/src/main/assets/fonts/ff4.ttf`

- A reproduction of Final Fantasy IV's in-game typeface by the FontStruct user
  **`gameboyf9`** (https://fontstruct.com).
- Licence: **CC0 1.0 Universal — Public Domain Dedication**
  (https://creativecommons.org/publicdomain/zero/1.0/).
- Source art kept at `design_assets/font_ff4.ttf`.

CC0 imposes **no obligations**: no attribution is required, commercial use is
permitted, and there is no share-alike condition, so the font sits happily
inside this MIT-licensed project. The credit to `gameboyf9` above is a courtesy,
not a requirement.

## Trademarks

*Final Fantasy*, *Final Fantasy IV* and related names are trademarks of Square
Enix. This project is an unofficial, fan-made tool; it is not affiliated with or
endorsed by Square Enix, and ships no game data. The font and pointer are
independent reproductions and do not imply any affiliation.

## The game itself

The app ships **no ROM**. It requires the user's own Final Fantasy II (US)
cartridge dump, validates it by MD5, and stores it privately on-device.
Generation happens entirely on-device with no network access.
