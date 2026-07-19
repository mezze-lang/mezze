#!/usr/bin/env bash
# Parity gate: every snippet in fixtures/parser-corpus/*.mz must parse under the
# tree-sitter grammar with no ERROR / MISSING node. The Kotlin `SharedCorpus
# ParityTest` guarantees the same snippets parse under the real parser, so a
# green run of both means the grammar and the parser agree on this corpus.
#
# Usage:
#   scripts/parity.sh            # check all fixtures, print a summary
#   scripts/parity.sh -v         # also print the failing tree for each miss
#
# Exit code 0 iff every snippet parses cleanly.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAMMAR_DIR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$GRAMMAR_DIR/../.." && pwd)"
FIXTURE_DIR="$REPO_ROOT/fixtures/parser-corpus"

VERBOSE=0
[[ "${1:-}" == "-v" ]] && VERBOSE=1

if ! command -v tree-sitter >/dev/null 2>&1; then
  echo "error: tree-sitter CLI not found on PATH (enter devenv shell)" >&2
  exit 2
fi
if [[ ! -d "$FIXTURE_DIR" ]]; then
  echo "error: fixture dir not found: $FIXTURE_DIR" >&2
  exit 2
fi

# Regenerate the parser so we test the current grammar.js.
( cd "$GRAMMAR_DIR" && tree-sitter generate >/dev/null ) || {
  echo "error: tree-sitter generate failed" >&2; exit 2; }

total=0
fails=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Split a fixture file into snippet blocks on lines matching `^# ===`.
# Each block is written to $tmp/NNN.mz and parsed individually.
for fixture in "$FIXTURE_DIR"/*.mz; do
  [[ -e "$fixture" ]] || continue
  fname="$(basename "$fixture")"
  # awk splits into blocks; a block is the text between `# ===` markers.
  awk -v out="$tmp" -v base="$fname" '
    /^# ===/ { blk++; label=$0; sub(/^# === ?/, "", label); labels[blk]=label; next }
    { if (blk>0) print >> (out "/" base "." blk ".mz") }
  ' "$fixture"

  # Parse each produced block file.
  for blk in "$tmp/$fname".*.mz; do
    [[ -e "$blk" ]] || continue
    # Skip blocks that are only blank lines / comments (no real snippet).
    if ! grep -qvE '^\s*(#.*)?$' "$blk"; then continue; fi
    total=$((total+1))
    if out="$(cd "$GRAMMAR_DIR" && tree-sitter parse "$blk" 2>&1)"; then
      # tree-sitter parse exits 0 even with ERROR nodes in some versions;
      # also scan the tree text to be safe.
      if grep -qE '\(ERROR|\(MISSING|MISSING ' <<<"$out"; then
        fails=$((fails+1))
        echo "FAIL  $fname block $(basename "$blk" | sed "s/^$fname\.//;s/\.mz$//")"
        [[ $VERBOSE -eq 1 ]] && { sed 's/^/      /' "$blk"; echo "$out" | sed 's/^/      /'; }
      fi
    else
      fails=$((fails+1))
      echo "FAIL  $fname block $(basename "$blk" | sed "s/^$fname\.//;s/\.mz$//") (parse errored)"
      [[ $VERBOSE -eq 1 ]] && { sed 's/^/      /' "$blk"; echo "$out" | sed 's/^/      /'; }
    fi
  done
done

echo "-----------------------------------------"
echo "tree-sitter parity: $((total-fails))/$total snippets parse cleanly"
[[ $fails -eq 0 ]] && echo "OK" || echo "$fails FAILED"
exit $(( fails > 0 ? 1 : 0 ))
