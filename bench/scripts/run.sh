#!/usr/bin/env bash
# bench/scripts/run.sh — cross-language perf harness.
#
# Runs each case (fib / sum_fold) across C / Java / Node / Python /
# Mezze JVM / Mezze native. Reports **min user-cpu** across N trials
# (default 3). Writes a Markdown table to bench/results/<date>.md.
#
# Usage:
#   ./bench/scripts/run.sh              # all cases, 3 trials each
#   ./bench/scripts/run.sh fib          # single case
#   ./bench/scripts/run.sh -n 5 fib     # 5 trials
#   ./bench/scripts/run.sh --no-build   # skip mez rebuilds (reuse cached binaries)
#   ./bench/scripts/run.sh --no-native  # skip Mezze native rebuild (slow)

set -uo pipefail

# ─── setup ───────────────────────────────────────────────────────────────────
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$BENCH_DIR/.." && pwd)"
MEZ="${MEZ:-$HOME/.mezze/bin/mez}"

N=3
CASES=()
BUILD_MEZ=1
BUILD_NATIVE=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        -n) N="$2"; shift 2;;
        --no-build) BUILD_MEZ=0; BUILD_NATIVE=0; shift;;
        --no-native) BUILD_NATIVE=0; shift;;
        -h|--help) sed -n '2,20p' "$0"; exit 0;;
        *) CASES+=("$1"); shift;;
    esac
