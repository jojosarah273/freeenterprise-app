# DESIGN — "FF4-native" Free Enterprise app

**Vision:** the app should feel like a *part of Final Fantasy IV*, not a utility
wearing a theme. Landscape, window menus, the game's own font, gamepad
navigation — the way `PKHeX.muai` feels like a Pokémon game. This document is
the research + design + roadmap for getting there without breaking the
currently-working app (v0.4.0 = "functional base").

---

> **Note:** the old `PLAN.md` (the original M0–M3 plan) has been retired; its
> content is folded into the roadmap and the status log below.

## 1. Research findings (all verified from data on disk)

### 1.1 The font
`font_ff4.ttf` → **"Final Fantasy IV Regular"** — 99 glyphs, complete
`A–Z`, `a–z`, `0–9`, and punctuation, unitsPerEm 2048. So we can render normal
mixed-case UI text in the game's typeface as reproduced here (no need to force
uppercase).
Staged at `app/src/main/assets/fonts/ff4.ttf`. This is a **reproduction**
(not a game rip) by the FontStruct user `gameboyf9`, released under **CC0**
(public domain); the cursor sprite (`ff4/pointer.png`) is hand-made for this
project under MIT. Neither blocks distribution — see `THIRD_PARTY_NOTICES.md`.

### 1.2 The authentic window palette
Decoded `FF4_ASMTOC/data/graphics/window_pal.bin` (SNES BGR555):

| Index | RGB | Use |
|---|---|---|
| 0 / 4 | `#000000` | background / inner shadow |
| 1 / 5 | `#000083` | **window body blue** |
| 2 | `#737373` | border shade (inactive) |
| 3 | `#FFFFFF` | **border white** |
| 6 / 7 | `#414141`, `#7B7B7B` | dimmed/inactive variants |
| 10 | `#00A400` | green accent |
| 11 | `#FFDE00` | yellow accent |
| 14 | `#FF3983` | pink accent |
| 15 | `#FF9C5A` | peach accent |

### 1.3 The window construction
Decoded the real SNES 4bpp tiles from
`FF4_ASMTOC/data/gfx/gfx/windowGraphics.bin`. The border is a **1px white line
with a 1px dark inner line** over the blue body; corners are square; several
near-identical tile variants are the FF4 window *open/close animation frames*.
The body fill is drawn as a solid `#000083` rectangle underneath.

### 1.4 The game itself keeps this code
FE's own symbol map (`docs/ff4.sym`, `docs/rom-map.txt`) names the live menu
routines in the ROM FE produces:

```
_menu_configure_and_draw_window   $01:80D9
_menu_draw_window                 $01:80F5
_menu_draw_window_border_row      $01:811C
_menu_draw_window_middle_row      $01:8152
_menu_set_palette_window_color    $01:8749
_menu_copy_palette_to_cgram       $01:80B2
```

FE additionally ships its own menu work (`scripts/custom_menu.f4c`,
`menu_kit.f4c`, `fast_menus.f4c`, `tracker.f4c`) — so an in-app tracker later
can mirror FE's own HUD ideas. `FF4_PS1_DECOMP/` has window/input primitive
references (`include/ff4_window.h`, `src/battle/window24.c`) if we ever want a
higher-resolution reference.

### 1.5 Reference app behavior (PKHeX.muai)
Game-shaped chrome: everything is a window; navigation is D-pad + A/B; the
"app" is a stack of full-screen menu pages with the same visual language
throughout; touch still works everywhere.

---

## 2. Visual language (design tokens)

```
FF4_BLUE          #000083   window body
FF4_BLUE_LIGHT    #1B1B9E   selected-row fill (derived; optional)
FF4_WHITE         #FFFFFF   border + text
FF4_BLACK         #000000   background / inner shadow line
FF4_GREY          #7B7B7B   inactive text/border
FF4_GREEN         #00A400   confirm / valid
FF4_YELLOW        #FFDE00   values / GP-like numbers
FF4_PINK          #FF3983   warnings
FF4_PEACH         #FF9C5A   secondary warnings

Window:  1px white border, 1px black inner line, square corners, #000083 body
Text:    FF4 font, white, 1px hard drop shadow (offset +1,+1, black)
Cursor:  blinking game-style pointer on the focused row; focused window's
         border becomes bright white while others sit at #FFFFFF dimmed
Grid:    everything snapped to an 8px SNES cell grid
Canvas:  logical 256×224 (SNES), integer-scaled; black letterbox
         scale = max(1, floor(min(w/256, h/224)))  → crisp chunky pixels
```

