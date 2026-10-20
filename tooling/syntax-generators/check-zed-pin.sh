#!/usr/bin/env bash
# Do the queries this extension SHIPS work against the grammar it PINS?
#
# Zed builds the parser from [grammars.mezze].commit, not from your worktree,
# so the two can disagree. When they do the failure is total: tree-sitter
# refuses a query naming a node the grammar lacks — the whole FILE, not the one
# pattern — and Zed renders Mezze with no highlighting at all. That is how the
# extension shipped once already.
#
# It must NOT be checked with `tree-sitter query`. That resolves a language by
# NAME and loads ~/.cache/tree-sitter/lib/mezze.so, which is built from whatever
# grammar you last generated — so it reports success against your worktree while
# you believe you are testing the pinned commit. This reads the pinned tree's
# own src/node-types.json instead, which cannot be confused about its source.
#
#   tooling/syntax-generators/check-zed-pin.sh
#
# Exit 0 iff the pinned commit exists and every shipped query resolves against it.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLING="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$TOOLING/.." && pwd)"
MANIFEST="$TOOLING/zed-extension/extension.toml"
ZED_QUERIES="$TOOLING/zed-extension/languages/mezze"

command -v tree-sitter >/dev/null 2>&1 || { echo "error: tree-sitter CLI not on PATH" >&2; exit 2; }

PIN="$(sed -n 's/^commit = "\(.*\)"/\1/p;' "$MANIFEST" | sed -n '1p')"
[[ -n "$PIN" ]] || { echo "error: no commit pin found in $MANIFEST" >&2; exit 2; }
echo "pinned grammar commit: $PIN"

if ! git -C "$REPO" cat-file -e "$PIN^{commit}" 2>/dev/null; then
  echo "FAIL the pinned commit is not an object in this repository."
  echo "     Zed fetches the grammar by this hash and would find nothing to build."
  exit 1
fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git -C "$REPO" archive "$PIN" tooling/tree-sitter-mezze 2>/dev/null | tar -x -C "$tmp" || {
  echo "FAIL the pinned commit has no tooling/tree-sitter-mezze tree."; exit 1; }

G="$tmp/tooling/tree-sitter-mezze"
( cd "$G" && tree-sitter generate >/dev/null 2>&1 ) || {
  echo "FAIL the pinned grammar does not generate."; exit 1; }

# check-queries.py takes a grammar dir and extra query dirs. Run the checker
# from the WORKTREE (it is the tool) against the PINNED grammar's node types.
python3 "$TOOLING/tree-sitter-mezze/scripts/check-queries.py" "$G" "$ZED_QUERIES" > "$tmp/out.txt" 2>&1
status=$?
if [[ $status -ne 0 ]]; then
  grep -v '^$' "$tmp/out.txt"
  echo
  echo "FAIL the shipped Zed queries name nodes the PINNED grammar does not have."
  echo "     Push the current grammar and update [grammars.mezze].commit in"
  echo "     tooling/zed-extension/extension.toml to the pushed hash."
  exit 1
fi

echo "OK  shipped Zed queries all resolve against the pinned grammar"
