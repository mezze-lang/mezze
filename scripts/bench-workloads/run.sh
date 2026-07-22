#!/usr/bin/env bash
# Real-workload BDSL vs AST bench. For each .mz workload copies it into a
# scratch Mezze project's src/app.mz, then runs `mez dev --no-watch` under
# MEZZE_BDSL={0,1} N times, reporting median wall-clock ms + ratio.
#
# Usage: ./run.sh [iterations=5]

set -euo pipefail

MEZ_BIN="${MEZ_BIN:-$HOME/.mezze/bin/mez}"
ITERS="${1:-5}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRATCH="${SCRATCH:-/tmp/bdsl-bench-scratch}"

if [[ ! -x "$MEZ_BIN" ]]; then
  echo "mez not found at $MEZ_BIN — set MEZ_BIN=/path/to/mez" >&2
  exit 1
fi

# Set up a scratch project once and reuse — mez init cost per workload
# would swamp our measurement.
if [[ ! -d "$SCRATCH/app" ]]; then
  rm -rf "$SCRATCH"
  mkdir -p "$SCRATCH"
  (cd "$SCRATCH" && "$MEZ_BIN" init app >/dev/null 2>&1)
fi

median() {
  sort -n | awk '{a[NR]=$1} END{if(NR==0){print "NaN";exit} if(NR%2==1) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2}'
}

time_ms() {
  # Prefer `date +%s%3N` (GNU) or fall back to nanoseconds (BSD/mac) and
  # divide. Avoids the python3 dependency.
  local start end nanos
  if date +%s%3N | grep -q '^[0-9]\+$'; then
    start=$(date +%s%3N)
    "$@" >/dev/null 2>&1
    end=$(date +%s%3N)
    echo $(( end - start ))
  else
    nanos=$(date +%s%N)
    start_s=${nanos:0:-6}
    "$@" >/dev/null 2>&1
    nanos=$(date +%s%N)
    end_s=${nanos:0:-6}
    echo $(( end_s - start_s ))
  fi
}

run_one() {
  local bdsl="$1"
  local runs=()
  for _ in $(seq 1 "$ITERS"); do
    # cd into scratch app for each run so mez dev finds Mezze.toml.
    runs+=("$( cd "$SCRATCH/app" && MEZZE_BDSL="$bdsl" time_ms "$MEZ_BIN" dev --no-watch )")
  done
  printf '%s\n' "${runs[@]}" | median
}

printf '%-20s %10s %10s %10s\n' "workload" "ast_ms" "bdsl_ms" "ratio"
printf '%-20s %10s %10s %10s\n' "--------" "------" "-------" "-----"
for mz in "$HERE"/*.mz; do
  name=$(basename "$mz" .mz)
  cp "$mz" "$SCRATCH/app/src/app.mz"
  # Prime once under each mode (JIT warmup + cache seed).
  ( cd "$SCRATCH/app" && MEZZE_BDSL=0 "$MEZ_BIN" dev --no-watch >/dev/null 2>&1 ) || true
  ( cd "$SCRATCH/app" && MEZZE_BDSL=1 "$MEZ_BIN" dev --no-watch >/dev/null 2>&1 ) || true
  ast=$(run_one 0)
  bdsl=$(run_one 1)
  if [[ "$ast" == "0" || "$ast" == "NaN" ]]; then
    ratio="n/a"
  else
    ratio=$(awk -v b="$bdsl" -v a="$ast" 'BEGIN{printf "%.2f", b/a}')
  fi
  printf '%-20s %10s %10s %10s\n' "$name" "$ast" "$bdsl" "$ratio"
done

echo
echo "ratio < 1.0 = BDSL faster; > 1.0 = BDSL slower. Median of $ITERS runs."
echo "Wall-clock includes JVM start + typecheck + compile — measurements are"
echo "dominated by fixed startup cost. Interpret as 'total end-to-end time'."
