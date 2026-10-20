#!/usr/bin/env bash
# Gate for the dev channel: the structural invariants plus the portable core's
# unit tests. Run before merging dev into main, and before cutting a release.
#
#   ./scripts/selftest.sh [version]      # version is checked against build.gradle.kts
set -uo pipefail
cd "$(dirname "$0")/.."

echo "== invariants =="
./scripts/check_invariants.sh "${1:-}" || exit 1

echo
echo "== portable core and desktop tests =="
./gradlew --quiet :core:test :desktop:test || exit 1
echo "  core and desktop tests passed"

echo
echo "all self-tests passed"