done
[[ ${#CASES[@]} -eq 0 ]] && CASES=(fib sum_fold)

# ─── portable timer ──────────────────────────────────────────────────────────
# Reports user-cpu in seconds (float). No wall clock, no children — just user
# time for the command. Uses gtime if present (nix / brew coreutils on macOS),
# else falls back to bash `time` + TIMEFORMAT.
time_user() {
    local out
    if command -v gtime >/dev/null 2>&1; then
        out=$(gtime -f "%U" "$@" 2>&1 >/dev/null | tail -1)
    else
        # bash builtin time via TIMEFORMAT — captures user via redirection
        # 2>&1 captures stderr where `time` prints; discard cmd stdout.
        out=$( { TIMEFORMAT='%3U'; time "$@" >/dev/null 2>&1; } 2>&1 )
    fi
    # If the string has 'm' (like "0m0.145s") normalize; else it's already seconds
    if [[ "$out" == *m* ]]; then
        local m s
        m=$(echo "$out" | awk -Fm '{print $1}')
        s=$(echo "$out" | awk -Fm '{print $2}' | tr -d 's')
        awk -v m="$m" -v s="$s" 'BEGIN{printf "%.3f", m*60+s}'
    else
        printf "%.3f\n" "$out"
    fi
}

# Run N trials, return min user-cpu. Skips if first trial fails (missing tool).
min_of_n() {
    local n=$1; shift
    local best="" t
    for ((i=0; i<n; i++)); do
        t=$(time_user "$@" 2>/dev/null) || return 1
        [[ -z "$t" ]] && return 1
        if [[ -z "$best" ]] || awk -v a="$t" -v b="$best" 'BEGIN{exit !(a<b)}'; then
            best="$t"
        fi
    done
    echo "$best"
}

# ─── prep ────────────────────────────────────────────────────────────────────
mkdir -p "$BENCH_DIR/results" "$BENCH_DIR/build"

# Compile C + Java once.
if [[ ! -x "$BENCH_DIR/build/fib_c" ]] || [[ "$BENCH_DIR/reference/fib.c" -nt "$BENCH_DIR/build/fib_c" ]]; then
    cc -O2 -o "$BENCH_DIR/build/fib_c" "$BENCH_DIR/reference/fib.c"
fi
if [[ ! -x "$BENCH_DIR/build/sum_fold_c" ]] || [[ "$BENCH_DIR/reference/sum_fold.c" -nt "$BENCH_DIR/build/sum_fold_c" ]]; then
    cc -O2 -o "$BENCH_DIR/build/sum_fold_c" "$BENCH_DIR/reference/sum_fold.c"
fi
javac -d "$BENCH_DIR/build" "$BENCH_DIR/reference"/*.java 2>/dev/null

# Build Mezze binaries.
if (( BUILD_MEZ )); then
    for case in "${CASES[@]}"; do
        case_dir="$BENCH_DIR/cases/$case"
        [[ -d "$case_dir" ]] || { echo "unknown case: $case" >&2; exit 2; }
        echo "▸ building $case (jvm)..." >&2
        (cd "$case_dir" && "$MEZ" build > /dev/null 2>&1) || { echo "  jvm build failed"; }
        if (( BUILD_NATIVE )); then
            echo "▸ building $case (native, ~1 min)..." >&2
            (cd "$case_dir" && "$MEZ" build --native > /dev/null 2>&1) || { echo "  native build failed"; }
        fi
    done
fi

# ─── run ─────────────────────────────────────────────────────────────────────
declare -A results   # results["$case,$runtime"]=<seconds or "-">

RUNTIMES=(c java node python mezze_jvm mezze_native)

for case in "${CASES[@]}"; do
    case_dir="$BENCH_DIR/cases/$case"

    # C
    if [[ -x "$BENCH_DIR/build/${case}_c" ]]; then
        results[$case,c]=$(min_of_n "$N" "$BENCH_DIR/build/${case}_c")
    fi
    # Java
    case_upper=$(tr '[:lower:]' '[:upper:]' <<< "${case:0:1}")${case:1}
    java_class=$(echo "$case_upper" | sed 's/_\([a-z]\)/\U\1/g')
    if [[ -f "$BENCH_DIR/build/$java_class.class" ]]; then
        results[$case,java]=$(min_of_n "$N" java -cp "$BENCH_DIR/build" "$java_class")
    fi
    # Node
    if command -v node >/dev/null && [[ -f "$BENCH_DIR/reference/$case.js" ]]; then
        results[$case,node]=$(min_of_n "$N" node "$BENCH_DIR/reference/$case.js")
    fi
    # Python — try `python3` on $PATH; if bogus (nix marker), use nix-shell.
    if [[ -f "$BENCH_DIR/reference/$case.py" ]]; then
        if python3 "$BENCH_DIR/reference/$case.py" >/dev/null 2>&1; then
            results[$case,python]=$(min_of_n "$N" python3 "$BENCH_DIR/reference/$case.py")
        elif command -v nix-shell >/dev/null; then
            # nix-shell adds ~500ms startup, unavoidable. Note in the table.
            results[$case,python]=$(min_of_n "$N" nix-shell -p python3 --run "python3 $BENCH_DIR/reference/$case.py")
        fi
    fi
    # Mezze JVM jar (glob — the jar's basename is the pkg name in
    # Mezze.toml, not necessarily the case dir name).
    mez_jar=$(find "$case_dir/.build" -maxdepth 1 -name '*.jar' -not -name '*.jsa' 2>/dev/null | head -1)
    if [[ -n "$mez_jar" ]]; then
        results[$case,mezze_jvm]=$(min_of_n "$N" java -jar "$mez_jar")
    fi
    # Mezze native (glob for -native suffix)
    mez_native=$(find "$case_dir/.build" -maxdepth 1 -type f -perm -u+x -name '*-native' 2>/dev/null | head -1)
    if [[ -n "$mez_native" ]]; then
        results[$case,mezze_native]=$(min_of_n "$N" "$mez_native")
    fi
done

# ─── emit table ──────────────────────────────────────────────────────────────
DATE=$(date -u +%Y-%m-%d)
TIME=$(date -u +%H:%M)
OUT="$BENCH_DIR/results/${DATE}.md"

{
    echo "# bench results — $DATE $TIME UTC"
    echo ""
    echo "min user-cpu (seconds) across $N trials. rows = case; cols = runtime."
    echo ""
    # header
    printf "| case |"
    for rt in "${RUNTIMES[@]}"; do printf " %s |" "$rt"; done
    echo ""
    printf "%s" "|------|"
    for rt in "${RUNTIMES[@]}"; do printf "%s" "------|"; done
    echo ""
    # rows
    for case in "${CASES[@]}"; do
        printf "| **%s** |" "$case"
        for rt in "${RUNTIMES[@]}"; do
            v="${results[$case,$rt]:-}"
            [[ -z "$v" ]] && v="-"
            printf " %s |" "$v"
        done
        echo ""
    done
    echo ""
    echo "Cases: ${CASES[*]}. Trials per cell: $N."
    echo ""
    if [[ -n "${results[fib,mezze_native]:-}" ]] && [[ -n "${results[fib,c]:-}" ]]; then
        ratio=$(awk -v m="${results[fib,mezze_native]}" -v c="${results[fib,c]}" 'BEGIN{printf "%.1f", m/c}')
        echo "**fib**: mezze_native = ${ratio}× C"
    fi
    if [[ -n "${results[sum_fold,mezze_native]:-}" ]] && [[ -n "${results[sum_fold,node]:-}" ]]; then
        ratio=$(awk -v m="${results[sum_fold,mezze_native]}" -v n="${results[sum_fold,node]}" 'BEGIN{printf "%.1f", m/n}')
        echo "**sum_fold**: mezze_native = ${ratio}× node"
    fi
} | tee "$OUT"

cp "$OUT" "$BENCH_DIR/results/latest.md"
echo ""
echo "→ $OUT"
