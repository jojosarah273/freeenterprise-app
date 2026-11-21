# Multi-engine feasibility — measured, on this machine

**Status: feasibility explored, nothing deployed.** The design below is the one
chosen in discussion: the engine is picked **when a seed is designed**, every
engine is **completely isolated** (its own options, presets, validation, data), the
generated seed lands in "Play Free Enterprise" **labelled with its engine**.

The numbers in this document are not estimates. Each engine was installed into its
own directory on this machine, driven through our own `fe_bridge.py`, and its ROM
booted in our own Snes9x core.

## Verdict

**Feasible, and cheaper than expected.** The bridge and the emulator are already
engine-neutral; the truly per-engine pieces all *ship inside each engine's own
tree*, including the compiler that produces our flag-editor spec. Nothing about
"completely different engines" forces shared anything — isolation is the natural
shape.

## What was actually run

| Check | 4.7.0.Gale (bundled) | 4.6.0.open (HungryTenor) | 4.6.0-GOLBEZ (public fork) |
|---|---|---|---|
| `fe_bridge.py --ext DIR version` | 4.7.0.Gale | 4.6.0.open | 4.6.0.open |
| `check --flags Kmain` | ok | ok | ok |
| `generate` (Kmain, host) | ~4 s | **5.3 s** | **4.0 s** |
| ROM boots in our Snes9x | yes | yes | yes (LoROM-mapped, 300 frames, tracker reads WRAM) |
| Golbez content present | n/a | n/a | yes — spoiler shows `Lunar Palace character ..... Golbez` |
| public repo clean of ROMs | yes | yes | **yes** — `ff2.smc` exists only in the local working tree |
| tracker flag address | `$7e1500` | `$7e1500` | `$7e1500` |
| verification words (this seed) | bow star sword harp | harp potion star mini | claw tent shield key |

Three of those rows were the big risks and all three dissolved:

* **The bridge needed zero changes.** `--ext DIR` already isolates the import path;
  GOLBEZ additionally required its `golbez_reference/` data directory to sit in the
  engine dir, which is exactly what "self-contained engine directory" means.
* **The tracker reads the same WRAM in all three** (`Tracker__KeyItemFoundFlags
  $7e1500` in each `scripts/tracker.f4c`), so page 1 works per engine unmodified.
* **All 16 checksum tile icons cover every verification word seen** — claw, tent,
  shield, key, harp, potion, star, mini are all in `assets/checksum/`. The icon set
  is engine-neutral; only the words differ per seed.

And one compatibility test was added to the suite rather than assumed:
`Ff4SpoilerGolbezTest` runs a **verbatim KEY ITEM LOCATIONS section from a real
GOLBEZ seed** through our parser — all 18 entries read, including the two-dot case.
If a fork ever changes its spoiler format, that test fails.

## The isolation, precisely

A "completely different engine" is mostly visible in the flag surface:

| | 4.6.0 | 4.7.0 |
|---|---|---|
| flag controls in the editor spec | **1,089** | **1,697** |
| shared | 1,077 | |
| 4.7-only (doors rando, call variants, …) | — | 620 |
| 4.6-only (`-vanilla:fusoya`, `Cj:spells`, …) | 12 | — |

The 4.6-only list is exactly the FuSoYa surface that GOLBEZ removes. So the flag
editor **must** load a per-engine spec — and here is the part that makes isolation
cheap: **each engine ships its own `compile_uispec.py`, `srcdata/flagspec.js`,
`uispec_flagdescriptions.txt`, `presets.json` and `checks` data in
`FreeEnt/server/`.** Our 4.7 `ui_spec.json` was compiled by that same tooling. We
never author a spec per engine; we run each engine's own compiler against its own
data. GOLBEZ's is already trimmed for its locked flag surface.

## The engine bundle format

```
engines/<id>/                       one directory, everything inside it
  FreeEnt/  f4c/  pyaes/
  golbez_reference/                 (engine data — only GOLBEZ has this)
  assets/
    ui_spec.json                     compiled by THIS engine's compile_uispec.py
    presets.json                     from this engine's server/
    checks.json                      from this engine's server/ data
  manifest.json
    { "id": "golbez", "display": "4.6.0-Golbez",
      "version": "4.6.0", "fork": "open", "upstream": "github.com/jojosarah273/FreeEnterprise4-Golbez" }
```

Nothing crosses between directories: the editor reads `<selected>/assets/ui_spec.json`,
generation passes `<selected>` as `--ext`, validation (`check`) sanitises against
*that* engine's `FlagSet` (already true — `sanitize_flags` imports the installed
engine), and the verification words come back in the generate result.

## The flow, as chosen

1. **Generate Seed** gains an **Engine** row (shared, so both platforms show it —
   desktop hosts engines the same way). Its value is the engine's display name.
2. Choosing it lists installed engines plus "get more" (a download list, on-demand —
   nothing is pushed at anyone).
3. The moment an engine is selected, the flag editor's sections, presets, checks and
   validation all come from that engine's bundle. A 4.6 session shows 4.6's rows —
   no translation, no renamed-flag table needed in that direction.
4. Generate uses that engine; the seed's metadata records the engine id (the FE
   version string is already stored today).
5. **"Play Free Enterprise" lists each seed with its engine** — in the seed row's
   subtitle, beside seed/save/last-played, on both platforms, guarded by an
   invariant like every other shared row.
6. "New seed with these flags" silently re-selects the seed's engine first, so
   regenerating an old seed is never a trap.

## What remains, if and when this is built

* `EngineStore` — install/list/select per engine dir (one class, both platforms;
  manifest parsing pure Kotlin in `:core`, testable).
* An `Engine` row in `Ff4Rows.generate` + both platforms' editors reading the
  selected engine's spec.
* Seed rows gain the engine label (shared wording, invariant-guarded).
* Packaging: per-engine zips built by the same script that builds today's engine
  bundle, plus a compile step running each engine's own `compile_uispec.py`.
* One manual pass per engine: generate, verify its words, boot the ROM, confirm
  its `checks.json` tracker page.

## What deliberately does not happen

* No engine is deployed or bundled by this document — Gale remains the only engine
  the app ships.
* No cross-engine flag translation. Old seeds keep their flags; opening one in the
  editor switches the engine rather than rewriting flags.
* No auto-download. Other engines are opt-in from the engine list.
