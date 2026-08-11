#!/usr/bin/env bash
# FASE 16 (frontend/backend/gateway separation, ADR-014): a real, automated regression check -
# not just a one-time manual inspection - that the three modules stay genuinely independent.
# Exits non-zero (and prints exactly what it found) the moment any of the four rules below is
# violated, so a regression is caught the same way a failing test would be, not discovered later
# by reading code.
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

check() {
    local description="$1"
    local result="$2"
    if [ -n "$result" ]; then
        echo "FAIL: $description"
        echo "$result" | sed 's/^/    /'
        fail=1
    else
        echo "PASS: $description"
    fi
}

# 1. backend/ must never contain a Node/npm project (no package.json, no node_modules).
check "backend/ has no Node/npm project" \
    "$(find backend -iname 'package.json' -o -iname 'node_modules' -maxdepth 6 2>/dev/null || true)"

# 2. frontend/ must never contain a Maven project (no pom.xml, no mvnw).
check "frontend/ has no Maven project" \
    "$(find frontend -iname 'pom.xml' -o -iname 'mvnw' -maxdepth 2 2>/dev/null | grep -v node_modules || true)"

# 3. gateway/ must never contain a Node/npm project.
check "gateway/ has no Node/npm project" \
    "$(find gateway -iname 'package.json' -o -iname 'node_modules' -maxdepth 6 2>/dev/null || true)"

# 4. No module's Dockerfile may COPY from, or reference the build output of, another module -
# this is exactly the FASE 15 pattern (`COPY --from=frontend-build ... backend/static`) that
# FASE 16 was required to dismantle (brief section 47). Only actual `COPY` instruction lines are
# checked (comments are stripped first) - the Dockerfiles' own explanatory prose about this exact
# forbidden pattern would otherwise false-positive against itself.
check "no Dockerfile copies another module's build output" \
    "$(grep -hE '^\s*COPY' backend/Dockerfile frontend/Dockerfile gateway/Dockerfile 2>/dev/null \
        | grep -E '(\.\./frontend|\.\./backend|\.\./gateway|frontend/dist|backend/target)' || true)"

if [ "$fail" -ne 0 ]; then
    echo
    echo "Module separation check FAILED - see above."
    exit 1
fi

echo
echo "Module separation check PASSED - backend/, frontend/, and gateway/ remain genuinely independent."
