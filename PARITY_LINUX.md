# Android 0.1.25 → Linux parity audit

Status of every behaviour in the Android 0.1.25 build against the Linux dev build.
Written after the drift became a real risk: the two must be the same program, not two
programs with the same name.

Legend: **same** = behaves and reads the same; **differs** = works but not identical;
**missing** = not implemented.

## Play

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Own Snes9x core, in-process, shipped in the app | Own core (`libretro.so`), in-process, dlopen'd — **not** RetroArch | same |
| Emulator thread at 60.0988 Hz, audio clock | Emulator loop, audio-paced when a device exists | same |
| Opening pause / tracker / dialog **pauses** the game | Menus pause the loop (fixed in this pass; it used to keep running) | same |
| Fast-forward is a **toggle** (menu row on/off, and R2) | `F` and `R2` now toggle | same |
| Save state + load state | `F5` / `F9`, one state per seed | same |
| Battery save written beside the ROM | `<name>.srm` beside the ROM | same |
| Pause menu rows: Resume, Save State, Load State, Fast-forward, Checks tracker, Reset Core, Exit to sessions | the same seven rows, same values ("none" greyed, "off"/"on") | same |
| Save State saves, then returns to the game | same | same |
| Load State present, disabled when there is none | same | same |
| Fast-forward toggled from the menu (row shows on/off) | same | same |
| Reset Core, confirmed | same, confirmed | same |
| Exit to sessions, confirmed ("Leave the game? Your battery save is written to FF4_FE first.") | same, same wording | same |
| On-screen pad (touch), PAD toggle | none (desktop has no touch; controller + keyboard) | n/a |
| Tracker page 1: "PAGE 1 / 2 KEY ITEM CHECKS", locations with ticks, "N / M checked", legend | same, same wording | same |
| Tracker page 1 ticks the engine's found flags, the free starting item, and items held or seen | same | same |
| Tracker remembers seen key items in `seen_key_items` so traded items stay ticked | same | same |
| Tracker page 2: "PAGE 2 / 2 CHECKS", TOTAL, by world, "BY AREA (most remaining first)" | same, same wording | same |
| Exit asks first | Exit asks first | same |
| In-game shortcut hint window | removed (you asked; the app has none) | same |

## Menus

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| FF4 windows: fixed status (2 lines) and hint (1 line), menu absorbs the rest | same, from the same core layout | same |
| Scrolling list, selection always visible, clipped at the box edge | now clipped and scrolled like the ScrollView (fixed in this pass) | same |
| Cursor remembered per level (categories / section / selector / presets) | same (fixed in this pass) | same |
| Back goes up a level; the main menu confirms before leaving | same | same |
| Locked cursor/focus while a modal is up | same | same |

## Main menu

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Play Free Enterprise, Generate Seed, Check for updates, Settings, Exit | Play Free Enterprise, Generate Seed, Exit | differs (platform rows) |
| Status: engine version, seed count, internet warning | engine version, seed count, pad status | same in spirit |
| Version shown as `v0.1.25` | engine version `4.7.0.Gale`; no app version yet | differs |

