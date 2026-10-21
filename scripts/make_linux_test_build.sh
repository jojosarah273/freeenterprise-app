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

cat > "$STAGE/run.sh" <<'LAUNCHER'
#!/usr/bin/env bash
# Free Enterprise - Linux dev build.
#
#   ./run.sh                                   the seed library, fullscreen
#   ./run.sh --generate SEED --flags "Kmain"   generate, then play it
#   ./run.sh --rom game.smc                    play a ROM directly
#
# Needs a JDK 17+ and your own FF2 (US) v1.0/v1.1 ROM for generation
# (--base-rom, or put it at ~/.freeenterprise/base_rom.smc).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec "$HERE/runtime/bin/freeenterprise" \
    --native "$HERE/native" \
    --engine "$HERE/engine/python_ext" \
    --bridge "$HERE/engine/fe_bridge.py" \
    --library "${FE_LIBRARY:-$HOME/.freeenterprise/library}" \
    --work "${FE_WORK:-$HOME/.freeenterprise}" \
    "$@"
LAUNCHER
chmod +x "$STAGE/run.sh"

cat > "$STAGE/README.md" <<'README'
# Free Enterprise — Linux dev build

A test build of the desktop client: the same portable interface core as the Android
app, with the same emulator bridge and core sources, on Linux.

```sh
./run.sh                                   # the seed library, fullscreen
./run.sh --generate SEED --flags "Kmain"   # generate a seed, then play it
./run.sh --rom path/to/seed.smc            # play a seed you already have
./run.sh --help
```

Keys: arrows are the d-pad, X/Z are A/B, S/D are Y/X, Enter is Start, Q/W are L/R,
Tab opens the tracker, Esc opens the pause menu.

Controller (SDL2, detected automatically; the mapping database means most pads work):
A confirms, B cancels, the left stick drives the menus, Start opens our menu,
L2 opens the tracker, and R2 fast-forwards while held. SDL2 is loaded at runtime, so
a machine without it simply falls back to the keyboard.

Requires **JDK 17+** (`java -version`) and, for generation, **your own FF2 (US)
v1.0/v1.1 ROM** — pass `--base-rom /path/to/ff2.smc` or put it at
`~/.freeenterprise/base_rom.smc`. No ROM is included, and none is ever uploaded.

Known gaps: keyboard only (no controller backend yet), audio needs a 32 kHz stereo
line from the sound system, and there is no metadata sidecar or updater.
README

echo "== packing =="
mkdir -p "$OUT_DIR"
tar -C "$(dirname "$STAGE")" -czf "$OUT_DIR/$NAME.tar.gz" "$NAME"
sha256sum "$OUT_DIR/$NAME.tar.gz" | awk '{print $1"  "}' > "$OUT_DIR/$NAME.tar.gz.sha256"
echo "$(basename "$OUT_DIR/$NAME.tar.gz")  $(du -h "$OUT_DIR/$NAME.tar.gz" | cut -f1)"
echo "sha256: $(cut -d' ' -f1 < "$OUT_DIR/$NAME.tar.gz.sha256")"
