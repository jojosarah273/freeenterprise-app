# Roadmap — expansion plans

Forward-looking plans for the app. Nothing here is a commitment; items are
ordered by how well they fit the current architecture. The living design record
(palette, tokens, status log) stays in `DESIGN_FF4_UI.md`.

---

## 1. Tracker (in progress → big win)

**Where it is.** Select-on-the-controller shows a window reading **live WRAM**:
- `CHECKS n / 403` — total, per world, and per area with remaining counts
- Key items currently held

Checks come from the 512 treasure switches at WRAM `$12A0` (LSB-first — the
array Free Enterprise's own `stats.f4c` counts), mapped to the 403 entries in
`assets/spec/checks.json`, generated from FE's own treasure database.

**What "better" looks like**

| Step | Detail | Effort |
|---|---|---|
| **Which checks remain, exactly** | Use `spoilerarea` / `spoilersubarea` / `spoilerdetail` from the treasure DB (already in `checks.json`) to list *unopened* chests per area, not just counts | Low |
| **Quest / objective tracking** | Hook the event switches (`$1280`) and NPC switches (`$12E0`) documented in `ff4_state.h` to show quest state and the seed's objectives | Medium |
| **Boss slots** | Read boss assignment from the ROM's embedded metadata / spoiler and tick off defeated bosses | Medium |
| **Key-item *locations*** | Combine held key items with the stored spoiler to say where the rest are — the single most requested FE quality-of-life feature | Medium |
| **Map tracker** | A minimap of overworld/underworld/moon with check markers | High |
| **Always-on HUD** | Draw checks/objectives as a corner overlay during play rather than a paused window (the WRAM reads already work while running) | Medium |

**Content we can reuse.** The PopTracker pack
(`seto10987/Final-Fantasy-4-Free-Enterprise-AP-Poptracker-Pack`) is
Archipelago-driven, not memory-driven, so it is **not** a source of addresses —
but its `location_mapping.lua` (449 lines of check names/taxonomy),
`item_mapping.lua` (key items, which are progressive), `logic.lua` (progression
gating) and its icon set (`images/items|characters|bosses`) and map images are
directly useful. See `DESIGN_FF4_UI.md` §10.

**Design constraint.** Everything should stay offline and derive from emulator
memory + the seed's own metadata; no server.

---

## 2. Doors / entrances randomization

**Status: built, hidden.** The bundled engine is 4.7.0.Gale, which contains the
full doors/entrances randomizer. It is not offered in the menus yet:
`UiSpec.DOORS_ENABLED = false` filters the `DOORS` section and every
`-doorsrando:*` / `-entrancesrando:*` control, and two doors presets are skipped.

**Why it was deferred.** It is a large, complicated ruleset, and we wanted a
stable base first.

**To enable** — the spec, engine and assets are already in place:
1. Flip `UiSpec.DOORS_ENABLED` to `true`.
2. Remove the doors filter from `Presets.load`.
3. Test the door-scope variants (`normal`, `gated`, `blueplanet`, `why`, `all`)
   plus the `starting_underground` interaction.
4. Nothing else — `doors_rando.py`, `doors.csvdb`, `doorsrando.f4c` and
   `map_history_extension.f4c` already ship, and upstream gates them on flags so
   they are dormant while hidden.

**Worth checking when enabled:** door randomization changes map connectivity, so
it interacts with objective/key-item logic. Verify a few seeds against the
website's own output before exposing it.

---

## 3. Refined UI

**Done:** FF4 design system (window/menu/dialog/progress/theme), controller and
touch input, per-layer cursor memory, no action bar, immersive landscape, real
progress reporting.

**Next**

| Item | Notes |
|---|---|
| **Window open/close animation** | The real ROM tiles include the animation frames; a simple scale/wipe would sell it |
| **Sound** | Cursor blip and confirm SFX, toggleable (the ROM's sound data is available) |
| **Portrait / icon art** | Character icons for the party and tracker rows |
| **Flag editor ergonomics** | Search across ~1,700 flags, "only show changed", and jump-to-section |
| **Flag descriptions** | Surface the long-form descriptions from `uispec_flagdescriptions.txt` in the editor |
| **Metaconfig UI** | Hide-flags and test-settings toggles (the engine already accepts them) |
| **Colour themes** | The window palette is data (the game has several), so alternate FF4 colour schemes are cheap |
| **Landscape/portrait** | Currently landscape-forced; portrait would suit the flag editor |
| **Accessibility** | Larger text mode, screen-reader labels, high-contrast palette |

---

## 4. In-app updating

**Goal:** get new engine versions and app releases without sideloading.

**Options**

| Approach | Notes |
|---|---|
| **Self-update via GitHub Releases** | App checks a release feed, downloads the APK, hands it to the package installer. Needs the `REQUEST_INSTALL_PACKAGES` permission and a signing key held consistently. Works outside Play. |
| **Play Store / F-Droid** | Simplest for users; note the "all files access" permission and bundled artwork may need explaining to reviewers |
| **Engine-only updates** | Decouple the randomizer from the app: version-stamp `python_ext`, download newer engine bundles from a URL, verify a checksum, and swap. Avoids a full APK for a flag change — attractive because the engine changes far more often than the app |
| **Do nothing** | Versioned APK filenames + the in-app version display already make manual updates unambiguous |

**Recommendation:** engine-only updates first (small, no permission changes),
then whole-app updates if the UI changes often.

---

## 5. iOS support

**Feasibility: possible but a genuine rewrite.** The two hard dependencies are
the sticking points.

| Component | Android today | iOS |
|---|---|---|
| Randomizer (Python) | Chaquopy | Embed CPython (e.g. BeeWare/Briefcase, or a hand-rolled `Python.xcframework`). The engine is pure Python, so this is mostly plumbing |
| Emulator | Snes9x built with the NDK into a `.so`, driven by our JNI bridge | The same C/C++ core compiles for arm64 iOS, but **JIT is not permitted**, so an interpreted/dynarec-free build is required. Snes9x runs acceptably without dynarec for SNES |
| UI | Kotlin + custom Views, AudioTrack, SurfaceView | Would need SwiftUI/Metal or a cross-platform layer (Kotlin Multiplatform can share logic but not Views) |
| Storage | `FF4_FE` at shared-storage root | iOS is sandboxed — a Files-app-visible folder via the document picker, or app-private storage |

**Recommended path:** extract the platform-neutral core (engine invocation,
seed metadata, save files, flag spec parsing, tracker decoding) into a shared
module, then write a fresh Swift UI and an iOS emulator host. Realistically a
separate project that reuses the Python and the Snes9x source, not a port of the
Android app.

---

## 6. Online racing

**Goal:** synchronised races on a shared seed, with fair verification.

**What makes it tractable:**
- The **binary flag string** plus the **four checksum tiles** already uniquely
  identify a seed's ruleset and verify a ROM is the right one — no ROM sharing
  required, and no copyrighted data crosses the wire.
- A seed is fully described by `(flags, seed)`; the ROM is reproducible by any
  client, so the server never needs to store or serve ROMs.

**What needs building**

| Piece | Detail |
|---|---|
| **Room service** | Small server (or serverless + WebSocket relay) holding room state: participants, seed, start time, status |
| **Seed exchange** | Share `(flags, seed)`; clients generate locally and verify checksums, so nobody can cheat by doctoring the ROM |
| **Start sync** | Countdown, then a shared start timestamp; each client starts its own emulator |
| **Progress reporting** | The tracker already reads checks/key items from WRAM — push periodic snapshots (checks opened, objectives, key items) so a room can show live standings |
| **Verification** | Compare final state against the seed's spoiler/metadata; flag suspicious divergence |
| **Spectating** | Optional: stream derived state (not video) to watchers |

**Privacy/consistency note:** keep it **opt-in and offline-by-default**. The app
currently has no internet permission at all, so racing would need a deliberate,
separate capability — ideally only in the racing mode.

**Sequencing:** needs the tracker's progress reporting first; the server is the
easy part.

---

## 7. Custom engine mode — playable Golbez

Feasibility of shipping the custom fork in `DEV/FE_GOLBEZ` (replaces FuSoYa with
a handmade playable Golbez) as a **separate mode** is assessed in
**`GOLBEZ_MODE_FEASIBILITY.md`**.

Short version: architecturally straightforward, but the fork is **not finished**
— its own dev log ends with the core rendering problem unresolved — and it is
based on **4.6.0.open** rather than our 4.7.0.Gale, so it would diverge in
features, flags and seed parity. Recommended route is to finish the fork, then
port the work onto 4.7.0.Gale so one engine serves both experiences.

---

## Sequencing summary

```
now        tracker: exact remaining checks → quest/objective switches → boss slots
           UI polish: animation, sound, flag search + descriptions
soon       doors randomization (flip a constant, then verify against the site)
           engine-only updates
later      Golbez mode (after the fork is finished)
           fuller tracker (key-item locations, map tracker, always-on HUD)
           in-app app updates
future     iOS (shared core + new UI + iOS emulator host)
           online racing (needs tracker progress reporting first)
```
