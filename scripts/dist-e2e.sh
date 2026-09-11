#!/usr/bin/env bash
#
# End-to-end distribution test: install MULTIPLE real toolchain versions side by
# side, then verify every selection mechanism dispatches to the right one — using
# real `mez` binaries (not stubs) and asserting on `mez --version`. Fully
# non-destructive: a throwaway MEZZE_HOME under a temp dir.
#
# Reuses two already-installed toolchains from ~/.mezze/toolchains (packages them
# into tarballs, no rebuild). Run after at least two `installNative` runs, e.g.
# `-Pversion=0.1.0 :cli:installNative` and `-Pversion=0.2.0 :cli:installNative`.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_TC="$HOME/.mezze/toolchains"
V1="${V1:-0.1.0}"; V2="${V2:-0.2.0}"
INSTALL="$REPO/scripts/install-mez.sh"

for v in "$V1" "$V2"; do
  [ -x "$SRC_TC/$v/bin/mez" ] || { echo "❌ need $SRC_TC/$v installed (run: ./gradlew -Pversion=$v :cli:installNative)"; exit 2; }
done

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
HOME_DIR="$WORK/home"
mkdir -p "$WORK/dist"
tar -C "$SRC_TC" -czf "$WORK/dist/$V1.tgz" "$V1"
tar -C "$SRC_TC" -czf "$WORK/dist/$V2.tgz" "$V2"

echo "▸ installing $V1 and $V2 into a throwaway home…"
MEZZE_HOME="$HOME_DIR" bash "$INSTALL" "$WORK/dist/$V1.tgz" >/dev/null 2>&1
MEZZE_HOME="$HOME_DIR" bash "$INSTALL" "$WORK/dist/$V2.tgz" >/dev/null 2>&1
PX="$HOME_DIR/bin/mez"

fail=0
ver() { MEZZE_HOME="$HOME_DIR" "$@" 2>&1 | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1; }
expect() { # <name> <wanted-vX.Y.Z> <cmd...>
  local name="$1" want="$2"; shift 2
  local got; got="$(ver "$@")"
  if [ "$got" = "$want" ]; then echo "  ✅ $name → $got"
  else echo "  ❌ $name → got '$got', wanted '$want'"; fail=1; fi
}

echo "multi-version selection (real binaries):"
# both installed?
n="$(MEZZE_HOME="$HOME_DIR" "$PX" toolchain list 2>&1 | grep -cE "  $V1| $V1 |  $V2| $V2 ")"
[ "$n" -ge 2 ] && echo "  ✅ both $V1 and $V2 listed" || { echo "  ❌ toolchain list missing a version"; fail=1; }

# global default
MEZZE_HOME="$HOME_DIR" "$PX" toolchain default "$V1" >/dev/null 2>&1
expect "global default = $V1"        "v$V1" "$PX" --version
MEZZE_HOME="$HOME_DIR" "$PX" toolchain default "$V2" >/dev/null 2>&1
expect "global default = $V2"        "v$V2" "$PX" --version

# project pin in Mezze.toml ([toolchain] channel) overrides the default
PROJ="$WORK/proj"; mkdir -p "$PROJ"
printf '[package]\nname="p"\nversion="0.0.0"\n\n[toolchain]\nchannel = "%s"\n' "$V1" > "$PROJ/Mezze.toml"
got="$(cd "$PROJ" && ver "$PX" --version)"
[ "$got" = "v$V1" ] && echo "  ✅ Mezze.toml [toolchain] channel=$V1 → $got (overrides default $V2)" \
  || { echo "  ❌ project pin → got '$got', wanted 'v$V1'"; fail=1; }

# +ver beats the project pin
got="$(cd "$PROJ" && ver "$PX" +"$V2" --version)"
[ "$got" = "v$V2" ] && echo "  ✅ +$V2 overrides the project pin → $got" \
  || { echo "  ❌ +ver override → got '$got', wanted 'v$V2'"; fail=1; }

# MEZ_VERSION env beats the project pin too
got="$(cd "$PROJ" && MEZZE_HOME="$HOME_DIR" MEZ_VERSION="$V2" "$PX" --version 2>&1 | grep -oE 'v[0-9.]+' | head -1)"
[ "$got" = "v$V2" ] && echo "  ✅ MEZ_VERSION=$V2 overrides the project pin → $got" \
  || { echo "  ❌ MEZ_VERSION → got '$got', wanted 'v$V2'"; fail=1; }

# remove a version → gone from the list, and a clear message if still requested
MEZZE_HOME="$HOME_DIR" "$PX" toolchain remove "$V1" >/dev/null 2>&1
if MEZZE_HOME="$HOME_DIR" "$PX" toolchain list 2>&1 | grep -qE "  $V1$| $V1 "; then
  echo "  ❌ removed $V1 still listed"; fail=1
else echo "  ✅ removed $V1 — no longer listed"; fi
out="$(cd "$PROJ" && MEZZE_HOME="$HOME_DIR" "$PX" --version 2>&1)"
if grep -qiE 'not installed|is not installed' <<<"$out"; then
  echo "  ✅ project still pinned to removed $V1 → clear 'not installed' error"
else echo "  ⚠️  removed-but-pinned gave: $(head -1 <<<"$out")"; fi

echo
[ "$fail" = 0 ] && echo "✅ dist-e2e: multi-version install + all selection mechanisms work" \
  || echo "❌ dist-e2e: FAILURES above"
exit "$fail"
