#!/usr/bin/env bash
#
# End-to-end package-management test: a TRANSITIVE local dependency graph that is
# both type-checked AND run. app → greet → core, where the middle library `greet`
# actually uses the deeper `core`. Guards the bug where a dep-of-a-dep type-checked
# but failed to compile/run because the middle library's own imports were resolved
# against the TOP project's .deps instead of its own.
#
# Needs a built mez (`just build`). Non-destructive: everything under a temp dir.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ="${MEZ:-$REPO/cli/build/install/mez/bin/mez}"
[ -x "$MEZ" ] || { echo "❌ mez not found at $MEZ — run 'just build'."; exit 2; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cd "$W"
fail=0

"$MEZ" init core  --lib >/dev/null 2>&1
"$MEZ" init greet --lib >/dev/null 2>&1
"$MEZ" init app   --app >/dev/null 2>&1

# core exposes a value; greet re-exposes core's value; app prints it.
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
echo "package management e2e — transitive graph app → greet → core:"

# sync should materialize BOTH greet (into app) and core (transitively, into greet)
"$MEZ" sync >/dev/null 2>&1
[ -e "$W/greet/.deps/mezze"/core-* ] && echo "  ✅ core linked transitively into greet/.deps" \
  || { echo "  ❌ core not linked into greet/.deps"; fail=1; }

# type-check
if "$MEZ" check 2>&1 | grep -qF "Check passed"; then echo "  ✅ check (app→greet→core resolves)"
else echo "  ❌ check failed"; "$MEZ" check 2>&1 | grep -iE 'error|not found' | head -2 | sed 's/^/       /'; fail=1; fi

# RUN — the value must flow core → greet → app at runtime (this is what regressed)
out="$("$MEZ" dev --no-watch 2>&1 | grep -v WARNING)"
if grep -qF "value-from-core" <<<"$out"; then echo "  ✅ run (dev --no-watch) → value-from-core"
else echo "  ❌ run (dev --no-watch):"; echo "$out" | grep -iE 'not found|error' | head -1 | sed 's/^/       /'; fail=1; fi

echo
[ "$fail" = 0 ] && echo "✅ pkg-e2e: transitive dependency graph type-checks and runs" \
  || echo "❌ pkg-e2e: FAILURES above"
exit "$fail"
