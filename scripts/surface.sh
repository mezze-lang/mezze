#!/usr/bin/env bash
#
# Run bench/cases/surface — the string and pattern-matching rows — ONE ROW PER
# PROCESS, each at TWO sizes, because neither of those is optional.
#
# ONE ROW PER PROCESS: see scripts/ds-matrix.sh. Building inputs in the same
# process GCs while the timer runs; measured there as a 6x artefact, larger than
# anything worth chasing.
#
# TWO SIZES: a row is only believable once its ps/op stops moving with n. These
# rows span 400 ps to 313 000 ps per operation — a 750x spread — so no single n
# suits them. At n=200 000 `str::size` reads 1749 ps/op against 423 converged
# (4.1x of pure warmup) while `str::split` reads 314 391 against 312 909 (already
# converged, because one op does 60x more work). What actually matters is the
# WORK PER PASS, n x cost: below roughly 20ms a pass never leaves warmup. So
# every row gets its own n, sized to land there, and a second run at 4x to prove
# the number stopped moving. Two readings within ~10% is the regime proof that a
# ratio between rows needs; a row that moves is not reported.
#
# THE CONTROL HAS ITS OWN n, fixed at 4e8 for every row, and it is the same
# counted loop + add that ds_matrix uses. Both sides are PER-OP costs, so they
# need no common n. Ratios are what survive between two builds on a machine that
# drifts; absolute nanoseconds are not.
#
# ROWS 19-22 ARE A SLOPE, not ratios. `to_upper` and `concat` each run over a
# 15-character and a 131-character string, because a ratio to a control that
# does ONE add cannot compare an O(1) operation with an O(n) one: `str::size`
# reads 2x because it reads a field, `str::to_upper` reads 262x because it
# transforms 64 characters. Subtract the pair and divide by the length
# difference and the fixed cost cancels, leaving ps per character — 793 for
# to_upper against 32 for concat copying the same bytes. Read every O(n) string
# row that way.
#
# MATCHING AND ALLOCATION ARE DIFFERENCES, not rows. Four rows share ONE inline
# selector (`if i % 3 == 0 ... else if i % 3 == 1 ... else ...`) and differ by
# exactly one thing each, against the `choose-ctor arith only` baseline:
#
#   allocate (variant)  = "variant alloc, no match"  - baseline
#   allocate (record)   = "record alloc, 1 field"    - baseline   <- the control
#   match               = "variant match, no alloc"  - baseline
#   guard               = "build+match w/ guard"     - "variant build+match"
#   and "variant build+match" should land near the sum, which is the cross-check.
#
# Rows 16/17 repeat 10/13 over a variant with NO multi-field constructor. That
# pair is what showed the cost is construction, not matching: a two-field
# payload lowers to five allocations (variant + wrapper record + Object[2] +
# two Long boxes) against one for a fused single-field constructor. Read them
# with -Xlog:gc and count pauses, not nanoseconds — see PERF-LOG.
#
# The first version of these rows had a baseline doing ONE `% 3` against a match
# row doing TWO, plus a `mk {}` call in some rows and not others, so the "match
# cost" subtracted out of them carried a whole extra integer modulo. That is why
# the selector is written out inline in all four instead of factored into a
# helper. The record row is there because "a variant costs 3.6 ns" means nothing
# until a plain record is measured the same way (it costs 3.0).
#
#     bash scripts/surface.sh
#
# Needs: just build, then `mez build` in bench/cases/surface.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/bench/cases/surface"
BIN="$DIR/.build/surface"
FLOOR_N=400000000

if [ ! -x "$BIN" ]; then
  echo "build it first:  cd bench/cases/surface && MEZZE_HOME= mez build"
  exit 1
fi

# row:n — n sized so one pass is >= ~20ms at that row's measured cost
ROWS="1:80000000 2:10000000 3:20000000 4:2000000 5:400000 6:5000000 \
7:10000000 8:10000000 9:40000000 10:10000000 11:10000000 12:10000000 \
13:10000000 14:10000000 15:10000000 16:10000000 17:10000000 18:10000000 \
19:4000000 20:1000000 21:8000000 22:4000000 23:10000000 24:10000000"

cd "$DIR" || exit 1
echo "surface: one row per process, two sizes each, control n=$FLOOR_N"
for PAIR in $ROWS; do
  ROW="${PAIR%%:*}"
  N="${PAIR##*:}"
  BIG=$((N * 4))
  MEZZE_HOME= timeout 900 "$BIN" "$N" "$ROW" "$FLOOR_N" 2>/dev/null \
    | grep "ps/op" | grep -v "^control:"
  MEZZE_HOME= timeout 900 "$BIN" "$BIG" "$ROW" "$FLOOR_N" 2>/dev/null \
    | grep "ps/op" | grep -v "^control:" | sed 's/$/   [4x n]/'
done