Two rendering options (decision needed):
- **A. Procedural** — custom `View` draws the window/border/text with
  anti-alias off at integer coordinates. Fast to iterate, scales cleanly.
- **B. Extracted 9-slice** — stitch the real border tiles into a nine-patch.
  Most literal, but fixed to one pixel scale unless we author multiple sizes.

Recommendation: **A** for now (with exact FF4 colors/border geometry), so the
UI is resolution-independent; B remains possible later for purists.

---

## 3. Interaction model

**Controller (primary):**

| Input | Action |
|---|---|
| D-pad / left stick | move focus between rows & windows |
| A | confirm / select |
| B | back / cancel (never loses data without confirm) |
| X | context action (e.g. regenerate seed, delete) |
| Y | secondary (copy flags, toggle detail panes) |
| L / R | switch pane/tab (e.g. flag sections) |
| Start | open/close main menu |
| Select | settings window |

**Touch (parallel, never a second-class path):** tap = A at that position,
long-press = context window, on-screen D-pad optional in settings.

**Focus behavior:** the focused window's border brightens; the focused row
shows the blinking cursor; submenus open as new windows (FF4 style), with the
parent window dimmed (palette indices 6/7 grey variant).

**Sound (later, optional):** FF4 cursor blip / confirm SFX from the ROM's
sound data, toggleable.

---

## 4. Screen architecture

```
TITLE (optional splash, FF4 title style)
  └─ MAIN MENU  (windows, landscape)
       ├─ GENERATE   flags summary + seed, → run engine → result window
       ├─ FLAG EDITOR  section panes → control windows (all 12 sections)
       ├─ SEEDS      list window → per-seed action windows
       ├─ ROM        base ROM status / (re)import
       └─ SETTINGS   theme, font size, controller, classic-UI toggle
  status window (top): app version · engine state · ROM state   ← replaces the
                       badly-formatted "*** ready base rom loaded" bar
  hint window (bottom): "A Select   B Back   X Context   Start Menu"
```

The status window is the fix for the current ugly top bar: it becomes a proper
FF4 window with label/value rows (VERSION / ENGINE / ROM / SEED), the app
version always visible.

---

## 5. Implementation plan (never breaking the functional base)

New package `com.freeenterprise.app.ff4ui`:

| Component | Responsibility |
|---|---|
| `FF4Theme.kt` | color/dimension tokens from §2; font loading from assets |
| `FF4Window.kt` | custom View: FF4 window chrome (body/border/shadow/dim state) |
| `FF4Text.kt` | TextView helpers: FF4 typeface, shadow, sizes on the 8px grid |
| `FF4Menu.kt` | focusable list of rows with blinking cursor + selection callbacks |
| `FF4Activity.kt` | base activity: landscape, logical-canvas scaling, dark bg |
| `GamepadRouter.kt` | key/gamepad event → semantic actions, focus graph |
| `FF4UiPrefs` | ui_mode (ff4/classic), controller prefs, appearance |

**Migration strategy (critical):**
1. Keep every existing activity and its logic untouched.
2. Add FF4-themed screens *next to* them, entering through a new main menu.
3. A setting (`ui_mode`, default **ff4**, fallback **classic**) routes at
   launch; the classic UI stays reachable in Settings — so a regression can
   never lock anyone out of a working tool.
4. Each step is a version bump and produces a green APK. Current v0.4.0 is the
   frozen fallback.

---

## 6. Roadmap

### Phase 1 — FF4 UI, all-in-one stop shop (v0.5 → v0.9)
- **v0.5.0** Design tokens + `FF4Window`/`FF4Text`/font + status window +
  landscape main menu + gamepad routing, wrapping the **existing**
  Generate / Editor / Seeds / Settings functions (no logic changes).
- **v0.6.0** Flag editor re-skinned: section pane window + control windows,
  cursor navigation, live flags/validity windows (logic unchanged; engine
  still the source of truth).
