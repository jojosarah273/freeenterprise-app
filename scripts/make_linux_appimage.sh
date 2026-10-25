#!/usr/bin/env bash
# Builds an AppImage alongside the usual tarball.
#
# The AppImage is a read-only squashfs mount, so the player's folder cannot live
# inside it: FF4_FE sits next to the .AppImage file, exactly as it sits next to the
# launcher in the tarball build. Likewise an engine update cannot write into the
# image - use the tarball build if you want in-app engine updates.
#
#   ./scripts/make_linux_appimage.sh [OUT_DIR]
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=${1:-"../FE_LINUX_ARCHIVE"}
TOOL="${APPIMAGETOOL:-/tmp/ait/appimagetool}"

VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts | head -1)
APPDIR="build/linux/appimage/FreeEnterprise.AppDir"
NAME="freeenterprise-linux-x86_64-${VERSION}.AppImage"

echo "== building the portable tree =="
./scripts/make_linux_test_build.sh "$OUT" >/dev/null
TARBALL=$(ls -t "$OUT"/freeenterprise-linux-x86_64-*.tar.gz | head -1)

echo "== staging $APPDIR =="
rm -rf build/linux/appimage
mkdir -p "$APPDIR"
tar -xzf "$TARBALL" -C build/linux/appimage
SRC=$(ls -d build/linux/appimage/freeenterprise-linux-x86_64-*)
mv "$SRC/runtime" "$SRC/engine" "$SRC/native" "$APPDIR/" 2>/dev/null || {
    mkdir -p "$APPDIR/usr"
    mv "$SRC/runtime" "$SRC/engine" "$SRC/native" "$APPDIR/usr/"
}
# Everything lives under usr/ so the image root holds only the AppImage metadata.
if [ -d "$APPDIR/runtime" ]; then
    mkdir -p "$APPDIR/usr"
    mv "$APPDIR/runtime" "$APPDIR/engine" "$APPDIR/native" "$APPDIR/usr/"
fi

cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
# Free Enterprise, from inside an AppImage.
#
# APPDIR is the mounted image (read-only). The player's folder lives beside the
# .AppImage file, so seeds, saves and states survive updates and reinstalls.
set -e
APPDIR="$(dirname "$(readlink -f "$0")")"

if [ -n "${APPIMAGE:-}" ]; then
    HERE="$(dirname "$(readlink -f "$APPIMAGE")")"
else
    HERE="$APPDIR"          # run directly from an extracted AppDir
fi

mkdir -p "$HERE/FF4_FE"

# The base ROM is the player's own; look beside the .AppImage, then in FF4_FE.
EXTRA=""
if [ -f "$HERE/ff2.smc" ]; then
    EXTRA="--base-rom $HERE/ff2.smc"
elif [ -f "$HERE/FF4_FE/ff2.smc" ]; then
    EXTRA="--base-rom $HERE/FF4_FE/ff2.smc"
fi

exec "$APPDIR/usr/runtime/bin/freeenterprise" \
    --native   "$APPDIR/usr/native" \
    --engine   "$APPDIR/usr/engine/python_ext" \
    --bridge   "$APPDIR/usr/engine/fe_bridge.py" \
    --checks   "$APPDIR/usr/engine/assets/checks.json" \
    --ui-spec  "$APPDIR/usr/engine/assets/ui_spec.json" \
    --presets  "$APPDIR/usr/engine/assets/presets.json" \
    --library  "$HERE/FF4_FE" \
    --work     "$HERE/FF4_FE/.work" \
    $EXTRA "$@"
RUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/freeenterprise.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Free Enterprise
Comment=Final Fantasy IV randomizer, with its own emulator
Exec=freeenterprise
Icon=freeenterprise
Terminal=false
Categories=Game;
DESKTOP

cp app/src/main/res/mipmap-xxxhdpi/ic_launcher.png "$APPDIR/freeenterprise.png"

echo "== packing the AppImage =="
mkdir -p "$OUT"
if [ ! -x "$TOOL" ]; then
    echo "appimagetool not found at $TOOL; set APPIMAGETOOL to its path" >&2
    exit 1
fi
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" "$APPDIR" "$OUT/$NAME" >/dev/null
sha256sum "$OUT/$NAME" | awk '{print $1"  "}' > "$OUT/$NAME.sha256"
echo "  $NAME  $(du -h "$OUT/$NAME" | cut -f1)"
echo "  sha256: $(cut -d' ' -f1 < "$OUT/$NAME.sha256")"
