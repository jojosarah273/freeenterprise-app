# Multi-engine — options for shipping 4.7.0.Gale, 4.6.0.open and 4.6.0-GOLBEZ together

**Status: design, no code changed.** What follows is grounded in what the app
already does — `fe_bridge.py` is version-neutral plumbing that takes an `--ext DIR`,
`sanitize_flags` imports the *installed* engine's `FlagSet`, and `EngineUpdater`
already swaps `python_ext` in place with rollback. The pieces that are genuinely
per-engine are fewer than they look.

## What is actually engine-specific

| Piece | Per-engine? | Why |
|---|---|---|
| `FreeEnt/` + `f4c/` + `pyaes/` | **yes** | the engine itself |
| `spec/ui_spec.json` | **yes** | the flag editor's rows come from it; 4.6 and 4.7 flag lists differ |
| `presets/presets.json` | **yes** | preset flags are version-tuned |
| `spec/checks.json` | **yes** | tracker area/check lists |
| verification words | **yes** | the checksum vocabulary is per version |
| `sanitize_flags` | **no** | already imports the installed engine's `FlagSet`; a 4.6 engine accepts 4.6 flags, and 4.7-only flags are dropped by the existing name-rejection loop. The hand-written 4.6→4.7 rename table is only needed when *4.7* is active |
| seed generation / playing | **no** | generation calls the bridge with the engine's dir; playing is the bundled Snes9x core on an already-built ROM |
| save states / .srm | **no** | the ROM is what matters, not the engine that built it |

One real risk to verify per engine: **the tracker's WRAM offsets**. `Tracker__KeyItemFoundFlags`
at `$7E1500` is an FE patch convention; it must be confirmed for the Golbez build (its
`tracker.f4c` may differ) before that engine's tracker page 1 is trusted.

## Options

### A — one active engine, per-seed memory (recommended)

An engine is a self-contained directory, installed side by side, with **one active**:

```
filesDir/engines/<id>/            (Android)     FF4_FE/../engine/<id>/   (desktop)
  python_ext/  FreeEnt  f4c  pyaes
  assets/      ui_spec.json  presets.json  checks.json
  manifest.json   { "id": "gale", "version": "4.7.0", "fork": "Gale", "words": ... }
```

- APK ships **4.7.0.Gale** as the default active engine (today's behaviour, zero
  regression for existing installs).
- 4.6.0.open and 4.6.0-GOLBEZ are **downloadable on demand** from Releases as
  `freeenterprise-engine-<id>-<version>.zip` (~4.6 MB each) through the existing
  update screen. Nothing is forced on anyone.
- The active engine is a **Settings row** (Android: `Settings → Engine`;
  desktop: the update screen gains an "Install engine" list). Platform-local
  rows, like the update screens — no shared-menu change, no parity impact.
- Seed metadata already records the FE version; it gains the engine **id**:
  `"engine": "gale"`. "New seed with these flags" **switches the active engine
  to the seed's** automatically, so regenerating an old seed is not a trap.
- The flag editor loads `ui_spec.json` **from the active engine**, so a 4.6
  session shows 4.6's rows and flags with no translation work at all.
- Rollback works exactly as today: swapping an engine dir keeps `<id>.old`.

This is also how the FE website works — a version dropdown, then generate — so it
will read as familiar to the community.

### B — an Engine row on the Generate screen

Same plumbing, but selection sits on Generate rather than Settings. Rejected:
`Ff4Rows.generate` is a shared menu, so the row appears on both platforms and
becomes part of the parity contract; it also clutters the screen most players
never need. If requested later it is a one-row addition on top of A.

### C — separate APKs per engine (a "Golbez APK")

Zero UI work, but three apps to update, three update channels, and app-private
`FF4_FE` storage means a seed made in one is invisible to the others. Rejected:
it breaks the one-codebase promise and splits the library.

## Golbez prerequisites (from GOLBEZ_MODE_FEASIBILITY.md, still true)

1. **Version control the fork** — it has none today.
2. **Remove `FreeEnt/ff2.smc` from the tree** — a copyrighted ROM inside the
   engine we would bundle and publish.
3. **Trim the flag surface** — removing FuSoYa invalidates a large set of flags;
   the fork should ship a `ui_spec.json` subset so the editor offers only what it
   honours.
4. **Verify tracker WRAM offsets** for that build before trusting page 1.

## What A costs

- `EngineStore` (list/install/activate/rollback, one class reused by both
  platforms; manifest parsing testable in :core).
- `EngineUpdater` generalised from "the engine" to "an engine id" — it is already
  90% there (per-id zip names, per-id dirs, per-id rollback).
- Settings row + engine picker window (Android), "Install engine…" (desktop).
- `SeedMeta` gains `engine`, generation stamps it, "New seed with these flags"
  honours it.
- Bundle script emits per-engine zips with manifests.

Estimate: a focused 2–3 days including the release packaging, plus one manual
pass per engine (generate, verification words, tracker offsets).

## What A deliberately does not do

- No per-seed engine *switching UI* — the seed's engine is honoured silently.
- No auto-download of other engines — the player chooses.
- No change to the bundled default — Gale stays what ships in the APK.
