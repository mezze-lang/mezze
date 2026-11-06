#!/usr/bin/env bash
#
# A polymorphic callback through a polymorphic HOF: it must RUN, and the HOF
# must carry no dictionary.
#
# Why this is a script and not a JUnit test. Neither gated suite can see this
# defect:
#
#   - `:interpreter:test` runs through MezzeTestHelper, which takes the
#     interpreter path. The same programs ran CORRECTLY with the bug still in,
#     so a runtime-value test there passes either way. Two versions of one were
#     written before this was understood.
#   - `:typechecker:test` can reach the lowered IR via
#     `TypeChecker().typeCheck(...).loweredFunctions`, but that is NOT the
#     build's lowering: mono is seeded differently there, and `apply` keeps its
#     `__dict_add` with the fix in place, while `mez build` on the same source
#     drops it. (`MonoInheritedResidueTest` carries the same caveat — seeded 0
#     against the pipeline's 134.)
#
# So the property only exists on the BUILD path, which is what this runs.
#
# WHAT IT GUARDS. A reference to a polymorphic function used to be emitted
# carrying the DECLARATION'S SCHEME, so every call site showed the same scheme
# vars and the final substitution said nothing about them — while the solver had
# instantiated and resolved them per site. Lowering then read a var nothing had
# bound and picked the dictionary from the only thing visible, the RECEIVER. For
# a Float accumulator that is `impl NumAdd Float for Float`, which was handed
# the callback's Long:
#
#   UnsupportedSpecializationException … CallNativeDDD [FloatOps$$Lambda, 0.5, 21]
#
#     bash scripts/hof-dict-e2e.sh
#
# Needs: just build   -> cli/build/install/mez/bin/mez
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ="$ROOT/cli/build/install/mez/bin/mez"
fail=0

run_case() {
  local dir="$1" expect="$2" args="${3:-}"
  local name
  name="$(basename "$dir")"
  ( cd "$dir" && rm -rf .mez .build && MEZZE_HOME= "$MEZ" build >/dev/null 2>&1 ) || {
    echo "❌ $name: build failed"; fail=1; return
  }
  local got
  got=$( cd "$dir" && MEZZE_HOME= ./.build/"$name" $args 2>&1 | grep -v "WARNING" | tr '\n' ' ' | sed 's/ *$//' )
  if [ "$got" != "$expect" ]; then
    echo "❌ $name: expected [$expect], got [$got]"; fail=1; return
  fi
  # And no dictionary may reach a HOF that takes the callback as a PARAMETER:
  # mono reads the same per-site arrow and specialises the HOF per call site.
  local dicts
  dicts=$( cd "$dir" && MEZZE_HOME= MEZZE_DUMP_FN='*' ./.build/"$name" $args 2>&1 \
    | grep -oE "dump-fn\] [^ ]* hash=[0-9a-f]{8} params=\[[^]]*\]" | sort -u \
    | grep "__dict" | grep -E "params=\[n, f" )
  if [ -n "$dicts" ]; then
    echo "❌ $name: a HOF still takes a dictionary:"; echo "$dicts"; fail=1; return
  fi
  echo "✅ $name: $got"
}

# The recursive inner lambda captures the enclosing dictionary (the hash the
# self-call targeted used to be predicted a second, different way).
run_case "$ROOT/bench/cases/hofcapture" "120" "10"

# Heterogeneous `+` reached directly, both operand orders.
run_case "$ROOT/bench/cases/hetero" "21.5 21.5"

# THE CASE THIS WAS BUILT FOR: one un-annotated callback through one
# un-annotated HOF, at an Int accumulator and a Float one — the second a
# heterogeneous add. Dies with UnsupportedSpecializationException when the
# per-site arrow does not reach lowering.
run_case "$ROOT/bench/cases/hofpoly" "120 120.5" "10"

# WHAT IS NOT ASSERTED, and why. A HOF like `apply { n, f, zero }` still takes
# a `__dict_add` PARAMETER here. The dictionary in it is now the RIGHT one —
# that is what these cases check — but eliminating the parameter is
# monomorphisation's job, and mono reads the function node's TYPE, which is
# still the declaration's scheme.
#
# Measured: stamping the per-site arrow over `fn.type` in `CallEmit` DOES
# eliminate it (defunc's carriers go 2 to 1, `apply_fold`'s dict disappears and
# it gains two more dict-free specializations) and costs seven interpreter
# tests, all the receiver-less dictionary family —
#
#   no dictionary was supplied for ability operation `default` … for a
#   receiver-less operation that means resolving it from the type the call
#   PRODUCES
#
# — because that type is read by far more than dictionary resolution, and
# replacing a scheme with a concrete arrow changes who is judged to need a
# dictionary at all. So the arrow is a side table consulted only where
# dictionaries are resolved, and the parameter stays until mono can be given
# the same answer without everyone else seeing a different type.
if [ "$fail" = "0" ]; then
  echo "✅ hof-dict: every case runs, resolves the right dictionary, and carries none"
else
  echo "❌ hof-dict: FAILURES above — a defect the JVM suites cannot see"
fi
exit "$fail"
