#!/usr/bin/env bash
# bench/scripts/ab.sh — the in-process kernel harness, and the A/B that makes a
# compile-time change believable.
#
# `scripts/run.sh` is the cross-language instrument: whole-process user-cpu,
# Mezze against C/Java/Node/Python. This is the other one — per-kernel, in
# process, picoseconds per iteration, with a control beside every number.
#
# ── THE CONTRACT A CASE MUST MEET ───────────────────────────────────────────
#
#   ./.build/<case> <kernel> <n>
#
# prints one line per timed repetition:
#
#   <name>  <number> ps/iter  sum=<checksum>
#
# and EVERY kernel in the case computes the SAME checksum, so a kernel that got
# fast by dropping work shows up as a different number instead of a better one.
# Kernel 1 is the control by convention.
#
# ── WHY IT IS SHAPED LIKE THIS ──────────────────────────────────────────────
# Each rule below is here because its absence produced a wrong number that
# looked publishable. They are not stylistic.
#
#  1. ONE KERNEL PER PROCESS. Fifteen kernels in one JVM is a compiler lottery:
#     the same binary gave one kernel 215, 7097 and 7474 ps on three runs.
#
#  2. MIN of several, never mean or median. The minimum is the run least
#     disturbed by the rest of the machine; a mean reports the disturbance.
#
#  3. ONE CLI, TWO BUILDS — never two CLIs, and never the INSTALLED one. A
#     compile-time pass is read by `mez build`, so its A/B is two BUILDS from
#     one compiler, selected by the pass's own kill switch. Measuring one build
#     while believing it is the other is how two wrong claims reached PERF-LOG;
#     both times the CLI had not been rebuilt after the source changed.
#
#     The third time, this script was already correct and was BYPASSED: a case
#     was built by typing `mez build` in its own directory, which takes
#     `~/.mezze/bin/mez` — three days old, without the passes being measured.
#     The same kernel read 3 378 ps built that way and 449 ps built here. So
#     `one` and `run` now REFUSE a build this script did not make, rather than
#     trusting a `.build` of unknown provenance; `ab.sh build1 <case>` makes
#     one in place.
#
#  4. THE CONTROL IS PART OF THE RESULT. If kernel 1 moves between the columns,
#     the machine was busy and nothing else in the table means anything. A
#     saturated box once drifted a control from 227 to 712 ps — three times the
#     effect being measured. This script prints the control's drift and says so.
#
#  5. GC COUNTS, because they do not care about CPU contention. "Young
#     collections equal to the control's" is the only allocation evidence that
#     survives a loaded machine, and it is how the record work was verified at
#     all.
#
#  6. A TIER CONTROL IS THE CASE'S JOB. A kernel that leaves the fused
#     tail-loop tier must be compared against another kernel that also left it,
#     or the tier gap is charged to whatever the kernel was testing. That
#     mistake turned a ~0.38 ns effect operation into a published "5x".
#
# ── USAGE ───────────────────────────────────────────────────────────────────
#
#   ab.sh build  <case> <ENV=value>    two builds from one CLI; ENV disables
#                                      the pass under test in the "off" copy
#   ab.sh build1 <case>                one build, in the case dir, stamped
#   ab.sh run    <case> <kernel>...    measure, one process per kernel
#   ab.sh one    <case> <kernel>...    no A/B — just the current build
#
#   N=20000000 REPS=5 ab.sh run recordcost 1 2 4
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${AB_WORK:-/tmp/mezze-ab}"
N="${N:-20000000}"
REPS="${REPS:-5}"

# The DEV CLI from this worktree, never whatever is installed — an installed
# `mez` is stale the moment the compiler changes, and `MEZZE_HOME=` forces the
# dev runtime for the same reason.
MEZ="${MEZ:-$ROOT/cli/build/install/mez/bin/mez}"
if [ ! -x "$MEZ" ]; then
  echo "no dev CLI at $MEZ — run: ./gradlew :cli:installDist :cli:installRuntime" >&2
  exit 1
fi

mode="${1:-}"; case_name="${2:-}"
[ -z "$mode" ] || [ -z "$case_name" ] && { sed -n '2,60p' "$0"; exit 2; }
shift 2
CASE_DIR="$ROOT/bench/cases/$case_name"
[ -d "$CASE_DIR" ] || { echo "no such case: $case_name" >&2; exit 1; }

# The binary `mez build` produces is named from Mezze.toml's [[app]], which is
# not always the directory name.
binary_in() {
  local d="$1" b
  # `sed -n 1p` and not `head -1`: head EXITS after the first line, so under
  # `pipefail` the writer takes SIGPIPE and the pipeline reports 141 (see
  # scripts/lint-shell.sh, whose self-test measures exactly that). sed without
  # `q` drains stdin, so the status stays 0.
  b="$(ls "$d/.build" 2>/dev/null | grep -vE '\.(jar|jsa|bat)$' | sed -n '1p')"
  [ -n "$b" ] && echo "$d/.build/$b"
}

# Which compiler this is. The CLI's own jars are version-named, so the ast jar's
# basename identifies the build that produced them without stamping anything.
cli_id() {
  basename "$(ls "$(dirname "$MEZ")/../lib/"ast-*.jar 2>/dev/null | sed -n '1p')" 2>/dev/null \
    || echo unknown
}

build_one() { # dir  [ENV=value ...]
  local d="$1"; shift
  rm -rf "$d/.build" "$d/.mez"
  ( cd "$d" && MEZZE_HOME= env "$@" "$MEZ" build >/dev/null 2>&1 ) || return 1
  # The stamp is the whole point of rule 3: a `.build` without one was made by
  # some other compiler and its numbers are about that compiler, not this tree.
  printf '%s\n' "$(cli_id)" > "$d/.build/.ab-cli"
}

