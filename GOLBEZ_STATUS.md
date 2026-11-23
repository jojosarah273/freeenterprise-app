# Golbez engine — what is established, what is disproven, what remains

Everything below is evidence from this machine, not theory. Each claim names how it
was measured, so it can be re-checked instead of re-argued.

## 1. The app runs your engine correctly (proven, repeatedly)

| claim | how measured | result |
|---|---|---|
| App bundle == your local tree | md5 of every `FreeEnt/` source file and all 19 `golbez_reference` blobs | identical (only the compile cache differs) |
| App ROM == your CLI ROM | `fe_bridge` vs `python -m FreeEnt … make`, same seed/flags/env | **byte-identical**: `6c723b5d` (CFGDUO), `4216e9bb` (CHECK2), `4e2970ee` (old engine CFGDUO) |
| Engine switch is isolated | gale generation → switch → golbez generation, one process | identical to a clean golbez build, once per-engine caches and native env were added |

So "the app produces different ROMs than your tool" is **disproven**.

## 2. The emulator is not the difference (proven by you)

You ran `golbez_exact_playable.sfc` (your canonical) in the app: both Golbez
instances rendered **correct battle sprites**. That is the same Snes9x core we ship,
so the renderer is exonerated.

## 3. The graphics data in our builds equals the canonical (measured)

Nine regions compared to `golbez_exact_playable.sfc` on two independent seeds —
battle sheet `0xD6800`, battle palette `0xE7EA0`, map sheet `0xDA700`, map palette
`0x68030`, portrait `0xEE740`, portrait palette `0x687A0`, standing block
`0x117840`, palette selector `0xAB307`, character records `0x79070`: **all "same"**.

Also: FuSoYa's sheet really is at `0xD6800` — the fork's Golbez art has 79.6%
silhouette overlap with it, the highest of all 24 character slots, so that address is
correct.

## 4. What has been tried and REVERTED (each made things worse)

| attempt | why it failed |
|---|---|
| Force `quickstart` (golbez.5–.9) | skips the pregame screen, and the fork initialises its character expansion there → every slot fell back to vanilla actor data |
| Write `0x13` into all 24 axtor-table entries (golbez.10) | the table is FE's own assignment output; overwriting it decoupled table from assignment → **every** party member broke |
| Copy Golbez's character record into all 24 slots (golbez.12) | would make Cecil, Rosa, Yang, Rydia all render as Golbez |
| Replace FE's `standing_characters.bin` FuSoYa block with Golbez tiles (golbez.14) | the canonical **working** ROM keeps that block vanilla, so the patch made us differ from the reference, and the field sprite got worse |

## 5. Where the remaining difference actually is

Two facts that matter together:

* your canonical ROM and our builds have identical sprite data and consistent tables;
* the canonical ROM **also** shows the "mini" sprite for an out-of-party Golbez.

That second one means the mini sprite is **not** an app bug at all: it reproduces in
the build you certify. It is a gap in the fork, and it is the only sprite problem that
survives every test.

FE writes only two binary patches (`generator.py`):

```
0x117000 : binary_patches/standing_characters.bin     <- standing / out-of-party graphics
0x10da00 : assets/encounters/formation_average_levels.bin
```

Battle sheets are not patched by FE at all — they come from the base ROM, which is why
patching `0xD6800` works for in-party battle sprites but cannot affect the standing /
out-of-party sprite.

The remaining task is therefore: **find which data the "mini" out-of-party sprite is
drawn from and make it Golbez's**, in the fork, not in the app.

## 6. Tools built for this investigation (kept)

* `desktop --dump-frame F` — the core's native 256×224 pixels, unscaled;
* `desktop --dump-every DIR` — one native frame every 60 frames;
* `desktop --script "frame:button,…"` — headless input, so a screen that needs a
  keypress can be reproduced without a window;
* `desktop --state FILE` — load raw or RetroArch (`#RZIP`/`RASTATE`) savestates;
* `fe_bridge` `FE_DUMP_SCRIPT=out.script` — the engine's full patch listing.

## 7. Method note

The sprite matcher used in this investigation has been wrong before (broken 4bpp
decoder, index-vs-rank mismatch, overlapping sample positions, sparse-tile filter).
It is only trusted now that it passes a two-way self-test: rendering Golbez's sheet
detects Golbez (109 hits, 0 FuSoYa) and rendering FuSoYa's sheet detects FuSoYa
(129, 0 Golbez). Any future claim about "no FuSoYa tiles" should require that test
first.

## 8. Audit of the shipped APK (0.1.26-dev.golbez.15, versionCode 69)

Tested the **artefact's own bundled engine**, extracted from the APK, not the repo copy.

* Engine code clean: no `AXTOR_TABLE`, no `CHAR_RECORD_ADDRESS`, no replicate helper,
  no standing-character replacement — all four reverted experiments are absent.
* Files match the fork tree: `standing_characters.bin`, `charitem_G.bin`,
  `battle_sheet_G.bin`, `battle_pal_G.bin`, `version.py`. The two that differ
  (`golbez_lock.py` by one blank line, `LOCKED_MANIFEST.json` by JSON indentation)
  are semantically identical — the manifests parse to equal dicts.
* Three configurations generated from the APK's engine (duo, solo, mixed):
  **9/9 graphics regions equal to the canonical ROM**, the fork's verifier **PASS** on
  all three, 24 distinct character records (mixed seeds unaffected), exactly one sheet
  carrying Golbez art (same as canonical).
* The canonical's own seed (GOLBEZ1) rebuilt with the APK engine: **7/7 sprite regions
  identical** and the **axtor table identical** to the canonical
  `[0,19,1,1,1,19,1,1,1,19,1,0,0,1,19,1,1,19,19,1,1,0,0,0]`.
* Runtime: title frames are pixel-identical between the canonical and our build; both
  reach the pregame screen (ours later, because the current flag vocabulary adds a line
  that shifts the pacing). No FuSoYa-sheet tiles appear in any captured native frame,
  measured with the matcher that passes its two-way self-test.

Verdict: worth testing. The ROMs are equivalent to the canonical build in every region
that determines character appearance.
