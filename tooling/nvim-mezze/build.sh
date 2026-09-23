#!/usr/bin/env bash
# Build the Mezze tree-sitter parser into this plugin as parser/mezze.so.
#
# Why this exists rather than leaning on nvim-treesitter's :TSInstall —
# src/parser.c is GENERATED from grammar.js and is gitignored (only the
# hand-written scanner.c is tracked). nvim-treesitter compiles src/parser.c
# directly; it does not run `tree-sitter generate`. So pointing it at this repo
# finds no parser at all. Generating here removes that dependency completely:
# with parser/mezze.so on the runtimepath, Neovim loads the grammar itself and
# nvim-treesitter becomes optional rather than required.
#
#   ./build.sh
#
# Needs: the tree-sitter CLI, and a C compiler.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAMMAR="$(cd "$HERE/../tree-sitter-mezze" && pwd)"
OUT="$HERE/parser/mezze.so"

command -v tree-sitter >/dev/null 2>&1 || {
  echo "error: tree-sitter CLI not on PATH (src/parser.c is generated, not committed)" >&2
  exit 2; }
CC="${CC:-cc}"
command -v "$CC" >/dev/null 2>&1 || { echo "error: no C compiler ($CC)" >&2; exit 2; }

echo "generating parser from $GRAMMAR/grammar.js ..."
( cd "$GRAMMAR" && tree-sitter generate ) >/dev/null || {
  echo "FAIL tree-sitter generate"; exit 1; }

[[ -f "$GRAMMAR/src/parser.c" ]] || { echo "FAIL no src/parser.c after generate"; exit 1; }

mkdir -p "$HERE/parser"
echo "compiling parser.c + scanner.c -> $(basename "$OUT") ..."
# -Isrc so the generated parser finds tree_sitter/parser.h. The external
# scanner is C, not C++, and carries the INDENT/DEDENT logic Mezze needs — a
# build that forgets it links but cannot parse an indented block.
"$CC" -o "$OUT" -shared -fPIC -Os \
  -I"$GRAMMAR/src" \
  "$GRAMMAR/src/parser.c" "$GRAMMAR/src/scanner.c" || {
  echo "FAIL compile"; exit 1; }

size=$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT")
echo "OK  $OUT ($((size / 1024)) KB)"
echo
echo "Neovim loads this from the runtimepath; no nvim-treesitter needed for the"
echo "parser itself. Verify with:  :lua =vim.treesitter.language.inspect('mezze')"
