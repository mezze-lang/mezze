#!/usr/bin/env bash
#
# Package IR-reuse test (#46): a dependency compiled once into a shared,
# content-addressed store gets the SAME function hashes a consumer's own
# compile produces — the property the fromStore reuse hook relies on.
#
# app → greet → core (path deps). `MEZZE_PRECOMPILE_DEPS=1 mez sync` compiles
# greet + core into per-dep compiled-<compiler>.sqlite stores; `mez build`
# then compiles the consumer, persisting its whole closure. Every IR blob in a
# dep's precompiled store MUST appear (same hash) in the consumer's closure —
# otherwise reuse would silently never hit.
#
# `dev --no-watch` runs in-memory and does NOT persist blobs; the consumer
# reference set comes from `build`, which does. Needs a built mez
# (`just build`) and sqlite3 (fetched via nix).
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ="${MEZ:-$REPO/cli/build/install/mez/bin/mez}"
[ -x "$MEZ" ] || { echo "❌ mez not found at $MEZ — run 'just build'."; exit 2; }
SQL() { nix shell nixpkgs#sqlite -c sqlite3 "$@"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cd "$W"
fail=0

"$MEZ" init core  --lib >/dev/null 2>&1
"$MEZ" init greet --lib >/dev/null 2>&1
"$MEZ" init app   --app >/dev/null 2>&1

cat > core/src/lib.mz  <<'EOF'
pub let base = {} -> { label = "value-from-core" }
EOF
cat > greet/src/lib.mz <<'EOF'
use core::{base}

pub let wrapped = {} -> { label = base {}.label }
EOF
cat > app/src/app.mz   <<'EOF'
use std::effects::log::{println, Console}
use greet::{wrapped}

let main = {} -> perform Console in do
    let r = wrapped {}
    println { msg = r.label }
EOF

( cd greet && "$MEZ" add core  --path ../core  >/dev/null 2>&1 )
( cd app   && "$MEZ" add greet --path ../greet >/dev/null 2>&1 )

cd app
echo "package IR-reuse e2e — precompiled dep hashes vs consumer compile:"

MEZZE_PRECOMPILE_DEPS=1 "$MEZ" sync >/dev/null 2>&1
for dep in greet core; do
  store="$(ls "$W"/$dep/compiled-*.sqlite 2>/dev/null | head -1)"
  [ -n "$store" ] && echo "  ✅ $dep precompiled → $(basename "$store")" \
    || { echo "  ❌ $dep not precompiled"; fail=1; }
done

# Consumer build persists the full closure (dev --no-watch does not).
"$MEZ" build >/dev/null 2>&1
CONS="$W/app/.mez/store/src.sqlite"
[ -f "$CONS" ] || { echo "  ❌ consumer store missing after build"; exit 1; }

for dep in greet core; do
  store="$(ls "$W"/$dep/compiled-*.sqlite 2>/dev/null | head -1)"
  [ -n "$store" ] || continue
  hashes="$(SQL "$store" 'SELECT hash FROM blobs WHERE ir IS NOT NULL;')"
  n=0; miss=0
  while IFS= read -r h; do
    [ -n "$h" ] || continue
    n=$((n+1))
    if [ "$(SQL "$CONS" "SELECT count(*) FROM blobs WHERE hash='$h';")" != "1" ]; then
      echo "  ❌ $dep: hash $h absent from consumer compile"; miss=1; fail=1
    fi
  done <<< "$hashes"
  [ "$miss" = 0 ] && echo "  ✅ $dep: all $n IR hash(es) match the consumer's compile"
done

# Behavior still correct end to end.
out="$("$MEZ" dev --no-watch 2>&1 | grep -v WARNING)"
grep -qF "value-from-core" <<<"$out" && echo "  ✅ run → value-from-core" \
  || { echo "  ❌ run did not print value-from-core"; fail=1; }

echo
[ "$fail" = 0 ] && echo "✅ pkg-reuse-e2e: precompiled dep IR is hash-identical to a consumer compile" \
  || echo "❌ pkg-reuse-e2e: FAILURES above"
exit "$fail"
