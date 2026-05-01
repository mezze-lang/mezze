#!/bin/bash
# Thin wrapper around the gradle install + clean tasks. Keeps the
# script the same for muscle memory; all real work happens in the
# build files.
#
# Usage:
#   ./scripts/refresh.sh                    # gradle :cli:installRuntime
#   ./scripts/refresh.sh --native           # also installs native binary
#   ./scripts/refresh.sh --clean            # cleanInstall + reinstall
#   ./scripts/refresh.sh --clean --native   # full reset + native rebuild
#   ./scripts/refresh.sh --all              # same as --clean --native

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

REBUILD_NATIVE=false
CLEAN=false
for arg in "$@"; do
    case "$arg" in
        --native) REBUILD_NATIVE=true ;;
        --clean)  CLEAN=true ;;
        --all)    REBUILD_NATIVE=true; CLEAN=true ;;
        "") ;;
        *) echo "Usage: $0 [--native] [--clean] [--all]"; exit 1 ;;
    esac
done

cd "$REPO_ROOT"

TASKS=()
if [ "$CLEAN" = "true" ]; then
    TASKS+=(":cli:cleanInstall")
fi
TASKS+=(":cli:installRuntime")
if [ "$REBUILD_NATIVE" = "true" ]; then
    TASKS+=(":cli:installNative")
fi

./gradlew "${TASKS[@]}"
