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

Both art assets are **reproductions**, not files extracted from the game. They
are recreations of the game's visual style made independently.

### Cursor sprite — `app/src/main/assets/ff4/pointer.png`

- **Hand-made by jojosarah273** for this project.
- Licence: **MIT**, the same as this project's own code (see `LICENSE`).
- Source art kept at `design_assets/POINTER.png`.

### Font — `app/src/main/assets/fonts/ff4.ttf`

- **Reproduced by the FontStruct user `gameboyf9`** and published on
  https://fontstruct.com — a recreation of Final Fantasy IV's in-game typeface.
- Source art kept at `design_assets/font_ff4.ttf`.
- **Not covered by this project's MIT licence**, and it cannot be: per
  FontStruct's terms, *"the FontStructions that are created and/or made
  available on this Site are the copyrighted work, as the case may be, of the
  respective creator"*, and FontStruct itself grants no rights. Copyright
  remains with the creator, and use is governed by the licence that creator
  selected for that FontStruction on FontStruct.

> **Action item before any public release:** confirm the licence chosen for this
> specific FontStruction on its FontStruct page (FontStruct fonts range from
> public-domain/CC variants to all-rights-reserved, and some forbid commercial
> use), record it here with a link, and keep the attribution to `gameboyf9`.
> If it turns out to be restrictive, the pragmatic alternatives are to ask the
> creator for permission, or to swap in a freely-licensed pixel font — the UI
> only depends on the file's name and a monospaced-ish pixel look, so nothing
> else has to change (see `DESIGN_FF4_UI.md` §2 for the design tokens).

---

## Trademarks

*Final Fantasy*, *Final Fantasy IV* and related names are trademarks of Square
Enix. This project is an unofficial, fan-made tool; it is not affiliated with or
endorsed by Square Enix, and ships no game data. The font and pointer are
independent reproductions and do not imply any affiliation.

## The game itself

The app ships **no ROM**. It requires the user's own Final Fantasy II (US)
cartridge dump, validates it by MD5, and stores it privately on-device.
Generation happens entirely on-device with no network access.
