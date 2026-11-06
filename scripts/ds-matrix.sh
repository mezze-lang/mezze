#!/usr/bin/env bash
#
# Run the Phase G0 collection matrix ONE ROW PER PROCESS, which is the only way
# its numbers have held up.
#
# Why not just run the binary. All twelve rows in one process build a 1M cons
# list and a 1M seq and then time ops against them, so the collector runs while
# the timer does. Measured, same binary, same N: `seq::filter+size` read 4 852
# ps/elem all-in-one against 753 one-per-process, and `seq::count` 4 465
# against 758 — a 6x measurement artefact, larger than any gap anyone would be
# chasing. The bench flags it (`UNDER-WARMED`, and a louder line when the
# CONTROL itself is unstable) but flagging is not fixing.
#
# Every row prints ps/elem and a ratio to a control measured in that same
# process: a counted loop over the same arithmetic with no collection in it.
# Ratios are what survive between two builds of the compiler on a machine that
# drifts; absolute nanoseconds are not, and a whole session was spent learning
# that the hard way.
#
#     bash scripts/ds-matrix.sh [N]
#
# Needs: just build, then `mez build` in bench/cases/ds_matrix.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/bench/cases/ds_matrix"
BIN="$DIR/.build/bench_ds_matrix"
N="${1:-1000000}"
ROWS=12

if [ ! -x "$BIN" ]; then
  echo "build it first:  cd bench/cases/ds_matrix && MEZZE_HOME= mez build"
  exit 1
fi

echo "Phase G0 matrix, N=$N, one row per process"
cd "$DIR" || exit 1
for i in $(seq 1 "$ROWS"); do
  MEZZE_HOME= timeout 600 "$BIN" "$N" "$i" 2>/dev/null \
    | grep -E "ps/elem|CONTROL is under-warmed" \
    | grep -v "^control:"
done