# Refuse a build this script did not make, or one made by a different compiler.
require_stamp() { # dir
  local d="$1" stamp want
  want="$(cli_id)"
  stamp="$(cat "$d/.build/.ab-cli" 2>/dev/null || true)"
  if [ -z "$stamp" ]; then
    cat >&2 <<EOF
$case_name has no ab.sh stamp — its .build was made by some other compiler,
most likely the installed \`mez\` on PATH, which is stale the moment this tree
changes. Rule 3. Build it here first:

    bench/scripts/ab.sh build1 $case_name

EOF
    exit 1
  fi
  if [ "$stamp" != "$want" ]; then
    echo "$case_name was built by $stamp but this CLI is $want — rebuild: ab.sh build1 $case_name" >&2
    exit 1
  fi
}

if [ "$mode" = "build1" ]; then
  build_one "$CASE_DIR" || { echo "build FAILED" >&2; exit 1; }
  echo "built $case_name with $(cli_id)"
  exit 0
fi

if [ "$mode" = "build" ]; then
  kill_switch="${1:-}"
  [ -z "$kill_switch" ] && { echo "build needs the pass's kill switch, e.g. MEZZE_SIZE_SINK=0" >&2; exit 2; }
  rm -rf "$WORK/$case_name"; mkdir -p "$WORK/$case_name"
  for v in off on; do cp -r "$CASE_DIR" "$WORK/$case_name/$v"; done
  build_one "$WORK/$case_name/off" "$kill_switch" || { echo "build off FAILED" >&2; exit 1; }
  build_one "$WORK/$case_name/on"                  || { echo "build on FAILED" >&2; exit 1; }
  echo "built $case_name: off($kill_switch) / on"
  exit 0
fi

# min ps/iter over REPS runs, each in its OWN process
best() { # binary kernel
  local bin="$1" k="$2" b=99999999999 ps
  for _ in $(seq "$REPS"); do
    ps=$("$bin" "$k" "$N" 2>/dev/null |
         grep -oE '[0-9]+ ps/iter' | grep -oE '^[0-9]+' | sort -n | sed -n '1p')
    [ -n "$ps" ] && [ "$ps" -lt "$b" ] && b="$ps"
  done
  [ "$b" = 99999999999 ] && echo FAIL || echo "$b"
}
gcs() { MEZZE_JAVA_OPTS="-Xlog:gc" "$1" "$2" "$N" 2>&1 | grep -cE "Pause Young"; }
sum() { "$1" "$2" "$N" 2>/dev/null | grep -oE 'sum=-?[0-9]+' | sed -n '1p'; }
label() { "$1" "$2" 1000 2>/dev/null | sed -n '1p' | sed -E 's/  +[0-9]+ ps.*//' | cut -c1-22; }

if [ "$mode" = "one" ]; then
  bin="$(binary_in "$CASE_DIR")"
  [ -z "$bin" ] && { echo "$case_name is not built — run: ab.sh build1 $case_name" >&2; exit 1; }
  require_stamp "$CASE_DIR"
  echo "# $case_name  N=$N  REPS=$REPS  (single build)"
  printf '%-24s %10s %8s   %s\n' kernel ps/iter young_gc checksum
  for k in "$@"; do
    printf '%-24s %10s %8s   %s\n' "$(label "$bin" "$k")" "$(best "$bin" "$k")" "$(gcs "$bin" "$k")" "$(sum "$bin" "$k")"
  done
  exit 0
fi

off="$(binary_in "$WORK/$case_name/off")"; on="$(binary_in "$WORK/$case_name/on")"
[ -z "$off" ] || [ -z "$on" ] && { echo "run ab.sh build $case_name <KILL=0> first" >&2; exit 1; }

echo "# $case_name  N=$N  REPS=$REPS  min of REPS, one process per kernel"
printf '%-22s %10s %10s %8s   %6s %6s   %s\n' kernel off on change gc_off gc_on checksum
ctrl_off=""; ctrl_on=""
for k in "$@"; do
  o="$(best "$off" "$k")"; n="$(best "$on" "$k")"
  [ -z "$ctrl_off" ] && { ctrl_off="$o"; ctrl_on="$n"; }
  so="$(sum "$off" "$k")"; sn="$(sum "$on" "$k")"
  ck=$([ "$so" = "$sn" ] && echo "$so" || echo "MISMATCH $so vs $sn")
  ch=$(awk -v a="$o" -v b="$n" 'BEGIN{ if (b+0>0 && a+0>0) printf "%.2fx", a/b; else print "-" }')
  printf '%-22s %10s %10s %8s   %6s %6s   %s\n' \
    "k$k $(label "$on" "$k")" "$o" "$n" "$ch" "$(gcs "$off" "$k")" "$(gcs "$on" "$k")" "$ck"
done

# Rule 4: the control decides whether any of the above is readable.
awk -v a="$ctrl_off" -v b="$ctrl_on" 'BEGIN{
  if (a+0<=0 || b+0<=0) exit
  d = (a>b ? a/b : b/a)
  if (d > 1.15) printf "\n!! CONTROL MOVED %.2fx (%s vs %s) — the machine was busy; treat every\n   number above as unreadable and measure again when it is quiet.\n", d, a, b
  else printf "\ncontrol steady (%s vs %s) — the columns are comparable.\n", a, b
}'
