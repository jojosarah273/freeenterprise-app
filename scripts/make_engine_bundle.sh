#!/bin/sh
# Build the engine bundle that makes a dialog-free in-place update possible.
#
# The app looks for a release asset named freeenterprise-engine-<version>.zip,
# where <version> matches VERSION_STR in FreeEnt/version.py (e.g.
# freeenterprise-engine-4.7.1.Gale.zip). The zip must contain these trees at its
# root, because the app copies them straight over its storage:
#
#     python_ext/...  spec/...  presets/...
#
# Usage:  scripts/make_engine_bundle.sh [output-dir]
# Attach the zip (and its .sha256) to the same GitHub release as the APK. Users
# never see it: the app offers one "Update" and picks whichever payload it needs.
set -e
cd "$(dirname "$0")/.."
OUT="$(cd "${1:-.}" && pwd)"
ASSETS=app/src/main/assets
VERSION=$(python3 - <<'PY'
import re
src = open('app/src/main/assets/python_ext/FreeEnt/version.py').read()
def part(k):
    m = re.search(rf'^\s*{k}\s*=\s*(.+?)\s*$', src, re.M)
    return m.group(1).strip().strip('"').strip("'")
print(".".join(part(k) for k in ("MAJOR", "MINOR", "PATCH", "FORK")))
PY
)
NAME="freeenterprise-engine-${VERSION}.zip"
rm -f "$OUT/$NAME" "$OUT/$NAME.sha256"
( cd "$ASSETS" && zip -qr "$OUT/$NAME" python_ext spec presets )
( cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256" )
echo "built  : $OUT/$NAME  ($(du -h "$OUT/$NAME" | cut -f1))"
echo "sha256 : $(cut -d' ' -f1 < "$OUT/$NAME.sha256")"
echo
echo "Attach BOTH files to the release whose engine is ${VERSION}."