## Sessions (Play)

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Row label is the seed's name; subtitle "seed X · icons · save · last played" | same | same |
| Seed files are named `<name>.smc` (never the engine's binary-flags name) | same | same |
| Row shows **verification icons** (checksum item sprites) | the same four icons, from the app's own `checksum-*.png` | same |
| Row shows seed, save size, last played | same, as a subtitle | same |
| Actions: Play Seed, Manage Save, New seed with these flags, Save flags as Flagset, Rename Seed, Delete Seed, Back | Play Seed, New seed with these flags, Save flags as Flagset, Delete Seed, Back | missing Manage Save, Rename |

## Generation

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Generate screen: Choose Preset, Choose Flags, Seed, Generate Seed, Back | same rows | same |
| Seed left blank gets the engine's random alphanumeric code (e.g. ELS5JYMQY9) | same | same |
| Seed name typed by the user | typed in an FF4 window (keyboard) | same |
| Progress window with phases, new seeds/regenerates | same, from the engine's progress file | same |
| Flags canonicalised and validated by the engine | same (`check_flags`, debounced) | same |
| Old (4.6-era) flags translated with a note | translated, but no note shown | differs |
| Writes `<name>.json` beside the ROM | same keys | same |

## Flag editor

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Level 0 categories with "N on" | same | same |
| Level 1 controls, ON/OFF, indents, children when on | same | same |
| Level 2 selector with "(none)", ON marking, exclusive | same | same |
| Status: canonical flags + "Flags OK" / errors | same | same |
| Three-line hint: title [flag], description, keys | same | same |
| Cursor returns to the last row per level | same (fixed in this pass) | same |

## Presets and flagsets

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| "My flagsets" first, then the site's categories | same | same |
| Category pages with descriptions | same | same |
| Apply returns to Generate | same (fixed: used to land in the editor) | same |
| Save flagsets, delete with confirmation | same | same |
| Flagset named by the user | typed in an FF4 window when saving from a session | same |

## Data

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| `FF4_FE` with `<name>.smc/.json/.srm/.state/.spoiler.txt`, `flagsets.json` | same layout and names | same |
| Engine 4.7.0.Gale (bundled) | same engine, same directory in the package | same |
| In-app engine updates (silent) | none: replace `engine/python_ext` | **missing** |
| In-app APK updates | none | n/a (desktop) |
| Base ROM import with MD5 check | `ff2.smc` beside the app or in `FF4_FE`; no picker | **differs** |
| Classic editor fallback UI | none | n/a (Android had a legacy UI) |
| RetroArch launch option | none (self-contained) | n/a |

## Known differences, in priority order

1. **Manage Save** on a session (save slot management).
2. **Engine updates** on the desktop (Android swaps its engine silently).
3. **Base ROM picker** with MD5 validation.
4. **App version** in the status line, alongside the engine version.
5. Rows Android has and the desktop does not: Check for updates, Settings.
6. "Save Flagset" is offered when saving from a session; Android also offers it from
   the Generate screen.

## Where to take the project

The audit is the contract. Everything below is measured against it.

### What stands in the way now

1. **Five functional gaps**: Manage Save, engine updates on the desktop, the base ROM
   picker with validation, the app version in the status line, and the
   updates/settings rows.
2. **Two implementations of the same flow.** The UI model is shared, but the *flow* -
   which screen follows which, what each row does - still lives twice: once in Kotlin
   for Android, once for the desktop. That is where the drift keeps coming from.
3. **Audio** only works when the host offers a 32 kHz stereo line.
4. **Distribution**: no bundled runtime, no updater, no packaged build people can
   install.

### The plan

**1. Move the flows into the core (the structural fix).** Define each screen as data:
rows with an id, a label, a value, and an action id; and one small state machine per
flow (main menu, sessions, generation, editor, presets, pause menu). Both platforms
then execute the *same* definitions - Android rendering them with views, the desktop
with the draw list. This is the single change that stops new divergence, because a new
row or a changed action would exist once.

**2. Close the five gaps**, in that order of cheapness: app version in the status line;
Manage Save (copy/delete a seed's `.srm` and `.state`); base ROM picker with the MD5
check the app already uses; engine updates (an engine bundle zip, downloaded and
unpacked into `engine/python_ext`, verified — the same shape as Android's silent
engine update); then optional rows for updates/settings on the desktop (file-swap
updater and a settings screen with the same options).

**3. Audio done properly**: request the core's rate, and resample to whatever the host
provides, so it works on every machine instead of silently running muted.

**4. Ship it**: an AppImage (or a `.tar.gz` with a bundled JRE) containing the runtime,
`libretro.so`, the engine and the font/icon assets; `FF4_FE` beside it; an updater that
replaces the app files and applies engine updates in place without a package manager.

**5. Keep it honest**: run the audit as a checklist before each build, alongside
`scripts/selftest.sh`; add an invariant for anything that has regressed once.

### What "as good as 0.1.25" would then mean

Not more features - *no surprises*. Every screen reads the same, every row does the
same thing, the same seed generates the same ROM, saves and states live in the same
place, and the tracker says the same numbers. The Android build is the reference
implementation; the desktop should be indistinguishable in use, and the audit is how we
prove it rather than assert it.

## Changes made in this pass

* **Verification icons** in the session list, drawn from the app's
  `assets/checksum/checksum-<tile>.png` set through a new image draw op.
* **A text field** for naming: the seed on the Generate screen, a flagset when saving,
  and a seed when renaming. Keyboard only, Enter accepts, Esc cancels, and it owns the
  keyboard while open.
* **Rename Seed** on a session, renaming the ROM and every file stored beside it.
* Lists are clipped and scrolled like the app's ScrollView (selection always visible).
* Menus pause the emulator; fast-forward toggles on F and R2.
