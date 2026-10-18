# Free Enterprise — Android

An all-in-one, **offline** Android front-end for **[Free Enterprise](https://ff4fe.galeswift.com/make)**,
the Final Fantasy IV randomizer. Build flagsets, generate seeds, play them
in-app, and keep every seed, save and save state in one library — presented as
if it were part of the game itself.

The real Free Enterprise randomizer runs **on the device** (CPython via
Chaquopy), and playback uses a bundled **Snes9x** core. No network, no server,
no account, and **no game ROM in the app** — you supply your own Final Fantasy II
(US) cartridge dump.

---

## Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Install](#install)
- [Using it](#using-it)
- [Screens](#screens)
- [Architecture](#architecture)
- [The `FF4_FE` library](#the-ff4_fe-library)
- [Engine provenance](#engine-provenance)
- [Building](#building)
- [Testing](#testing)
- [Documentation](#documentation)
- [Legal](#legal)
- [Roadmap](#roadmap)

---

## What it does

**A complete FF4-native interface.** Every screen is built from the game's own
visual language: procedural blue windows with a white border and black inner
bevel (`#000083` / `#FFFFFF` / `#000000`, decoded from the original ROM's palette
data), the game's font, and its hand-shaped menu cursor. Landscape, immersive,
no action bar. Driven by a controller (D-pad + A/B) or touch, with the selector
always returning to where you left it when you back out of a menu.

**Seed generation, offline and identical to the site.** The same engine code and
the same salt values the website uses, so the same flags and seed produce the
same ROM.

- Every flag from the site's own compiled spec — currently **19 sections** and
  ~1,700 flags (WACKY, HARP, ZEROMUS, AGILITY, EXPERIENCE, FUSOYA and more)
- Item-quality controls: `Tmintier` (improve) and `Tmaxtier` (restrict), plus the
  `Tstandardish` / `Tvanillaish` / `Tsemipro` chest modes
- **Flagsets** — save a ruleset by name and roll it into fresh seeds later
- The website's preset library (race presets, introductory sets…)
- A modal progress window with **real** engine progress (compile phases and
  per-block-type detail), which locks the menus while it runs
- A post-generation sanity check that catches a malformed treasure table before
  you ever play the seed

**Play in-app.** Bundled Snes9x 1.63 libretro core, driven by our own frontend —
no RetroArch required (though it is still offered as a fallback).

- Battery saves and save states stored with the seed, written back on pause/exit
- Fast-forward toggle, quick-save, on-screen pad (off by default)
- Confirmations before reset or exit

**Library and sessions.** Every generated ROM is a *session*: metadata, battery
save and save state travel together, and renaming moves all three.

**Checks tracker.** Read from live WRAM while playing — opened checks against all
**403** checks from Free Enterprise's own treasure database, broken down per
world and per area, plus key items held.

## Requirements

| | |
|---|---|
| Android | 7.0+ (minSdk 24), `arm64-v8a` or `armeabi-v7a` |
| Storage | ~40 MB app, plus room for seeds in `FF4_FE` |
| Permissions | "All files access" (to create the shared `FF4_FE` folder) |
| Game ROM | Your own **Final Fantasy II (US) v1.0 or v1.1** dump |
| Optional | A game controller for the intended experience |

The ROM is validated by MD5 against the two known-good revisions and is stored
in app-private storage. It is never uploaded, and the app holds **no internet
permission**.

## Install

Build an APK (see [Building](#building)) and sideload it, or install from the
project's releases. Then:

1. Launch — the app asks for storage access, then for your ROM.
2. Point it at your FF2 (US) dump. It is verified and kept privately.

## Using it

**Main menu:** `Play Free Enterprise` · `Generate Seed` · `Settings` · `Exit`

**Generating a seed**
1. `Generate Seed` → `Choose Preset` or `Choose Flags` (the full editor).
2. Optionally set a seed (blank = random) and `Save Flagset` to reuse the ruleset.
3. `Generate Seed` → progress window → the ROM lands in `FF4_FE` with its metadata.

**Playing**
1. `Play Free Enterprise` → pick a session → `Play in-app`.
2. In game:

| Button | Function |
|---|---|
| D-pad / stick | move |
| A / B / X / Y | game buttons (top button opens FF4's field menu) |
| **Start** | system menu — resume · save state · load state · reset · exit |
| **Select** | checks tracker |
| **L** | quick save (battery save to `FF4_FE`) |
| **R** | fast-forward toggle |

**Session actions:** `Play Seed` · `Regenerate ROM` (same flags + seed, with a
checksum cross-check) · `Manage Save` (import/export/delete `.srm`) · `New seed
with these flags` · `Save flags as Flagset` · `Rename` · `Delete`.

## Screens

| Screen | Purpose |
|---|---|
| Main | entry point, storage/ROM gates, settings |
| Generate | current flagset, presets, flagsets, seed, generate |
| Flag editor | 19 sections → controls → selector options, with live validity and logic errors |
| Presets | the website's preset library |
| Play (library) | sessions, saves, regenerate, rename, delete |
| Player | the emulator, with pause menu and tracker overlays |
| Classic | the original tool-style UI, kept as a fallback (`Settings → Interface`) |

## Architecture

```
┌──────────────────────────── Kotlin app ─────────────────────────────┐
│  ff4ui/        FF4 design system: Window, Menu, Dialog, ProgressBar │
│                ProgressOverlay, Theme (palette, font, SNES scaling) │
│                                                                     │
│  FF4*Activity  main · generate · flag editor · presets · play ·      │
│                player (each a stack of FF4 menu layers)              │
│                                                                     │
│  SeedEngine ──► Chaquopy ──► fe_bridge.py ──► FreeEnt (4.7.0.Gale)   │
│       │                          │            f4c compiler           │
│       │                          └─ writes pct/stage to a file ─────┼─► ProgressOverlay polls
│       │                                                              │
│  EmulatorSession ──► ff4emu (JNI) ──► libretro.so (Snes9x 1.63)      │
│       │   RGB565 → SurfaceView · audio → AudioTrack · input           │
│       └─ SRAM read/write to <seed>.srm · WRAM read for the tracker    │
│                                                                     │
│  RomLibrary / RomStore / SeedLibrary / FlagsetLibrary / FlagSession  │
│  Ff4Save (save decoding) · CheckDb (403 checks) · UiSpec (flag spec) │
└─────────────────────────────────────────────────────────────────────┘
```

Key implementation notes:

- **The engine is extracted to app storage and re-extracted on every version
  change.** It used to be extracted once on first install, which meant engine
  fixes shipped in new APKs never ran — worth knowing if you touch `SeedEngine`.
- **Native libraries are extracted** (`extractNativeLibs="true"`) so the core can
  be `dlopen`ed by absolute path; the APK is smaller as a result.
- **Save states** go through the core's `retro_serialize` on the emulator thread
  via a command queue (the core is not thread-safe).
- **Progress reporting** is file-based rather than a cross-thread Chaquopy
  callback, because Python is blocking inside the engine.

## The `FF4_FE` library

Seeds live in `/storage/emulated/0/FF4_FE` (root of shared storage), reachable
from any file manager, USB or emulator. Each session is a triple:

```
FF4_FE/
├── RICKSHA.smc        the generated ROM
├── RICKSHA.json       metadata: seed, flags, binary flags, checksum, version,
│                      created, last played, linked save name
├── RICKSHA.srm        battery save
├── RICKSHA.state      save state
└── flagsets.json      your saved rulesets
```

Renaming a session moves all of its files together. ROMs dropped in by other
means are picked up automatically (metadata inferred from the filename).

## Engine provenance

| | |
|---|---|
| Randomizer | **Free Enterprise 4.7.0.Gale** — upstream `DoorsRando` branch (the code `ff4fe.galeswift.com` runs) |
| Compiler | `f4c`, from the same repository |
| AES | `pyaes` (MIT) |
| Emulator | **Snes9x 1.63**, `libretro` port |

Doors/entrances randomization is **bundled but hidden** from the menus; flipping
`UiSpec.DOORS_ENABLED` to `true` is the whole change. See
`DESIGN_FF4_UI.md` §11.

## Building

```bash
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: **JDK 17+**, Android SDK with **NDK 21.4.7075529** (pinned in
`app/build.gradle.kts`), and internet on the first build for Gradle/Chaquopy
dependencies. `local.properties` must point at your SDK and is git-ignored.

The first build compiles the entire Snes9x core for both ABIs and takes a few
minutes; later builds are fast.

## Testing

The engine is verified from the host without an emulator. The useful tricks:

- **Chest integrity:** read the pre-opened-chest table directly out of a
  generated ROM at bus `$21D9A0` (LoROM `0x21*0x8000 + (0xD9A0 & 0x7FFF)`). A
  table of all `0xFF` means every chest will read as already looted.
- **Flag effects:** compare the private spoiler logs of two seeds to confirm a
  flag did what it claims (e.g. `Tmaxtier:3` removes high-tier chest contents,
  `Tmintier:3` removes low-tier ones).
- **Progress:** generate with a progress path and poll the file to check the
  percentage sequence is monotonic.
- **Checks mapping:** the 512 treasure switches live at WRAM `$12A0`, LSB-first —
  the same array Free Enterprise's own `stats.f4c` counts.

## Helper scripts

| Script | Purpose |
|---|---|
| `scripts/make_icons.py` | Regenerates the launcher icon set from `design_assets/ICON.png` (`python3 scripts/make_icons.py [--background '#000000']`) |

## Documentation

| File | Contents |
|---|---|
| `DESIGN_FF4_UI.md` | Living design doc: palette research, design tokens, input map, roadmap, status log, doors plan |
| `WEB_UI_BREAKDOWN.md` | How the website's flag editor works, and how each piece maps onto ours |
| `ROADMAP.md` | Expansion plans: tracker, doors, UI polish, updating, iOS, online racing |
| `GOLBEZ_MODE_FEASIBILITY.md` | Feasibility of shipping the custom playable-Golbez engine as a mode |
| `RELEASING.md` | Version numbering, signing key, and the release checklist |
| `THIRD_PARTY_NOTICES.md` | Licences for everything bundled, and the artwork caveat |

## Legal

The app contains **no game ROM**, downloads nothing and requests no internet
permission. It bundles Free Enterprise (MIT), the Snes9x core, and two art
assets that are **reproductions rather than game rips**: the cursor sprite is
hand-made here (MIT) and the font is a FontStruct reproduction by **`gameboyf9`**
released under **CC0** (public domain). Both are freely redistributable, so
nothing bundled here blocks distribution — see `THIRD_PARTY_NOTICES.md` for the
full licence breakdown.

## Roadmap

See `ROADMAP.md`. In short: a fuller tracker, doors/entrances randomization,
UI refinement, in-app updates, iOS, and eventually online racing.
