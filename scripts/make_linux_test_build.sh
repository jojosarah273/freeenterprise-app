#!/usr/bin/env bash
# Assembles a self-contained Linux test build:
#
#   runtime/          the application (jars + launcher)
#   native/           libretro.so and libff4emu.so
#   engine/           the randomizer engine (Python) and the bridge CLI
#   run.sh            launcher that wires the three together
#
# Requires: a JDK 17+ on the machine that runs it, make, and a C++ toolchain to
# build the emulator libraries. Produces a .tar.gz plus a sha256 next to it.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR=${1:-"../FE_LINUX_ARCHIVE"}
STAMP=$(date +%Y%m%d-%H%M)
NAME="freeenterprise-linux-dev-$STAMP"
STAGE="build/linux/$NAME"

echo "== building the emulator libraries =="
make -C desktop/native -j"$(nproc)" >/dev/null

echo "== building the application =="
./gradlew --quiet :desktop:installDist

echo "== staging $STAGE =="
rm -rf "$STAGE"
mkdir -p "$STAGE/native" "$STAGE/engine"
cp -r desktop/build/install/freeenterprise "$STAGE/runtime"
cp desktop/native/build/libretro.so desktop/native/build/libff4emu.so "$STAGE/native/"
cp desktop/native/build/libff4input.so "$STAGE/native/"
cp -r app/src/main/assets/python_ext "$STAGE/engine/python_ext"
cp app/src/main/python/fe_bridge.py "$STAGE/engine/fe_bridge.py"
# the check list, so tracker page two works in a packaged build
mkdir -p "$STAGE/engine/assets"
cp app/src/main/assets/spec/checks.json "$STAGE/engine/assets/checks.json"
cp app/src/main/assets/spec/ui_spec.json "$STAGE/engine/assets/ui_spec.json"
cp app/src/main/assets/presets/presets.json "$STAGE/engine/assets/presets.json"

cat > "$STAGE/FreeEnterprise" <<'LAUNCHER'
#!/usr/bin/env bash
# Free Enterprise - Linux test build.
#
#   ./FreeEnterprise                     the seed library, fullscreen
#   ./FreeEnterprise --windowed          start in a window (F11 switches)
#   ./FreeEnterprise --generate SEED     generate a seed, then play it
#   ./FreeEnterprise --rom some.smc      play a seed you already have
#
# Your own FF2 (US) v1.0/v1.1 ROM is needed to generate seeds. Put it beside this
# script as ff2.smc and it will be found automatically, or pass --base-rom PATH.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# FF2 (US) v1.0 and v1.1, without a copier header.
MD5_V10=4fa9e542b954dcb954d5ce38188d9d41
MD5_V11=27d02a4f03e172e029c9b82ac3db79f7

looks_like_base_rom() {
    local file="$1" md5
    md5="$(md5sum "$file" | cut -d' ' -f1)"
    [ "$md5" = "$MD5_V10" ] || [ "$md5" = "$MD5_V11" ] && return 0
    # Some dumps carry a 512-byte copier header; the ROM itself starts after it.
    md5="$(tail -c +513 "$file" | md5sum | cut -d' ' -f1)"
    [ "$md5" = "$MD5_V10" ] || [ "$md5" = "$MD5_V11" ]
}

find_base_rom() {
    local candidate
    for candidate in "$HERE"/FF4_FE/ff2.smc "$HERE"/FF4_FE/ff2.sfc \
                     "$HERE"/ff2.smc "$HERE"/ff2.sfc "$HERE"/*.smc "$HERE"/*.sfc; do
        [ -f "$candidate" ] || continue
        if looks_like_base_rom "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
    done
    return 1
}

extra=()
if base_rom="$(find_base_rom)"; then
    echo "base ROM: $(basename "$base_rom")"
    extra+=(--base-rom "$base_rom")
else
    echo "No base ROM found beside this launcher."
    echo "  Put your FF2 (US) v1.0/v1.1 ROM here as ff2.smc, or pass --base-rom PATH."
    echo "  Everything except generating a seed works without it."
fi

exec "$HERE/runtime/bin/freeenterprise" \
    --native "$HERE/native" \
    --engine "$HERE/engine/python_ext" \
    --bridge "$HERE/engine/fe_bridge.py" \
    --library "${FE_LIBRARY:-$HERE/FF4_FE}" \
    --work "${FE_WORK:-$HERE/FF4_FE/.work}" \
    "${extra[@]}" "$@"
LAUNCHER
chmod +x "$STAGE/FreeEnterprise"

cat > "$STAGE/run.sh" <<'WRAPPER'
#!/usr/bin/env bash
# Kept for convenience: passes everything through to ./FreeEnterprise, which is the
# launcher that finds the base ROM and wires up the directories.
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/FreeEnterprise" "$@"
WRAPPER
chmod +x "$STAGE/run.sh"

mkdir -p "$STAGE/FF4_FE/.work"

cat > "$STAGE/README.md" <<'README'
# Free Enterprise — Linux test build

## Run it

```sh
# 1. put your own FF2 (US) v1.0/v1.1 ROM in this folder, named ff2.smc
# 2. then:
./FreeEnterprise
```

That is the whole setup. The launcher looks for the ROM beside itself (it checks the
known checksums, with or without a copier header), and keeps everything it creates in
this folder:

```
FF4_FE/    the seeds you generate or play, with their metadata, battery saves,
           save states and spoilers - the same folder layout as the Android app
```

`run.sh` still exists and does the same thing, if you have scripts that call it.

## Keys

| | |
| --- | --- |
| Arrows | d-pad |
| X / Z | A / B |
| S / D | Y / X |
| Enter | Start |
| Q / W | L / R |
| Tab | tracker |
| Esc | our menu |
| **F11** | **windowed ⇄ fullscreen** |
| Esc, then Quit | leave |

The controls also appear in a bar along the bottom of the screen: when a session
starts, and whenever one of our menus is open. It clears itself after a few seconds
so it never sits on top of the game's dialogue.

A controller is picked up automatically if one is attached (SDL2; the library's
mapping database means most pads work). A confirms, B cancels, the stick drives the
menus, Start opens our menu, L2 opens the tracker, R2 fast-forwards while held.

## Command line

```sh
./FreeEnterprise --windowed                  start in a window
./FreeEnterprise --generate SEED --flags "Kmain"
./FreeEnterprise --rom some-seed.smc
./FreeEnterprise --base-rom /path/to/ff2.smc
./FreeEnterprise --library /somewhere/else
```

## Notes

- Needs **JDK 17+** (`java -version`).
- Your ROM is never copied, uploaded or modified; it is read to generate a seed.
- Audio needs a 32 kHz stereo line from your sound system; without one the app runs
  silently and says so.
README

echo "== packing =="
mkdir -p "$OUT_DIR"
tar -C "$(dirname "$STAGE")" -czf "$OUT_DIR/$NAME.tar.gz" "$NAME"
sha256sum "$OUT_DIR/$NAME.tar.gz" | awk '{print $1"  "}' > "$OUT_DIR/$NAME.tar.gz.sha256"
echo "$(basename "$OUT_DIR/$NAME.tar.gz")  $(du -h "$OUT_DIR/$NAME.tar.gz" | cut -f1)"
echo "sha256: $(cut -d' ' -f1 < "$OUT_DIR/$NAME.tar.gz.sha256")"