- **v0.7.0** Seed library re-skinned: per-seed windows with
  Play / Regenerate / Load-flags / Copy / Delete; ROM file management.
- **v0.8.0** Preset browser + metaconfig (hide flags / test settings) windows.
- **v0.9.0** Polish: window open/close animations, optional FF4 SFX, CRT filter
  option, controller remapping.

### Phase 2 — Sessions & save files (v1.0 → v1.x)
- **Sessions**: one entry per playthrough = {seed record, flags, ROM, `.srm`
  save(s), progress metadata, tracker state}. Multiple concurrent games.
- **Save management**: import/export `.srm` (e.g. from RetroArch), per-session
  slots, rename/duplicate, "Continue" flow.
- **SRAM awareness**: read FF4 save structure for progress display
  (characters/objectives/key items) — the foundation for trackers.

### Phase 3 — Emulator, trackers, online (v2.x+)
- **In-app SNES core**: integrate/port a Snes9x-based core (FF4 needs
  LoROM + SRAM + no special chips; FE output is plain SNES) with FF4-window
  overlay menus (save states, speed, reset, window scaling).
- **Trackers**: HUD windows fed from ROM spoiler data + live SRAM
  (objectives, key items, character locations, boss slots) — mirroring FE's
  own `tracker.f4c` concepts, but always-on-screen.
- **Online racing**: room/seed sync, race start sync, verification of flags via
  the binary-flag string + checksum tiles, spectator state sharing.

### Guardrails
- Functional base v0.4.0 always installable; classic mode always selectable.
- Every phase ships a versioned APK + version shown in the status window.
- No engine/flag-logic changes during UI work — UI consumes the same
  `fe_bridge` API it does today.

---

## 7. Status log

