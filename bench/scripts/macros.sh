#!/usr/bin/env bash
# Compile-time cost of macros: what `mez check` pays for N `name!( … )` calls.
#
# Reports the SLOPE (milliseconds per call, from the largest and smallest N) and
# the FIXED cost (N calls at the smallest N, minus the same code written by
# hand), never one diluted total: process start-up would hide both.
#
#   ./bench/scripts/macros.sh            # best of 3, N = 10 200 1000
#   MEZ=path/to/mez ./bench/scripts/macros.sh
set -euo pipefail

MEZ="${MEZ:-$HOME/.mezze/bin/mez}"
TRIALS="${TRIALS:-3}"
SIZES=(10 200 1000)
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/src"
printf '[package]\nname = "macrobench"\nversion = "0.1.0"\n\n[[app]]\nname = "macrobench"\npath = "src/app.mz"\nentry = "main"\n' > "$work/Mezze.toml"
cat > "$work/src/macros.mz" <<'MZ'
use std::macro::{ MacroInput, Expansion, parse_expr, expand_to, reject }

pub let double: { input: MacroInput } -> Expansion = { input } ->
  match parse_expr { input = input } is
    Ok { val } -> expand_to { e = `(~val * 2) }
    Err { error } -> reject { e = `(0), message = error.message, span = error.span }
MZ

# best-of-N wall milliseconds of `mez check` on the current src/app.mz
check() {
  local best=""
  for _ in $(seq 1 "$TRIALS"); do
    local s e t
    s=$(date +%s%N)
    (cd "$work" && "$MEZ" check > /dev/null 2>&1)
    e=$(date +%s%N)
    t=$(( (e - s) / 1000000 ))
    if [ -z "$best" ] || [ "$t" -lt "$best" ]; then best=$t; fi
  done
  echo "$best"
}

gen() { # $1 = N, $2 = macro|plain
  {
    [ "$2" = macro ] && echo 'use macros::{ double }'
    echo
    for i in $(seq 0 $(( $1 - 1 ))); do
      if [ "$2" = macro ]; then echo "let v$i = double!($i + 1)"; else echo "let v$i = ($i + 1) * 2"; fi
    done
    echo
    echo 'let main = {} -> {}'
  } > "$work/src/app.mz"
}

declare -A macro_t plain_t
for n in "${SIZES[@]}"; do
  gen "$n" macro; macro_t[$n]=$(check)
  gen "$n" plain; plain_t[$n]=$(check)
done

lo=${SIZES[0]}; hi=${SIZES[-1]}
extra_lo=$(( macro_t[$lo] - plain_t[$lo] ))
extra_hi=$(( macro_t[$hi] - plain_t[$hi] ))
slope=$(awk -v a="$extra_hi" -v b="$extra_lo" -v n="$(( hi - lo ))" 'BEGIN { printf "%.2f", (a - b) / n }')

printf '| calls | with macros (ms) | written by hand (ms) |\n|---|---|---|\n'
for n in "${SIZES[@]}"; do printf '| %s | %s | %s |\n' "$n" "${macro_t[$n]}" "${plain_t[$n]}"; done
printf '\nper call: %s ms (slope %s→%s)\nfixed, at %s calls: %s ms\n' "$slope" "$lo" "$hi" "$lo" "$extra_lo"
