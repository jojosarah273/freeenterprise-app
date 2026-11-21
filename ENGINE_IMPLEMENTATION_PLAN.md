# Implementing the GOLBEZ engine in dev builds — the plan

**Goal:** dev builds can choose an engine *while designing a seed* — 4.7.0.Gale
(bundled today) or 4.6.0-GOLBEZ — with each engine's own flag options, presets and
validation, and every generated seed labelled with its engine in Play Free
Enterprise. Production builds keep shipping Gale alone until this is proven.

Everything below rests on measurements already made on this machine (see
`MULTI_ENGINE_OPTIONS.md`), plus four facts established while writing this plan:

1. `fe_bridge.py` already has the two functions multi-engine needs:
   `set_ext_path(dir)` (any engine dir, at `sys.path[0]`) and `reload_engine()`
   (drops cached `FreeEnt`/`f4c` modules and purges `__pycache__` — it exists for
   in-app engine updates). One string inside it keys the purge loop to
   `'python_ext'` and needs generalising to engine dirs.
2. GOLBEZ's **own** spec compiler runs and produces a shape our flag editor parses
   unchanged: 1,088 flag controls, 11 sections, 14 presets, **no FuSoYa surface**
   (vanilla 4.6 has 1,089; the trimmed one is exactly a `-vanilla:fusoya` control).
   The only structural difference from our 4.7 asset is a `fork` key *we* added.
3. `SeedEngine.kt` extracts a bundled engine dir to `filesDir/python_ext`, stamped
   with the app's `versionCode` so it re-extracts on upgrades — the mechanism
   generalises per engine id.
4. The desktop already takes `--engine`, `--ui-spec`, `--presets`, `--checks` per
   launch: engine dirs have been parameters there since the AppImage work.

## Phase 0 — per-engine bundle tooling (no app change)

`scripts/make_engine_bundle.sh` grows an engine-source mode:

```
engines/golbez/
  FreeEnt/ f4c/ pyaes/ golbez_reference/     ← from the public repo, self-contained
  assets/ui_spec.json                          ← compiled by THAT engine's compile_uispec.py
  assets/presets.json                          ← from that engine's server/
  assets/checks.json                           ← initially reuse ours (see Risks)
  manifest.json                                ← { id, display, version, fork, upstream }
```

Dev APK bundles two: `assets/engines/gale` (today's `python_ext`, reorganised) and
`assets/engines/golbez`. Cost measured by tree size: roughly +10–15 MB compressed
on a 26 MB APK — acceptable for dev, never shipped to production until this
graduates.

## Phase 1 — Android dev build

| Touch point | Change |
|---|---|
| `SeedEngine.kt` | extract every bundled `assets/engines/*` to `filesDir/engines/<id>`, keep the versionCode stamp per engine; `set_ext_path` points at the *selected* engine; `reload_engine()` after a switch |
| `EngineStore.kt` (new, pure logic in `:core`) | list installed engines from manifests, parse manifest, hold the selected id; `EditorPrefs.KEY_ENGINE`, default `gale` |
| `Ff4Rows.generate` | gains an **Engine** row (shared — desktop gets the same row), value = the engine's display name; `Ff4Action.CHOOSE_ENGINE` |
| `FF4GenerateActivity` | Engine row opens a platform-local picker (same pattern as the pad settings): installed engines with version and source |
| `UiSpec.kt`, `FF4PresetActivity` | read `ui_spec.json` / `presets.json` from the selected engine's dir instead of fixed assets — proven compatible, zero parsing changes |
| generation | passes the selected engine's dir to the bridge; the FE version string already comes back in the result |
| `RomLibrary` metadata | seed JSON gains `"engine": "<id>"` |
| sessions list | the seed row's subtitle shows the engine beside seed/save/last-played — shared wording, invariant-guarded like every other row |
| "New seed with these flags" | switches the selected engine to the seed's first, so a 4.6 seed regenerates on 4.6 — never a trap |
| `fe_bridge.py` | generalise the `'python_ext' in p` purge filter to engine dirs; nothing else |

The flag editor needs no translation layer: switching engines swaps the spec, and
`sanitize_flags` already imports the *installed* engine's `FlagSet`, so a 4.6
session validates against 4.6's own rules.

## Phase 2 — desktop parity

Same shared Engine row; `SpecLoader` already reads per-launch files, so the
AppImage gains an `engines/<id>/` sibling and the launcher passes the selected
engine's paths. The picker is platform-local like Android's.

## Per-engine verification, per engine

Already done for GOLBEZ and 4.6.0.open (measured in the feasibility doc):
generation through our bridge on host, ROM boots in our Snes9x, tracker page 1
(`$7e1500` identical in all three engines), verification words covered by the 16
checksum tiles, spoiler parser proven by `Ff4SpoilerGolbezTest`.

Still to verify once, per engine, with the tools we already have:

* **`checks.json` mapping.** Ours was authored from 4.7 data; 4.6 also reports
  399 chests, but the check-flag indices at WRAM `$12A0` need confirming on a
  generated 4.6 ROM — `tools/ff4dump` already reads them, so this is an afternoon,
  not a project. Until then the tracker's *areas* (page 2) reuse our file and are
  labelled dev-known-approximate on 4.6; page 1 (key items) is exact on all
  engines because the convention is shared.
* One device pass per engine: generate → verification words → play → tracker.

## What deliberately does not move

* Production builds stay Gale-only; engines-in-APK is a dev-build experiment until
  the community release decision is made deliberately.
* No downloads in this phase — bundling in the dev APK avoids updater work while
  the UX is proven. On-demand engine zips (the existing `EngineUpdater` shape)
  come after, if at all.
* No cross-engine flag translation, in either direction.
