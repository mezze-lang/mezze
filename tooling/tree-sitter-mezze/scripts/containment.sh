#!/usr/bin/env bash
# Containment gate: a mistake must stay LOCAL.
#
# This grammar exists to syntax-highlight. That makes "does tree-sitter accept
# exactly what the parser accepts?" the wrong question to optimise — the parser
# is deliberately lenient and defers to the validator, and matching that
# leniency buys a highlighter nothing. What a highlighter must never do is let
# ONE typo stop the rest of the file from highlighting.
#
# So: take each snippet, wrap it in valid declarations above and below, and
# check the wrapper still parses. Measured when this was written, over the 61
# snippets the parser accepted and the grammar did not:
#
#   56 stayed local  — the file kept highlighting; these are not defects
#    5 poisoned the file — an unclosed `{` inside `$(…)`, where the `"` meant to
#      close the string was read as the START of a new one, which then ran to
#      EOF. That is the bug worth fixing, and `must-parse` never saw it.
#
# It also catches the realistic case the corpus does not contain at all: simply
# TYPING `let s = "total: $(f { x = ` used to kill every declaration below it.
#
# Usage:
#   scripts/containment.sh          # summary
#   scripts/containment.sh -v       # list every snippet that poisons
#
# Exit 0 iff every snippet's damage stays inside the snippet.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAMMAR_DIR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$GRAMMAR_DIR/../.." && pwd)"
FIXTURE_DIR="$REPO_ROOT/fixtures/parser-corpus"
GEN_DIR="$FIXTURE_DIR/generated"

VERBOSE=0
[[ "${1:-}" == "-v" ]] && VERBOSE=1

command -v tree-sitter >/dev/null 2>&1 || { echo "error: tree-sitter CLI not on PATH" >&2; exit 2; }
( cd "$GRAMMAR_DIR" && tree-sitter generate >/dev/null ) || {
  echo "error: tree-sitter generate failed" >&2; exit 2; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# Four declarations of three DIFFERENT kinds, so a wrapper survivor count is not
# an artefact of one rule being unusually robust.
read -r -d '' PREFIX <<'MZ' || true
use std::collections::seq

type SentinelColor = | Red, Green, Blue |
MZ
read -r -d '' SUFFIX <<'MZ' || true
let sentinel_after: { n: Int } -> Int = { n } -> n + 1

type SentinelShape = | Circle, Square |
MZ

TOTAL=0; POISONED=0

check_file() {
  local fixture="$1" fname; fname="$(basename "$fixture")"
  rm -f "$tmp/$fname".*.mz
  awk -v out="$tmp" -v base="$fname" '
    /^# ===/ { blk++; next }
    { if (blk>0) print >> (out "/" base "." blk ".mz") }
  ' "$fixture"

  local blk
  for blk in "$tmp/$fname".*.mz; do
    [[ -e "$blk" ]] || continue
    grep -qvE '^\s*(#.*)?$' "$blk" || continue
    TOTAL=$((TOTAL+1))
    { printf '%s\n\n' "$PREFIX"; cat "$blk"; printf '\n\n%s\n' "$SUFFIX"; } > "$tmp/wrapped.mz"
    local out survivors
    out="$(cd "$GRAMMAR_DIR" && tree-sitter parse "$tmp/wrapped.mz" 2>&1)"
    # Count the wrapper's own declarations that still parsed at top level.
    survivors=$(grep -cE '^  \((use_declaration|type_declaration|let_declaration)' <<<"$out")
    if [[ "$survivors" -lt 4 ]]; then
      POISONED=$((POISONED+1))
      local n; n="$(basename "$blk" .mz | sed "s/^$fname\.//")"
      echo "POISON $fname block $n — only $survivors/4 wrapper decls survived"
      [[ $VERBOSE -eq 1 ]] && sed 's/^/      /' "$blk"
    fi
  done
}

for fixture in "$FIXTURE_DIR"/*.mz; do
  [[ -e "$fixture" ]] || continue
  check_file "$fixture"
done
[[ -f "$GEN_DIR/accepted.mz" ]] && check_file "$GEN_DIR/accepted.mz"

echo "-----------------------------------------"
echo "containment: $((TOTAL-POISONED))/$TOTAL snippets keep their damage local"
if [[ $POISONED -eq 0 ]]; then
  echo "OK"
  exit 0
else
  echo "$POISONED snippet(s) POISON the surrounding file (build gate)"
  exit 1
fi
