#!/bin/sh
# Build the engine-update bundle that the app's "Check for updates" installs.
#
# The app looks for a release asset named:
#     freeenterprise-engine-<engine version>.zip
# where <engine version> is the VERSION_STR from FreeEnt/version.py
# (e.g. freeenterprise-engine-4.7.1.Gale.zip). The zip must contain the trees at
# these paths, because the updater copies them straight over app storage:
#
#     python_ext/...  spec/...  presets/...
#
# Usage:  scripts/make_engine_bundle.sh [output-dir]
# Then attach the zip (and its .sha256) to a GitHub release. The app verifies the
# download against the release asset's sha256 digest, which the API provides.
set -e
cd "$(dirname "$0")/.."
OUT="$(cd "${1:-.}" && pwd)"
ASSETS=app/src/main/assets
VERSION=$(grep -oE 'VERSION_STR = f"\{MAJOR\}\.\{MINOR\}\.\{PATCH\}\.\{FORK\}"' "$ASSETS/python_ext/FreeEnt/version.py" >/dev/null 2>&1 \
  && python3 - <<'PY'
import re
src = open('app/src/main/assets/python_ext/FreeEnt/version.py').read()
ns = {}
for k in ('MAJOR','MINOR','PATCH','FORK'):
    m = re.search(rf'^{k}\s*=\s*(.+)$', src, re.M)
    ns[k] = m.group(1).strip().strip('"')
print(f"{ns['MAJOR']}.{ns['MINOR']}.{ns['PATCH']}.{ns['FORK']}")
PY
)
NAME="freeenterprise-engine-${VERSION}.zip"
rm -f "$OUT/$NAME" "$OUT/$NAME.sha256"
( cd "$ASSETS" && zip -qr "$OUT/$NAME" python_ext spec presets )
( cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256" )
echo "built  : $OUT/$NAME  ($(du -h "$OUT/$NAME" | cut -f1))"
echo "sha256 : $(cut -d' ' -f1 < "$OUT/$NAME.sha256")"
echo
echo "Attach BOTH files to the GitHub release whose version is ${VERSION}."
