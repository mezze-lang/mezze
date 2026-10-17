#!/usr/bin/env bash
# A benchmark run you can believe a 2% difference from.
#
# Single runs on this box vary 3-4%, which is the size of most of what is left to
# measure — so a single run cannot answer any of it. Three things fix that:
#
#  1. SEVERAL SERVER STARTS, not one. Start-to-start variance (JIT shape, AppCDS
#     mapping, page cache) is real and does not average out within one process.
#  2. SEVERAL RUNS PER START, and the MEDIAN of everything. A median is immune to
#     the one bad run that drags a mean.
#  3. The launcher's own JVM options, untouched. Overriding them to "something
#     reasonable" once dropped -XX:SharedArchiveFile and moved a benchmark
#     238.7k -> 196.3k, four times the effect being hunted.
#
# Usage:  NAME PORT PATH -- command...
#   STARTS=3 RUNS=3 DUR=10 WARM=5  override the shape.
set -uo pipefail
NAME="$1"; PORT="$2"; PATHQ="$3"; shift 3
[ "${1:-}" = "--" ] && shift

STARTS="${STARTS:-3}"; RUNS="${RUNS:-3}"; DUR="${DUR:-10}"; WARM="${WARM:-5}"
WRK="nix shell nixpkgs#wrk --command wrk"
SAMPLES=()

for start in $(seq 1 "$STARTS"); do
  "$@" > "/tmp/bench-$NAME-$start.log" 2>&1 &
  PID=$!
  up=0
  for i in $(seq 1 120); do
    curl -fsS --max-time 1 "http://127.0.0.1:$PORT$PATHQ" >/dev/null 2>&1 && { up=1; break; }
    sleep 0.5
  done
  if [ "$up" = 0 ]; then
    echo "$NAME: never came up (start $start)"; tail -3 "/tmp/bench-$NAME-$start.log"
    kill -9 $PID 2>/dev/null; wait $PID 2>/dev/null; exit 1
  fi
  $WRK -t8 -c256 -d"${WARM}s" "http://127.0.0.1:$PORT$PATHQ" >/dev/null 2>&1
  for run in $(seq 1 "$RUNS"); do
    r=$($WRK -t8 -c256 -d"${DUR}s" "http://127.0.0.1:$PORT$PATHQ" 2>&1 \
        | grep -oP "Requests/sec:\s+\K[0-9.]+")
    [ -n "$r" ] && SAMPLES+=("$r")
  done
  kill -9 $PID 2>/dev/null; wait $PID 2>/dev/null
  sleep 2
done

printf '%s\n' "${SAMPLES[@]}" | awk -v n="$NAME" -v k="${#SAMPLES[@]}" '
  { a[NR]=$1 }
  END {
    asort(a)
    med = (NR % 2) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2
    printf "%-26s median %9.0f   min %9.0f   max %9.0f   spread %4.1f%%   n=%d\n",
           n, med, a[1], a[NR], 100*(a[NR]-a[1])/med, k
  }'
