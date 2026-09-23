#!/usr/bin/env bash
# Build the Zed extension's WASM component and prove it is loadable.
#
# Zed compiles this itself during `zed: install dev extension`, so this script
# is not required to ship — it exists so a broken shim is caught HERE, with a
# compiler error you can read, instead of inside Zed's installer where the
# failure surfaces as the extension simply not working.
#
# It checks the one export that matters: `language-server-command`. That is the
# function Zed calls to learn how to start `mez lsp`. A component that builds
# but does not export it produces syntax highlighting and no language server,
# which is exactly the sort of half-working state this tree has shipped before.
#
#   ./build.sh            # build + verify
#
# Needs a Rust toolchain with the wasm32-wasip1 target. nixpkgs' rustc knows the
# triple but ships no std for it, so rustup is the reliable route:
#   nix shell nixpkgs#rustup --command rustup target add wasm32-wasip1
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

TARGET=wasm32-wasip1
OUT="target/$TARGET/release/zed_mezze.wasm"

command -v cargo >/dev/null 2>&1 || {
  echo "error: cargo not on PATH." >&2
  echo "       try: nix shell nixpkgs#rustup --command rustup toolchain install stable" >&2
  exit 2
}

echo "building $TARGET (release)..."
cargo build --release --target "$TARGET" || {
  echo
  echo "FAIL the extension did not compile."
  echo "     If this is 'can't find crate for std', the wasm target is missing:"
  echo "       rustup target add $TARGET"
  exit 1
}

[[ -f "$OUT" ]] || { echo "FAIL no artifact at $OUT"; exit 1; }

size=$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT")
echo "artifact: $OUT ($((size / 1024)) KB)"

# Verify the LSP entry point is exported. wasm-objdump is the precise tool, but
# it is not always installed; fall back to a byte scan for the export NAME,
# which is present verbatim in the component's export section.
if command -v wasm-objdump >/dev/null 2>&1; then
  exports="$(wasm-objdump -x "$OUT" 2>/dev/null)"
  if grep -q 'language-server-command' <<<"$exports"; then
    echo "OK  exports language-server-command — Zed can start the LSP"
  else
    echo "FAIL the component does not export language-server-command."
    echo "     Zed would install this and give you highlighting with NO language server."
    exit 1
  fi
elif grep -q 'language-server-command' "$OUT"; then
  echo "OK  exports language-server-command (byte scan; install wabt for a real check)"
else
  echo "FAIL language-server-command not found in the component."
  exit 1
fi

echo
echo "Zed rebuilds this itself on install; run:"
echo "  command palette -> 'zed: install dev extension' -> $HERE"
