#!/usr/bin/env bash
# Every gate in the tooling module, one pass/fail.
#
# These checks accumulated across the grammar, the generators, and two editor
# extensions, each runnable only from its own directory with its own runner
# (bash here, python there, npm in the VS Code extension). Scattered like that
# they were easy not to run — which is how the Zed extension shipped with a
# query file that did not compile, and how six VS Code snippets shipped
# inserting code that does not parse.
#
#   tooling/check.sh           # everything
#   tooling/check.sh -v        # show each gate's output
#   tooling/check.sh <name>    # one gate, by substring
#
# Exit 0 iff every gate that could run passed. A gate whose tool is missing is
# reported SKIP and does not fail the run — but it is counted and printed, so a
# green line never silently means "did not check".
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
TS="$HERE/tree-sitter-mezze"
VSC="$HERE/vscode-extension/extension"

VERBOSE=0
FILTER=""
for a in "$@"; do
  case "$a" in
    -v|--verbose) VERBOSE=1 ;;
    *) FILTER="$a" ;;
  esac
done

PASS=0; FAIL=0; SKIP=0
FAILED_NAMES=()
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# gate <name> <needs-cmd|-> <dir> <command...>
gate() {
  local name="$1" need="$2" dir="$3"; shift 3
  [[ -n "$FILTER" && "$name" != *"$FILTER"* ]] && return 0
  printf '  %-26s ' "$name"
  if [[ "$need" != "-" ]] && ! command -v "$need" >/dev/null 2>&1; then
    echo "SKIP (no $need)"; SKIP=$((SKIP+1)); return 0
  fi
  if [[ ! -d "$dir" ]]; then
    echo "SKIP (no $dir)"; SKIP=$((SKIP+1)); return 0
  fi
  local out="$tmp/$RANDOM.log"
  ( cd "$dir" && "$@" ) >"$out" 2>&1
  local rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "PASS"; PASS=$((PASS+1))
    [[ $VERBOSE -eq 1 ]] && sed 's/^/        /' "$out"
  else
    echo "FAIL ($rc)"; FAIL=$((FAIL+1)); FAILED_NAMES+=("$name")
    tail -12 "$out" | sed 's/^/        /'
  fi
  return 0
}

echo "tooling gates"
echo

echo "grammar"
gate "corpus"            tree-sitter "$TS" tree-sitter test
gate "queries compile"   python3     "$TS" python3 scripts/check-queries.py .
gate "parser parity"     tree-sitter "$TS" scripts/parity.sh
gate "error containment" tree-sitter "$TS" scripts/containment.sh

echo
echo "generated query copies"
gate "copies match source" python3 "$HERE" python3 syntax-generators/gen-queries.py --check

echo
echo "zed extension"
gate "grammar pin"    tree-sitter "$HERE" syntax-generators/check-zed-pin.sh
gate "wasm + lsp export" cargo "$HERE/zed-extension" ./build.sh

echo
echo "vscode extension"
gate "textmate scopes" node "$VSC" node test/tmgrammar.test.js
gate "snippets parse"  node "$VSC" node test/snippets.test.js
gate "typescript"      npx  "$VSC" npx --no-install tsc -p ./ --noEmit

echo
echo "neovim plugin"
gate "lua syntax" nvim "$HERE/nvim-mezze" bash -c '
  for f in $(find . -name "*.lua"); do
    nvim --headless -u NONE -l /dev/stdin "$f" <<<"
      local ok, err = loadfile(_G.arg[1])
      if not ok then io.stderr:write(_G.arg[1]..\": \"..tostring(err)..\"\n\"); os.exit(1) end
    " || exit 1
  done'

echo
echo "website"
gate "highlighter" node "$REPO/website" node --experimental-strip-types --test test/highlight.test.mjs

echo
echo "-------------------------------------------"
printf 'pass %d   fail %d   skip %d\n' "$PASS" "$FAIL" "$SKIP"
if [[ $FAIL -gt 0 ]]; then
  echo "failed: ${FAILED_NAMES[*]}"
  exit 1
fi
[[ $SKIP -gt 0 ]] && echo "note: $SKIP gate(s) skipped for missing tools — not the same as passing."
exit 0
