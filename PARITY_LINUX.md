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
| Pause menu rows: Resume, Save State, Load State, Fast-forward, Checks tracker, Reset Core, Exit to sessions | Resume, Tracker, Save state, Exit to library | differs (rows) |
| On-screen pad (touch), PAD toggle | none (desktop has no touch; controller + keyboard) | n/a |
| Tracker: key-item page then checks page, L2 opens | Two pages, `Tab` opens, Left/Right pages | same |
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
| Row label is the seed's name | the seed's name (fixed: was the file name) | same |
| Row shows **verification icons** (checksum item sprites) | verification **words** | **missing** (icons) |
| Row shows seed, save size, last played | same, as a subtitle | same |
| Actions: Play Seed, Manage Save, New seed with these flags, Save flags as Flagset, Rename Seed, Delete Seed, Back | Play Seed, New seed with these flags, Save flags as Flagset, Delete Seed, Back | missing Manage Save, Rename |

## Generation

| Android 0.1.25 | Linux | State |
| --- | --- | --- |
| Generate screen: Choose Preset, Choose Flags, Seed, Generate Seed, Back | same rows | same |
| Seed name typed by the user | assigned (`SEEDnnnn`) | **differs** (no text input) |
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
| Flagset named by the user | named from its flags (`Kmain+Cvanilla`) | **differs** (no text input) |

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

1. **Verification icons** in the session list (Android draws the checksum item sprites).
2. **Text input** for a seed's name and a flagset's name (Android prompts).
3. **Manage Save / Rename Seed** rows on a session.
4. **Engine updates** on the desktop.
5. **Base ROM picker** with validation.
6. **App version** in the status line, alongside the engine version.
7. Rows Android has and the desktop does not: Check for updates, Settings.
