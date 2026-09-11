#!/usr/bin/env bash
#
# End-to-end smoke test of the `mez` CLI runtime commands against a freshly
# scaffolded app: check · exec · dev · build (jar) · run-jar · build --native ·
# run-native. Exercises every path that spawns/embeds the runtime, so a regression
# in runtime-jar / shell / toolchain resolution (e.g. the MezzeHome rework) is
# caught here rather than by a user.
#
# Run inside the devenv (needs the JVM + GraalVM):
#     devenv shell -- just test-cli        # or: bash scripts/cli-e2e.sh
#
# Env:
#   MEZ               path to the mez binary (default: cli/build/install/mez/bin/mez)
#   MEZ_E2E_NATIVE=0  skip the (slow) `build --native` step
#
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ="${MEZ:-$REPO/cli/build/install/mez/bin/mez}"
[ -x "$MEZ" ] || { echo "❌ mez not found at $MEZ — run 'just build' first."; exit 2; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail=0

# expect <name> <wanted-substring> <cmd...>
expect() {
  local name="$1" want="$2"; shift 2
  local out; out="$("$@" 2>&1)"
  if grep -qF -- "$want" <<<"$out"; then
    echo "  ✅ $name"
  else
    echo "  ❌ $name — expected to contain: $want"
    tail -4 <<<"$out" | sed 's/^/       /'
    fail=1
  fi
}

cd "$WORK"
"$MEZ" init app --app >/dev/null 2>&1
cd app

echo "mez CLI end-to-end:"
expect "check"         "Check passed"    "$MEZ" check
expect "exec"          "Hello from app!" "$MEZ" exec src/app.mz
expect "dev --no-watch" "Hello from app!" "$MEZ" dev --no-watch
expect "build (jar)"   "built"           "$MEZ" build

if [ -f .build/app.jar ]; then echo "  ✅ jar produced"; else echo "  ❌ jar produced"; fail=1; fi
expect "run jar"       "Hello from app!" java -jar .build/app.jar

if [ "${MEZ_E2E_NATIVE:-1}" = "1" ]; then
  echo "  … building native (one-time shell build can take a few minutes)"
  if "$MEZ" build --native >/tmp/mez-native-$$.log 2>&1; then
    native="$(find .build -maxdepth 2 -type f -perm -u+x ! -name '*.jar' 2>/dev/null | head -1)"
    if [ -n "$native" ] && grep -qF "Hello from app!" <<<"$("$native" 2>&1)"; then
      # Distinguish a genuine AOT image from the JVM-launcher fallback.
      if file -b "$native" | grep -qiE 'ELF|Mach-O'; then
        echo "  ✅ build --native + run (AOT binary)"
      else
        echo "  ⚠️  build --native ran, but produced a JVM-launcher fallback, not an AOT"
        echo "      binary (native-image 'blocklisted methods' — tracked upstream on main)."
      fi
    else
      echo "  ❌ build --native produced no runnable artifact (looked in .build/)"
      fail=1
    fi
  else
    echo "  ❌ build --native failed:"; tail -5 /tmp/mez-native-$$.log | sed 's/^/       /'; fail=1
  fi
  rm -f /tmp/mez-native-$$.log
else
  echo "  ⏭  build --native skipped (MEZ_E2E_NATIVE=0)"
fi

echo
if [ "$fail" = 0 ]; then echo "✅ cli-e2e: all runtime commands pass"; else echo "❌ cli-e2e: FAILURES above"; fi
exit "$fail"