| Version | State |
|---|---|
| 0.4.0 | Functional base (classic UI): engine, presets, editor, seeds, RetroArch hand-off. **Kept as fallback.** |
| 0.5.0 | FF4 shell: theme/window/menu, landscape, gamepad nav, NoActionBar immersive. |
| 0.6.x | FF4 menu-style flag editor (categories → controls → selectors); POINTER.png cursor; action bar removed. |
| 0.7.0 | Flow restructure: start-up gates (storage access → ROM import), Play / Generate / Settings / Exit, `FF4_FE` library at root storage with per-seed JSON metadata, persisted current flagset, tabbed generate screen. |
| 0.8.x | *Next:* Phase-1 polish — FF4-styled dialogs (rename/seed/confirm instead of Material AlertDialog), seed list detail/sort, any bug fixes from 0.7.0 testing. |
| 0.7.1 | Bug fixes: swallowed generate errors surfaced; ROMs copied into FF4_FE with Java I/O + verification; FileProvider external path. |
| 0.7.2 | Clean-slate flags (session-only, no persistence; cleared at launch and after generation); static pointer (no blink). |
| 1.0.0 | **Phase 2 begins — sessions:** each FF4_FE ROM is a session (name/seed/check/last-played/save); list sorted by last played; per-session Manage Save (import/export/delete `.srm`); last-played tracked on Play. |
| 1.0.1 | Sessions: save files bound to seeds via metadata (`save_name`) with naming-convention fallback; **rename moves ROM + `.srm` + `.json` together**; FF4-style confirm dialogs (delete session/save); rename still uses a system dialog for the keyboard. |
| 1.1.0 | **Phase 3 begins — in-app emulator:** Snes9x 1.63 libretro core vendored + buildable (`app/src/main/cpp`), `ff4emu` JNI frontend (RGB565 direct ByteBuffer → SurfaceView, AudioTrack, joypad), `FF4PlayerActivity` with FF4 pause overlay; Select/L/R remapped to app functions; Play Seed launches in-app. |
| 1.1.1 | Emulator fixes: `extractNativeLibs=true` (dlopen path exists), core preloaded by name + SONAME fallback, RGB565-only, visible error window + no-video watchdog, Reset actually clears the overlay, RetroArch hand-off probes `com.retroarch[.aarch64\|.ra32]` and skips the chooser. |
| 1.2.0 | Player UX: on-screen pad toggleable (default off, PAD button), save states in the Select menu (`<name>.state`, carried by rename/delete), R = fast-forward **toggle**, tap-to-pause, translucent pause window. |
| 1.3.0 | A/B swapped (controller + on-screen SNES diamond); **named flagsets** (`FF4_FE/flagsets.json` — Save/Load Flagset on the Generate screen, and "New seed with these flags" / "Save flags as Flagset" from a session); **save-file progress decoding** (`Ff4Save.kt`): party & levels, gil, playtime, map, checksum-verified. |
| 1.4.0 | Input: X/Y swapped so the pad's top button opens FF4's field menu; Start = system menu, Select = tracker; confirmations before Reset/Exit. Tracker window reads **live SRAM** (party/levels, gil, playtime, map, key items) via `nativeCopySaveRam`. |
| 1.5.0 | Tracker v2: reads **live WRAM** (`nativeReadMemory`, bank $7E $1000-$17FF) instead of SRAM; **checks tracking** from the 512 treasure switches at $12A0 (LSB-first, same array FE's `stats.f4c` counts) mapped to the 403 checks in FE's treasure DB (`assets/spec/checks.json`); key items kept; party/gil/playtime dropped. |
| 1.5.1 | **Fixed an upstream fork bug that made every chest empty.** `treasure_rando.apply()` returned early when no character was placed in a chest, skipping the whole treasure assignment *and* the pre-opened-chest table at `$21D9A0`; the ROM kept `0xFF` there, `config_init.f4c` copied it into `$12A0`, and all 512 treasure switches read as already-looted. Now only the character placement is conditional (`init_bits=0`, `nonempty_count=399` for every flagset). **Regenerate old seeds** — ROMs made before this build have empty chests. Worth reporting upstream. |
| 1.5.2 | **Treasure audit + guard.** All 18 treasure-related flags measured through the engine: every one yields 0 pre-opened bits / 399 non-empty chests **except `Tempty`** (367 pre-opened / 32 non-empty), which is explicit, present in the TREASURES menu, and used by no preset. Generation now runs a post-build sanity check on the `$21D9A0` table (and the `$21F0FA` chest count) and the Generate screen shows a red warning if the table is uninitialised — so this class of bug can never ship silently again. |
| 1.5.3 | **Root cause of the "still empty" reports:** the engine was extracted to app storage only on first install and never refreshed, so every later engine fix (including the treasure fix) stayed in the APK and never ran. Extraction is now stamped with the versionCode and redone on every upgrade (wiping stale sources and `__pycache__`). Settings shows the extracted engine build so this is verifiable. |
| 1.6.0 | **Item-quality (tier) controls exposed.** The editor rendered the chest-mode selector but never descended into the chosen mode's children, so "Restrict item quality" (`Tmaxtier:7..3`), "Unrestrict item quality" (`Tunrestrict:*`), "Sparse distribution" (`Tsparse:10..90`) and "Sparse options" were unreachable. `buildSection` now walks the selected mode's subcontrols inline (same as the website's collapsible tree). Verified functionally: `Tmaxtier:3` removes 65 high-tier chest contents. |
| 1.7.0 | **Engine upgraded 4.6.0.Gale → 4.7.0.Gale** (upstream `DoorsRando` branch — the exact code `ff4fe.galeswift.com` runs). Adds 7 sections and ~345 usable flags: **`Tmintier:2..5` (Improve item quality)**, `Tstandardish`/`Tvanillaish`/`Tsemipro`, `Tadjmiabareas`, `Tbuffweights`, plus WACKY, HARP OPTIONS, ZEROMUS, AGILITY, EXPERIENCE, FUSOYA. Local chest patch dropped (upstream fixed it properly). Doors/entrances randomization is **bundled but hidden** (`UiSpec.DOORS_ENABLED = false`); two doors presets filtered out. Verified: `v4.7.0.Gale`, chests 399, no warnings, `Tmaxtier:3` drops 67 contents, `Tmintier:3` drops 50 low-tier contents. |
| 1.7.1 | **Menu navigation polish.** Backing out of any submenu now returns the cursor *and* focus to the row that opened it. Added per-layer selection memory (`FF4Activity.selectionMemory` + `FF4MenuActivity.submitMenu(key, …)`); the flag editor keys per section/selector, presets per category, and the player's pause menu keeps its row across re-opens. Also fixed a real bug where the editor reused a *section* row index as the *category* index (so Back could land on the wrong section). `FF4Menu.submit` now takes focus whenever a layer is (re)populated. |
| 1.8.0 | **Modal generation progress.** A centred FF4 window now blocks the whole screen while a seed generates (the menus are useless then, and easy to mis-navigate), with a **real** progress bar — not a fake one. `fe_bridge` hooks `f4c`'s `Metrics.start` (from *our* code, no vendored edits) and writes `pct/stage/detail` to a file the UI polls, so it shows genuine phases: Parsing scripts → Compiling (with per-block-type detail) → Applying patches → Post-processing → Writing ROM, plus elapsed time. Percentages are monotonic (verified). Used by Generate and by Regenerate on the Play screen; all key/touch input is swallowed while it is up. |
| 1.9.0 | **Pre-release cleanup.** Restored the lost **Regenerate ROM** action on the FF4 Play screen (it had been dropped in the 1.4.0 sessions rewrite) with the modal progress window and a checksum cross-check. Removed dead code (unused imports, obsolete `tools/`), de-duplicated classic/FF4 preset loading (classic now uses `Presets.load`, so the doors filter applies to both), added `.gitignore`, moved 845 MB of stray APKs out of the project, rewrote the README and added `THIRD_PARTY_NOTICES.md`. |
| **0.1.0** | **First public release** (internal development version 1.9.0; `versionCode` 30). Signed with the project release key. See `RELEASING.md` for the version mapping and the release process. |
| 0.1.1 | App icon: `design_assets/ICON.png` wired up as a proper launcher icon — adaptive (API 26+) plus legacy and round mipmaps, generated by `scripts/make_icons.py`; replaces the placeholder system icon. |
| 0.1.2 | Input fix: **Free Enterprise uses Select**, so it is forwarded to the emulator again; the checks tracker moved to **L**, and the ex-L quick save became a Start-menu entry ("Save battery (.srm) now") plus the on-screen SAVE. On-screen pad gained SELECT for touch players (console buttons top-right, app functions top-left). |
| 2.x+ | Phase 3 — in-app emulator (direct Snes9x integration; source + libretro port already local), live trackers, online racing. **Deferred — future feature.** |

---

## 8. Emulator input mapping

FF4 (SNES) uses the D-pad, A/B/X/Y, Start and — in Free Enterprise — **Select**.
Only **L and R** are free, so those drive our app instead of being forwarded to
the core:

| Button | Game | App |
|---|---|---|
| D-pad / stick | movement | — |
| A / B / X / Y | game | — |
| Start | in-game menu | (also opens our overlay when tapped on the on-screen pad) |
| **Start** | in-game menu | **system menu**: Resume / Save State / Load State / **Save battery (.srm) now** / **Checks tracker** / Reset Core / Exit (destructive actions confirm) |
| **Select** | **used by Free Enterprise** | *forwarded to the game* — not ours |
| **L** | *unused* | **checks tracker** (live WRAM: checks opened per world/area, key items) |
| **R** | *unused* | **fast-forward toggle** (3× frames, audio at 1×) |
| **top face button** | field menu (SNES X) | (positional X/Y swap: pad-top = FF4 menu) |
| Back | — | exit to sessions (save is flushed) |
| tap picture | — | also opens the overlay menu (no-controller fallback) |

On-screen pad is **off by default**; the low-opacity **PAD** button in the top
corner toggles it (remembered). When on: D-pad + A/B/X/Y bottom; top-left carries
our functions (MENU · TRK · FF · SAVE) and top-right the console's own buttons
(SELECT · START), so touch players get Select for the game too. Save states are a single slot per session: `<name>.state` beside
the ROM in FF4_FE, moved along by rename and removed with the session. Battery
save is flushed every ~5 s and on pause/exit, so a crash costs seconds at most.

Playback path: `FF4PlayerActivity` → `EmulatorSession` (worker thread, one
frame per 1/60.0988 s) → `dlopen(libretro.so)` → `retro_run` → RGB565 straight
into a direct `ByteBuffer` → `SurfaceView`; audio batch → `AudioTrack` @32 kHz.
SRAM is read from `<seed>.srm` on boot and written back by us.


---

## 9. Save-file decoding (`Ff4Save.kt`)

Layout taken from `FF4_ASMTOC/include/ff4/core/ff4_state.h` (documented WRAM/SRAM
addresses). Each of the four 0x800-byte slots holds:

| Field | Offset in slot | Note |
|---|---|---|
| character n (id, level, HP/MP) | `0x000 + n*0x40` | id = byte0 & 0x1F; level +2; HP +7/+9; MP +0x0B/+0x0D |
| gil | `0x6A0` | 3 bytes little-endian |
| playtime | `0x6A3` frames, `0x6A4` sec, `0x6A5` min, `0x6A6` hour | **byte order inferred** — confirm with a played save |
| world | `0x700` map type, `0x701` world, `0x702` map, `0x703` leader index | |
| valid / checksum | `0x7FB` / `0x7FC` | checksum = sum of bytes `0x000-0x7FA` |

Character names use the id → default-name table (Namingway custom names are not
decoded yet). A slot is only shown when the checksum verifies; otherwise the
summary is marked *(unverified)*.


---

## 10. Tracker plan (Phase 3)

State source = **live SRAM/WRAM** (`retro_get_memory_data`) — offline, no server.
The PopTracker pack (seto10987/…-Poptracker-Pack) is **Archipelago-driven**: its
`snes.lua` is the stock example and tracking runs off AP server messages, so it
is *not* a memory-address source. What it **is** good for:

| Reusable | Use |
|---|---|
| `item_mapping.lua` | the FE key-item list and which are *progressive* |
| `location_mapping.lua` (449 lines) | the full check/objective taxonomy (`@Overworld/Baron Town/…`) |
| `images/items/*.png`, `characters/*.png`, `bosses/*.png` | tracker icons |
| `maps/*.png` + `maps.json` | overworld/underworld/moon map tracker (with coordinates) |
| `logic.lua` | progression gating ("what can I do next") |

Our own authoritative state source is the documented FF4 layout
(`ff4_state.h`): save block $1000-$17FF per slot, plus live WRAM for things not
in the save (battle state, current event switches, timer). Key items are already
decoded from the inventory bag (+0x440, ids $E7-$FA).


### 10.1 How checks are decoded (verified against FE source)

`FreeEnt/scripts/stats.f4c` counts treasures with:

```asm
ldx #$0000              ; byte index into the switch array
lda #$01
SubLoop: bit $12a0,x    ; test (mask in A, LSB-first)
         beq next
         iny
next:    asl a
         bcc SubLoop
         inx
         cpx #$0040     ; 64 bytes = 512 switches
```

So: **bit `f` lives in byte `$12A0 + (f >> 3)`, bit position `f & 7`**, and the
treasure DB's `flag` column indexes it directly (403 checks, flags 0x00-0x1A0).
`config_init.f4c` copies starting flags byte-for-byte into `$12a0,x`, confirming
the byte alignment. The tracker mirrors this exactly.


---

## 11. Doors/entrances randomization — bundled but hidden

We ship upstream **4.7.0.Gale** (`DoorsRando` branch), which contains the door
and entrance randomizers. They are **not offered in our menus yet**:

| Where | Mechanism |
|---|---|
| Editor/model | `UiSpec.DOORS_ENABLED = false` — the `DOORS` section and every `-doorsrando:*` / `-entrancesrando:*` / `@doorsrando` / `@entrancesrando` control are skipped at parse time |
| Presets | `Presets.load` drops any preset whose flags mention doors/entrances (drops *Doorway To Tomorrow*, *Shufflemarket Sweep*) |
| Engine | Untouched and fully present: `doors_rando.py`, `doors.csvdb`, `scripts/doorsrando.f4c`, `scripts/map_history_extension.f4c` |

Doors randomization is **flag-gated** upstream — `generator.py` only adds the doors
scripts and calls `doors_rando.apply()` when a `(doors|entrances)rando` flag is
present. With those flags unset (which our UI guarantees) the doors machinery
never runs, so non-doors seeds match the live site exactly.

**To enable later:** flip `UiSpec.DOORS_ENABLED` to `true` and remove the preset
filter. The spec, engine and assets are already in place — that is the whole
change. Note `databases.py` opens `doors.csvdb` at import time even when unused,
so that asset must stay bundled regardless.

Not to be confused with `Ekeep:doors` ("Preserve TrapDoor fights"), which is an
ordinary encounter option and is **kept**.
