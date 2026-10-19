#!/usr/bin/env bash
#
# One-shot local install of the whole Mezze editor toolchain:
#   1. rebuild + install the native `mez` binary (so `mez lsp` is current)
#   2. compile + package the VS Code extension
#   3. install the extension into VS Code (--force = overwrite same/older version)
#
# After it finishes, reload the VS Code window ONCE
# (Cmd+Shift+P -> "Developer: Reload Window") to pick up the new extension
# and a fresh `mez lsp`.
#
# Usage:
#   tooling/install-dev.sh              # everything
#   tooling/install-dev.sh --skip-mez   # extension only (fast; when only the
#                                       # extension/grammar/config changed)
#   tooling/install-dev.sh --skip-ext   # mez binary only

set -euo pipefail

# Resolve the repo root from this script's location, so it works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EXT_DIR="$ROOT/tooling/vscode-extension/extension"

SKIP_MEZ=0
SKIP_EXT=0
for arg in "$@"; do
  case "$arg" in
    --skip-mez) SKIP_MEZ=1 ;;
    --skip-ext) SKIP_EXT=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [[ "$SKIP_MEZ" -eq 0 ]]; then
  echo "==> [1/3] Rebuilding + installing native mez ..."
  ( cd "$ROOT" && ./gradlew :cli:installNative )
  mez_ver="$("$HOME/.mezze/bin/mez" --version 2>&1 || true)"
  echo "    installed: $(sed -n '1p' <<<"$mez_ver")"
else
  echo "==> [1/3] Skipping mez rebuild (--skip-mez)"
fi

if [[ "$SKIP_EXT" -eq 0 ]]; then
  echo "==> [2/3] Compiling + packaging the VS Code extension ..."
  ( cd "$EXT_DIR" && npm run --silent compile )
  VSIX="$( cd "$EXT_DIR" && node_modules/.bin/vsce package --allow-star-activation 2>/dev/null \
            | sed -n 's/.*Packaged: \(.*\.vsix\).*/\1/p' )"
  # vsce prints the path with the DONE banner; fall back to newest vsix if the
  # grep missed (e.g. vsce output format drift).
  if [[ -z "${VSIX:-}" || ! -f "$VSIX" ]]; then
    mapfile -t _vsix < <(ls -t "$EXT_DIR"/*.vsix)
    VSIX="${_vsix[0]-}"
  fi
  echo "    packaged: $VSIX"

  echo "==> [3/3] Installing the extension into VS Code ..."
  if command -v code >/dev/null 2>&1; then
    code --install-extension "$VSIX" --force
  else
    echo "    'code' CLI not on PATH — install it via VS Code:" >&2
    echo "    Cmd+Shift+P -> 'Shell Command: Install code command in PATH'" >&2
    echo "    then re-run, or install manually: code --install-extension $VSIX --force" >&2
    exit 1
  fi
else
  echo "==> [2-3/3] Skipping extension build+install (--skip-ext)"
fi

echo ""
echo "Done. Reload the VS Code window ONCE to activate:"
echo "  Cmd+Shift+P -> Developer: Reload Window"
