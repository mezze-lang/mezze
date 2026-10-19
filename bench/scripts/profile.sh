#!/usr/bin/env bash
# bench/scripts/profile.sh — CPU-sample a Mezze bench under async-profiler.
#
# Uses async-profiler (installed via `brew install async-profiler`) to attach
# to the running JVM and capture stack samples via AsyncGetCallTrace, which
# handles Truffle's compiled code correctly (the built-in CpuSampler misses
# most of the JIT-compiled samples because it doesn't understand
# HotSpotTruffleRuntime frames).
#
# Purpose: give #405 (fib gap vs V8/C) real evidence rather than a guess at
# what's hot. Output is text (flat + tree) + collapsed stacks for FlameGraph.
#
# Usage:
#   ./bench/scripts/profile.sh                          # profile fib_profile
#   ./bench/scripts/profile.sh <case>                   # profile any bench case
#   ./bench/scripts/profile.sh -o /tmp/out.html <case>  # emit HTML flamegraph
#   ./bench/scripts/profile.sh --native <case>          # profile native binary
#
# Prerequisites:
#   brew install async-profiler   # provides asprof + libasyncProfiler.dylib
#   mez build has already run for the case (script does it if missing)
#
# Notes:
# - Only profiles the JVM path by default. Native builds have no
#   AsyncGetCallTrace hook, so async-profiler won't attach.
# - The bench workload must run long enough for the sampler (10ms default)
#   to gather hits. fib(42) works; fib(35) is too short.

set -uo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$BENCH_DIR/.." && pwd)"
MEZ="${MEZ:-$HOME/.mezze/bin/mez}"

CASE="fib_profile"
OUT=""
FORMAT="text"   # text | html | collapsed | jfr
MODE="jvm"      # jvm | native (native = probe only, no async-profiler)
INTERVAL="1ms"  # sampler period; 1ms gives ~1000/s samples on a busy CPU

while [[ $# -gt 0 ]]; do
    case "$1" in
        -o) OUT="$2"; shift 2;;
        -f) FORMAT="$2"; shift 2;;
        -i) INTERVAL="$2"; shift 2;;
        --native) MODE="native"; shift;;
        -h|--help) sed -n '2,28p' "$0"; exit 0;;
        *) CASE="$1"; shift;;
    esac
done

CASE_DIR="$BENCH_DIR/cases/$CASE"
if [[ ! -d "$CASE_DIR" ]]; then
    echo "error: no such case '$CASE' (looked in $CASE_DIR)" >&2
    exit 2
fi

# ─── build if needed ─────────────────────────────────────────────────────────
# Bench cases follow the convention: dir name is "<case>", but the app jar is
# named after the [package].name in Mezze.toml (usually "bench_<case>"). Read
# it out rather than guessing.
APP_NAME="$(grep -m1 -E '^name\s*=' "$CASE_DIR/Mezze.toml" | sed -E 's/.*"([^"]+)".*/\1/')"
if [[ -z "$APP_NAME" ]]; then
    echo "error: could not extract [package].name from $CASE_DIR/Mezze.toml" >&2
    exit 2
fi
JAR="$CASE_DIR/.build/${APP_NAME}.jar"
NATIVE_BIN="$CASE_DIR/.build/${APP_NAME}-native"

if [[ "$MODE" == "jvm" && ! -f "$JAR" ]]; then
    echo "==> building JVM bench for $CASE"
    (cd "$CASE_DIR" && "$MEZ" build) || { echo "build failed" >&2; exit 2; }
fi
if [[ "$MODE" == "native" && ! -x "$NATIVE_BIN" ]]; then
    echo "==> building native bench for $CASE (2-3 min)"
    (cd "$CASE_DIR" && "$MEZ" build --native) || { echo "build failed" >&2; exit 2; }
fi

# ─── async-profiler paths ────────────────────────────────────────────────────
if [[ "$MODE" == "jvm" ]]; then
    if ! command -v asprof >/dev/null 2>&1; then
        echo "error: asprof not on PATH — install with 'brew install async-profiler'" >&2
        exit 2
    fi
    # Some tools want the raw .dylib path; find it under the brew cellar.
    # mapfile, not `find | head -1`: head exits on the first line and
    # SIGPIPEs find, which under pipefail is a 141 the script reads as failure.
    mapfile -t _libs < <(find /opt/homebrew/Cellar/async-profiler -name libasyncProfiler.dylib 2>/dev/null)
    LIB="${_libs[0]-}"
    if [[ -z "$LIB" ]]; then
        echo "error: libasyncProfiler.dylib not found under /opt/homebrew/Cellar/async-profiler" >&2
        exit 2
    fi
fi

# ─── output path default ─────────────────────────────────────────────────────
STAMP="$(date -u +%Y%m%d-%H%M%S)"
if [[ -z "$OUT" ]]; then
    case "$FORMAT" in
        html)      OUT="$BENCH_DIR/results/${CASE}-${STAMP}.html";;
        collapsed) OUT="$BENCH_DIR/results/${CASE}-${STAMP}.collapsed";;
        jfr)       OUT="$BENCH_DIR/results/${CASE}-${STAMP}.jfr";;
        text|*)    OUT="$BENCH_DIR/results/${CASE}-${STAMP}.txt";;
    esac
