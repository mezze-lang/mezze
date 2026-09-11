#!/usr/bin/env bash
#
# Package IR-reuse test (#46): a dependency compiled once into its shared,
# content-addressed store is REUSED by a consumer instead of being re-lowered.
#
# Two dependency shapes, so both kinds of lowered function are covered:
#   - plain functions:   app → greet → core   (greet::wrapped calls core::base)
#   - ability operations: app → shoutlib       (Shout impl for Str; the op is
#                          lowered as a synthetic computeOpHash function)
#
# `mez sync` compiles every dep into its per-dep compiled-<compiler>.sqlite.
# `mez build` then compiles the consumer; because the read-side is wired
# (DepStoreLoader → PrecompiledDeps → the typechecker serves that IR), the
# consumer does NOT re-lower any dep function OR ability op.
#
# Assertions:
#   1. each dep gets a compiled store,
#   2. NONE of a dep's stored functions carry IR in the consumer's own compiled
#      set (src.sqlite) — proof they were served, not recompiled, and
#   3. the program still runs end to end (both values flow through).
#
# `dev --no-watch` runs in-memory and does NOT persist blobs; the consumer's
# compiled set comes from `build`, which does. Needs a built mez
# (`just build`) and sqlite3 (fetched via nix).
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ="${MEZ:-$REPO/cli/build/install/mez/bin/mez}"
[ -x "$MEZ" ] || { echo "❌ mez not found at $MEZ — run 'just build'."; exit 2; }
SQL() { nix shell nixpkgs#sqlite -c sqlite3 "$@"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cd "$W"
fail=0

"$MEZ" init core     --lib >/dev/null 2>&1
"$MEZ" init greet    --lib >/dev/null 2>&1
"$MEZ" init shoutlib --lib >/dev/null 2>&1
"$MEZ" init app      --app >/dev/null 2>&1

cat > core/src/lib.mz  <<'EOF'
pub let base = {} -> { label = "value-from-core" }
EOF
cat > greet/src/lib.mz <<'EOF'
use core::{base}

pub let wrapped = {} -> { label = base {}.label }
EOF
cat > shoutlib/src/lib.mz <<'EOF'
ability Shout for 'a where {
  shout: { it: 'a } -> Str
}

impl Shout for Str where {
  shout = { it } -> "shouted-from-dep"
}
EOF
cat > app/src/app.mz   <<'EOF'
use std::effects::log::{println, Console}
use greet::{wrapped}
use shoutlib::{Shout}

let main = {} -> perform Console in do
    let r = wrapped {}
    let s = "x".shout {}
    println { msg = r.label }
    println { msg = s }
EOF

( cd greet && "$MEZ" add core     --path ../core     >/dev/null 2>&1 )
( cd app   && "$MEZ" add greet    --path ../greet    >/dev/null 2>&1 )
( cd app   && "$MEZ" add shoutlib --path ../shoutlib >/dev/null 2>&1 )

cd app
echo "package IR-reuse e2e — precompiled deps (plain fns + ability ops) are reused, not re-lowered:"

# Precompile is on by default now that the read-side is wired.
"$MEZ" sync >/dev/null 2>&1
for dep in greet core shoutlib; do
  store="$(ls "$W"/$dep/compiled-*.sqlite 2>/dev/null | head -1)"
  [ -n "$store" ] && echo "  ✅ $dep precompiled → $(basename "$store")" \
    || { echo "  ❌ $dep not precompiled"; fail=1; }
done

# Consumer build persists its freshly-lowered set (dev --no-watch does not).
"$MEZ" build >/dev/null 2>&1
CONS="$W/app/.mez/store/src.sqlite"
[ -f "$CONS" ] || { echo "  ❌ consumer store missing after build"; exit 1; }

for dep in greet core shoutlib; do
  store="$(ls "$W"/$dep/compiled-*.sqlite 2>/dev/null | head -1)"
  [ -n "$store" ] || continue
  hashes="$(SQL "$store" 'SELECT hash FROM blobs WHERE ir IS NOT NULL;')"
  n=0; relowered=0
  while IFS= read -r h; do
    [ -n "$h" ] || continue
    n=$((n+1))
    # The consumer must not have lowered this dep function itself: it may keep a
    # typed-only interface blob, but never IR (which only scatterFunctionIRs of
    # its OWN loweredFunctions would write).
    if [ "$(SQL "$CONS" "SELECT count(*) FROM blobs WHERE hash='$h' AND ir IS NOT NULL;")" != "0" ]; then
      echo "  ❌ $dep: $h was RE-LOWERED by the consumer (carries IR)"; relowered=1; fail=1
    fi
  done <<< "$hashes"
  [ "$n" -gt 0 ] && [ "$relowered" = 0 ] && echo "  ✅ $dep: none of its $n fn(s) re-lowered — served from the compiled store"
  [ "$n" = 0 ] && { echo "  ❌ $dep: compiled store has no IR blobs to reuse"; fail=1; }
done

# Behavior still correct end to end — both values flow through.
out="$("$MEZ" dev --no-watch 2>&1 | grep -v WARNING)"
grep -qF "value-from-core" <<<"$out" && echo "  ✅ run → value-from-core (plain fn dep)" \
  || { echo "  ❌ run missing value-from-core"; fail=1; }
grep -qF "shouted-from-dep" <<<"$out" && echo "  ✅ run → shouted-from-dep (ability op dep)" \
  || { echo "  ❌ run missing shouted-from-dep"; fail=1; }

echo
[ "$fail" = 0 ] && echo "✅ pkg-reuse-e2e: precompiled deps (functions + ability ops) are reused, not re-lowered" \
  || echo "❌ pkg-reuse-e2e: FAILURES above"
exit "$fail"
