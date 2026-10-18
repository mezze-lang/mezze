#!/usr/bin/env bash
# Interleaved A/B on one URL: A B A B, medians at the end.
#
# Point it at two BUILT jars of this app — one per compiler under test — in
# $SC/before/ and $SC/after/. Interleaving, not all-A-then-all-B, because this
# box moved 2.1% between two consecutive medians of the SAME config; alternating
# puts both in the same time window so drift lands on both.
#
#   ROUNDS=4 DUR=10 WARM=5 ./ab.sh '/work/20?mul=3'
#   ROUNDS=3 ./ab.sh '/health'        # the control: no codec, expect no gain
#
# `bench/compare.sh` does the same job generically; this exists because it takes
# two jars of ONE app rather than two commands, which is what comparing two
# COMPILERS needs.
set -uo pipefail
ROUNDS="${ROUNDS:-4}"; DUR="${DUR:-10}"; WARM="${WARM:-5}"; PORT=8111
URL_PATH="$1"
SC="${SC:?set SC to a directory holding before/ and after/ builds}"
WRK="nix shell nixpkgs#wrk --command wrk"
URL="http://127.0.0.1:$PORT$URL_PATH"

sample() {
  local variant="$1"
  pkill -9 -f "/$variant/http_work_route" 2>/dev/null
  sleep 1
  env MEZZE_HOME= "$SC/$variant/http_work_route" > "$SC/ab-$variant.log" 2>&1 &
  local pid=$!
  local up=0
  for i in $(seq 1 120); do
    if curl -fsS --max-time 1 "$URL" >/dev/null 2>&1; then up=1; break; fi
    sleep 0.5
  done
  if [ "$up" = 0 ]; then echo "NOTUP" ; kill -9 $pid 2>/dev/null; return 0; fi
  $WRK -t8 -c256 -d"${WARM}s" "$URL" >/dev/null 2>&1
  local r
  r=$($WRK -t8 -c256 -d"${DUR}s" "$URL" 2>&1 | grep -oP "Requests/sec:\s+\K[0-9.]+")
  kill -9 $pid 2>/dev/null
  wait $pid 2>/dev/null
  sleep 2
  echo "${r:-NORESULT}"
}

A=(); B=()
for r in $(seq 1 "$ROUNDS"); do
  a=$(sample before); A+=("$a")
  b=$(sample after);  B+=("$b")
  echo "round $r: before=$a  after=$b"
done
med() { printf '%s\n' "$@" | sort -n | awk '{x[NR]=$1} END {print (NR%2)?x[(NR+1)/2]:(x[NR/2]+x[NR/2+1])/2}'; }
MA=$(med "${A[@]}"); MB=$(med "${B[@]}")
echo "---"
echo "path     $URL_PATH"
echo "before   median $MA"
echo "after    median $MB"
awk -v a="$MA" -v b="$MB" 'BEGIN { printf "delta    %+.1f%% (after over before)\n", 100*(b-a)/a }'
