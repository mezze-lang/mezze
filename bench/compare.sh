#!/usr/bin/env bash
# Compare two server configs by INTERLEAVING them: A B A B A B.
#
# Running all of A then all of B makes the comparison hostage to drift — this box
# moved 2.1% between two consecutive medians of the SAME config, which is the
# size of the differences worth finding. Alternating puts both configs in the same
# time window, so a thermal or page-cache shift lands on both.
#
# Usage:  ./compare.sh ROUNDS "nameA:portA:pathA:cmdA" "nameB:portB:pathB:cmdB"
set -uo pipefail
ROUNDS="${1:-4}"; A="$2"; B="$3"
DUR="${DUR:-10}"; WARM="${WARM:-5}"
WRK="nix shell nixpkgs#wrk --command wrk"

declare -a SA SB
one() {  # name port path cmd...  -> one sample
  local name="$1" port="$2" pathq="$3"; shift 3
  "$@" > "/tmp/cmp-$name.log" 2>&1 &
  local pid=$!
  local up=0
  for i in $(seq 1 120); do
    curl -fsS --max-time 1 "http://127.0.0.1:$port$pathq" >/dev/null 2>&1 && { up=1; break; }
    sleep 0.5
  done
  if [ "$up" = 0 ]; then echo "SKIP $name: never came up" >&2; kill -9 $pid 2>/dev/null; return 1; fi
  $WRK -t8 -c256 -d"${WARM}s" "http://127.0.0.1:$port$pathq" >/dev/null 2>&1
  local r
  r=$($WRK -t8 -c256 -d"${DUR}s" "http://127.0.0.1:$port$pathq" 2>&1 \
      | grep -oP "Requests/sec:\s+\K[0-9.]+")
  kill -9 $pid 2>/dev/null; wait $pid 2>/dev/null; sleep 2
  echo "$r"
}

for r in $(seq 1 "$ROUNDS"); do
  IFS=':' read -r an ap apath acmd <<< "$A"
  IFS=':' read -r bn bp bpath bcmd <<< "$B"
  s=$(one "$an" "$ap" "$apath" $acmd) && SA+=("$s")
  s=$(one "$bn" "$bp" "$bpath" $bcmd) && SB+=("$s")
  echo "  round $r: $an=${SA[-1]:-?}  $bn=${SB[-1]:-?}"
done

med() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {print (NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2}'; }
MA=$(med "${SA[@]}"); MB=$(med "${SB[@]}")
IFS=':' read -r an _ _ _ <<< "$A"; IFS=':' read -r bn _ _ _ <<< "$B"
awk -v a="$MA" -v b="$MB" -v an="$an" -v bn="$bn" 'BEGIN {
  printf "\n%-22s median %9.0f\n%-22s median %9.0f\ndelta %+.1f%% (%s over %s)\n",
         an, a, bn, b, 100*(b-a)/a, bn, an
}'
