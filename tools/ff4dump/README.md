# ff4dump

A headless Snes9x driver for Free Enterprise work: it loads a ROM (and optionally a
save state or battery save), runs frames, and prints what the addresses the app
cares about actually contain.

## Why it exists

The tracker, and the completion metadata planned for it, are statements about
console memory. Checking them by playing to Toroia and looking at a screen is slow
and unrepeatable. This turns those questions into a command you can run, and it is
the front half of the desktop client: the same core, the same save-state handling,
no window.

## Build

```sh
make -C tools/ff4dump           # → tools/ff4dump/ff4dump
```

The core source list is **included from the same file the Android build uses**
(`app/src/main/cpp/libretro/Makefile.common`), so there is one list and the two
builds cannot drift. An invariant check asserts that include stays.

## Use

```sh
tools/ff4dump/ff4dump --rom game.smc
tools/ff4dump/ff4dump --rom game.smc --state game.state --frames 60
tools/ff4dump/ff4dump --rom game.smc --sram game.srm
tools/ff4dump/ff4dump --rom game.smc --dump-wram wram.bin --dump-sram sram.bin
```

| Option | Meaning |
| --- | --- |
| `--rom PATH` | the ROM to load (required) |
| `--state PATH` | load a save state before running |
| `--sram PATH` | load a battery save into SRAM |
| `--frames N` | run N frames (input is idle, so this suits states rather than play) |
| `--poke wram:ADDR=VAL` / `sram:ADDR=VAL` | set a byte after the frames, before the report |
| `--dump-wram FILE`, `--dump-sram FILE` | write raw memory out |
| `--save-state FILE` | serialize a state out |
| `--no-report` | skip the decoded report |

`--poke` exists so the decoder can be checked without a playthrough. Poking
`wram:1500=04` sets bit 2 of the engine's found flags, and the report must then say
`legend_sword found=1` — which is what it does, and which independently confirms
the bit order the app's tracker relies on.

## What it decodes

| Address | What |
| --- | --- |
| `$7E1440` | item bag, 48 entries of (id, quantity) |
| `$7E1500` / `$7E1503` | the engine's key item *found* and *used* flags, 17 bits each |
| `$7E12A0` | the treasure switch array (512 bits) the app's checks page counts |
| `$7E1578` | run statistics: key items, characters, treasures, bosses, end time |
| SRAM `0x0000` | save slots: validity, checksum, playtime |
| SRAM `0x7080` | the engine's record of *where* each key item came from, 2 bytes each |
| SRAM `0x70F2` | the engine's "ever completed" byte |

It also prints the SRAM size the core reports, because that is what decides whether
the engine's `$70:xxxx` addresses are direct or mirrored.

## Finding: FE runs on 32 KB of SRAM

The vanilla ROM reports 8 KB; a generated Free Enterprise ROM reports **32 KB**
(`0x8000`). So the engine's `$70:xxxx` addresses are **direct offsets** — no
mirroring — which settles `Tracker__KeyItemLocations` at `0x7080` and
`Stats_EverCompleted` at `0x70F2` for the completion metadata.

## Limits

- Input is idle, so it is for inspecting loaded states rather than playing.
- No window, no audio: it exists to answer questions about memory.
