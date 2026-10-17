# Third-party notices

This app bundles third-party code and artwork. Nothing here is covered by a
single project licence; the components are licensed separately as below.

## Randomizer engine — Free Enterprise

- Source: https://github.com/galeswift/FreeEnterpriseGaleswift (branch `DoorsRando`, v4.7.0.Gale),
  a fork of https://github.com/HungryTenor/FreeEnterprise4
- Licence: **MIT** (Copyright (c) 2018-2023 Henry Truong)
- Bundled at `app/src/main/assets/python_ext/FreeEnt` and `.../f4c`, together
  with its data files.
- Free Enterprise itself credits: PinkPuff, Grimoire LD, Chillyfeez, Aexoden,
  b0ardface/HungryTenor, Crow, Myself086, Myria, mxzv, Wylem, SchalaKitty,
  Steph Sybydlo, riversmccown, and others — see the upstream README.

## SNES emulator — Snes9x

- Source: https://github.com/snes9xgit/snes9x (v1.63, `libretro` port)
- Licence: Snes9x's own licence (see `app/src/main/cpp/` sources and upstream
  `LICENSE`), which is permissive but **not** the same as MIT. Retain the
  upstream copyright notices.
- Bundled at `app/src/main/cpp/`.

## AES implementation — pyaes

- Licence: **MIT**. Bundled at `app/src/main/assets/python_ext/pyaes`.
  (Used by the engine for its embedded report encryption.)

## Lark parser

- Vendored inside the engine at `app/src/main/assets/python_ext/f4c/lark`.
- Licence: **MIT**.

## Game artwork — NOT open source

Two files are ripped from Final Fantasy IV itself and are included only so the
UI resembles the game:

| File | Origin |
|---|---|
| `app/src/main/assets/fonts/ff4.ttf` | the game's in-game font |
| `app/src/main/assets/ff4/pointer.png` | the game's menu cursor sprite |

They are **not** covered by the licences above, and Final Fantasy IV is a
trademark/copyright of Square Enix. This is the same grey area the Free
Enterprise community operates in. **Before publishing publicly, decide
deliberately** whether to ship these or replace them with original artwork
(e.g. a public pixel font and a hand-drawn cursor). Removing them costs only
cosmetic fidelity — see `DESIGN_FF4_UI.md` §2 for the design tokens.

## The game itself

The app ships **no ROM**. It requires the user's own Final Fantasy II (US)
cartridge dump and validates it by MD5. No copyrighted game data is
redistributed, and generation happens entirely on-device with no network access.
