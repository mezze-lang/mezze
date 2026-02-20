#!/bin/bash
set -e

# Smoke test script: builds CLI and runs comprehensive tests
# Usage: ./scripts/smoke-test.sh
#
# Note: Uses JAR-based distribution (not native) because 'mez build' needs
# access to JAR files for creating standalone executables.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================="
echo "  Building CLI Distribution"
echo "=========================================="

cd "$PROJECT_ROOT"
./gradlew :cli:installDist

# Find the mez launcher script
MEZ_BIN="$PROJECT_ROOT/cli/build/install/mez/bin/mez"
if [ ! -f "$MEZ_BIN" ]; then
    echo "❌ CLI not found at $MEZ_BIN"
    exit 1
fi

echo ""
echo "✅ CLI built: $MEZ_BIN"
echo ""

# Run the smoke tests with the CLI
echo "=========================================="
echo "  Running Smoke Tests"
echo "=========================================="

cd "$PROJECT_ROOT/docker-test-project"
MEZ="$MEZ_BIN" ./run-tests.sh
