#!/bin/bash
# Fast refresh of the installed mez runtime artifacts after code changes.
#
# What this does (and what it skips):
#   ✅ Rebuilds the shadow jar (cli/build/libs/mezze.jar)
#   ✅ Copies it to ~/.mezze/lib/mezze-lang.jar (where `mez build` reads from)
#   ✅ Optionally rebuilds the native binary if --native is passed
#   ❌ Does NOT touch shell config / PATH (the full installer handles that)
#
# Usage:
#   ./scripts/refresh.sh           # Just refresh the runtime jar (~10s)
#   ./scripts/refresh.sh --native  # Also rebuild native binary (~3 min)
#   ./scripts/refresh.sh --all     # Same as --native
#
# When to use:
#   After any change to the interpreter, typechecker, parser, or stdlib.
#   The shadow jar is what `mez build` copies into standalone JARs, so if
#   you don't refresh, your standalone jars run yesterday's code.

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
MEZZE_HOME="$HOME/.mezze"

REBUILD_NATIVE=false
case "${1:-}" in
    --native|--all) REBUILD_NATIVE=true ;;
    "") ;;
    *) echo "Usage: $0 [--native]"; exit 1 ;;
esac

cd "$REPO_ROOT"

echo -e "${BLUE}refreshing mez runtime${NC}"

# 1. Shadow jar — always rebuild. This is what mez build embeds.
echo -e "${YELLOW}  building shadow jar...${NC}"
./gradlew :cli:shadowJar -q

SHADOW_JAR="$REPO_ROOT/cli/build/libs/mezze.jar"
if [ ! -f "$SHADOW_JAR" ]; then
    echo "  shadow jar missing at $SHADOW_JAR" >&2
    exit 1
fi

mkdir -p "$MEZZE_HOME/lib"
cp "$SHADOW_JAR" "$MEZZE_HOME/lib/mezze-lang.jar"
SIZE=$(du -h "$MEZZE_HOME/lib/mezze-lang.jar" | cut -f1)
echo -e "${GREEN}  ✓ ~/.mezze/lib/mezze-lang.jar (${SIZE})${NC}"

# 2. Stdlib sqlite — refresh too. Built as part of compileStdlib.
STDLIB_SQLITE="$REPO_ROOT/cli/build/generated/resources/stdlib.sqlite"
if [ -f "$STDLIB_SQLITE" ]; then
    mkdir -p "$MEZZE_HOME/cache"
    cp "$STDLIB_SQLITE" "$MEZZE_HOME/cache/stdlib.sqlite"
    SIZE=$(du -h "$MEZZE_HOME/cache/stdlib.sqlite" | cut -f1)
    echo -e "${GREEN}  ✓ ~/.mezze/cache/stdlib.sqlite (${SIZE})${NC}"
fi

# 3. Native binary — only on request. ~3 minutes; usually not needed for
#    runtime changes since the native binary is just a CLI shell that
#    spawns a JVM with mezze-lang.jar.
if [ "$REBUILD_NATIVE" = "true" ]; then
    echo -e "${YELLOW}  building native binary (this takes a few minutes)...${NC}"
    ./gradlew :cli:nativeCompile -q

    NATIVE_BINARY="$REPO_ROOT/cli/build/native/nativeCompile/mezze"
    if [ -f "$NATIVE_BINARY" ]; then
        rm -f "$MEZZE_HOME/bin/mez"
        cp "$NATIVE_BINARY" "$MEZZE_HOME/bin/mez"
        chmod +x "$MEZZE_HOME/bin/mez"
        echo -e "${GREEN}  ✓ ~/.mezze/bin/mez (native)${NC}"
    else
        echo "  native binary missing at $NATIVE_BINARY" >&2
        exit 1
    fi
fi

echo -e "${GREEN}done${NC}"