fi
mkdir -p "$(dirname "$OUT")"

# ─── run ─────────────────────────────────────────────────────────────────────
if [[ "$MODE" == "native" ]]; then
    echo "==> native binaries have no AsyncGetCallTrace hook — only running"
    "$NATIVE_BIN"
    exit 0
fi

echo "==> profiling $CASE JVM (interval=$INTERVAL, format=$FORMAT)"
echo "    output → $OUT"

# asprof attaches to a running PID; for short-lived bench programs the
# easier path is to load the profiler as a JVM agent via -agentpath. That
# starts sampling at JVM start and dumps at shutdown, so we don't have to
# race asprof against a millisecond-scale program.
#
# Agent options docs: github.com/async-profiler/async-profiler#user-guide
# start,event=cpu,interval=<ns>,file=<path>,<output-format-key>
#
# Output-format key mapping:
#   text       → default (flat + tree summary)
#   collapsed  → `collapsed` (folded stacks; feed to FlameGraph.pl)
#   html       → `flamegraph` (self-contained interactive HTML)
#   jfr        → `jfr` (Java Flight Recorder binary)
case "$FORMAT" in
    html)      FMT_KEY="flamegraph";;
    collapsed) FMT_KEY="collapsed";;
    jfr)       FMT_KEY="jfr";;
    text|*)    FMT_KEY="flat=200,traces=50";;   # top 200 flat, top 50 traces
esac

# Interval accepted as e.g. "1ms" or nanoseconds directly. Agent spec uses
# suffix-free integers OR suffixes ns/us/ms/s.
AGENT_OPTS="start,event=cpu,interval=${INTERVAL},file=${OUT},${FMT_KEY}"

java \
    -Dpolyglot.engine.WarnInterpreterOnly=false \
    "-agentpath:${LIB}=${AGENT_OPTS}" \
    -jar "$JAR"

echo ""
if [[ ! -s "$OUT" ]]; then
    echo "warning: expected profile output at $OUT but file is missing/empty" >&2
    echo "         check async-profiler agent output above for errors" >&2
    exit 1
fi
echo "==> profile written to $OUT"
if [[ "$FORMAT" == "text" ]]; then
    echo ""
    echo "==> flat hotspots (top 40):"
    # asprof text output has traces first, then a flat table below. Print
    # the flat table lines (they look like: "  <ns> <pct>% <samples>  <name>").
    # Capture first: `grep … | head -40` SIGPIPEs grep once head has its 40
    # lines, and pipefail turns that into a failed exit on the last line of a
    # profile run that otherwise succeeded.
    flat="$(grep -E '^ +[0-9]+ +[0-9]+\.[0-9]+% +[0-9]+ ' "$OUT" || true)"
    head -40 <<<"$flat"
fi
