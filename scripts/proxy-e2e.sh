#!/usr/bin/env bash
#
# End-to-end test of the ~/.mezze/bin/mez toolchain proxy (ProxyDispatch).
#
# Uses the REAL native `mez` binary as the proxy, but STUB toolchain binaries
# (tiny scripts that echo which version ran) so the test is fast and asserts on
# *which* toolchain the proxy dispatches to under each precedence rule. Fully
# non-destructive: everything lives under a temp MEZZE_HOME.
#
# Needs the native CLI binary (`./gradlew :cli:nativeCompile`). Run:
#     bash scripts/proxy-e2e.sh                 # uses cli/build/native/nativeCompile/mez
#     bash scripts/proxy-e2e.sh /path/to/mez    # or an explicit binary
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ_NATIVE="${1:-$REPO/cli/build/native/nativeCompile/mez}"
if [ ! -x "$MEZ_NATIVE" ]; then
  echo "❌ native mez not found at $MEZ_NATIVE"
  echo "   build it with: ./gradlew :cli:nativeCompile   (or pass a path)"
  exit 2
fi

HOME_DIR="$(mktemp -d)"; trap 'rm -rf "$HOME_DIR"' EXIT

mkstub() { # <version> — a stub toolchain binary that announces itself + its args
  mkdir -p "$HOME_DIR/toolchains/$1/bin"
  printf '#!/bin/sh\necho "RAN %s :: $*"\n' "$1" > "$HOME_DIR/toolchains/$1/bin/mez"
  chmod +x "$HOME_DIR/toolchains/$1/bin/mez"
}
mkstub 0.1.0; mkstub 0.1.7; mkstub 0.2.0

# The proxy is the real native binary at the proxy path.
mkdir -p "$HOME_DIR/bin"; cp "$MEZ_NATIVE" "$HOME_DIR/bin/mez"; chmod +x "$HOME_DIR/bin/mez"
PX="$HOME_DIR/bin/mez"

fail=0
run() { MEZZE_HOME="$HOME_DIR" "$@" 2>&1; }
expect() { # <name> <wanted-substring> <cmd...>
  local name="$1" want="$2"; shift 2
  local out; out="$(run "$@")"
  if grep -qF -- "$want" <<<"$out"; then echo "  ✅ $name"
  else echo "  ❌ $name — wanted '$want', got:"; echo "$out" | sed 's/^/       /'; fail=1; fi
}

echo "proxy dispatch (real native binary → stub toolchains):"
# no pins → latest installed
expect "latest (no pins)"    "RAN 0.2.0 :: check"     "$PX" check
# explicit +ver wins and is stripped from forwarded args
expect "+ver selector"       "RAN 0.1.0 :: exec a.mz" "$PX" +0.1.0 exec a.mz
# MEZ_VERSION env beats project pin + default
expect "MEZ_VERSION env"     "RAN 0.1.7 :: build"     env MEZ_VERSION=0.1.7 "$PX" build
# `toolchain default` is handled in-process by the proxy (not dispatched)…
run "$PX" toolchain default 0.1.0 >/dev/null
# …and then governs resolution
expect "global default"      "RAN 0.1.0 :: check"     "$PX" check
# project [toolchain] channel (track 0.1 → newest on it) overrides the default
PROJ="$HOME_DIR/proj"; mkdir -p "$PROJ"; printf '[toolchain]\nchannel = "0.1"\n' > "$PROJ/Mezze.toml"
expect "project pin (track)" "RAN 0.1.7 :: check"     bash -c "cd '$PROJ' && MEZZE_HOME='$HOME_DIR' '$PX' check"
# uninstalled request → helpful failure, non-zero
out="$(run env MEZ_VERSION=9.9.9 "$PX" check)"; code=$?
if grep -qF "not installed" <<<"$out" && [ "$code" != 0 ]; then echo "  ✅ uninstalled → error+nonzero"
else echo "  ❌ uninstalled handling (code=$code): $out"; fail=1; fi
# exit-code forwarding (target 0.2.0 explicitly so it is deterministic)
printf '#!/bin/sh\nexit 7\n' > "$HOME_DIR/toolchains/0.2.0/bin/mez"; chmod +x "$HOME_DIR/toolchains/0.2.0/bin/mez"
run "$PX" +0.2.0 check >/dev/null; code=$?
if [ "$code" = 7 ]; then echo "  ✅ exit-code forwarded (7)"; else echo "  ❌ exit-code forwarding: got $code"; fail=1; fi

echo
[ "$fail" = 0 ] && echo "✅ proxy-e2e: all dispatch rules pass" || echo "❌ proxy-e2e: FAILURES above"
exit "$fail"
