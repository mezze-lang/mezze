#!/usr/bin/env bash
#
# End-to-end test of GraalVM detection + graceful degradation (GraalvmResolver /
# `mez doctor`). Uses the REAL native `mez` binary (it runs `doctor` with no JDK
# present — that's the point) plus STUB `java` executables (tiny scripts that
# emit a chosen `java -version` banner) so we can drive every classification:
# matching GraalVM, other-line GraalVM, plain JDK, too-old, and none — without
# needing five real JDKs installed. Fully non-destructive: temp HOME/PATH only.
#
# Needs the native CLI binary (`./gradlew :cli:nativeCompile`). Run:
#     bash scripts/graalvm-e2e.sh                 # uses cli/build/native/nativeCompile/mez
#     bash scripts/graalvm-e2e.sh /path/to/mez    # or an explicit binary
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEZ_NATIVE="${1:-$REPO/cli/build/native/nativeCompile/mez}"
if [ ! -x "$MEZ_NATIVE" ]; then
  echo "❌ native mez not found at $MEZ_NATIVE"
  echo "   build it with: ./gradlew :cli:nativeCompile   (or pass a path)"
  exit 2
fi

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# The Graal line mez wants — read from the binary itself so this test tracks the
# build's pin instead of hardcoding it.
doctor_out="$("$MEZ_NATIVE" doctor 2>/dev/null || true)"
WANT_LINE="$(sed -n 's/.*wants GraalVM line \([0-9.]*\).*/\1/p' <<<"$doctor_out" | sed -n '1p')"
[ -n "$WANT_LINE" ] || WANT_LINE="25.0"

# Build a fake JDK home whose bin/java prints a chosen `java -version` banner.
# $1 = home name, $2 = the full -version banner text (may be multi-line).
mkjdk() {
  local home="$WORK/$1"; mkdir -p "$home/bin"
  { printf '#!/bin/sh\n'
    printf 'cat >&2 <<EOF\n%s\nEOF\n' "$2"
  } > "$home/bin/java"
  chmod +x "$home/bin/java"
  echo "$home"
}
# A GraalVM home that ALSO has native-image (matching line → full capability).
mkjdk_graal_native() {
  local home; home="$(mkjdk "$1" "$2")"
  printf '#!/bin/sh\necho native-image stub\n' > "$home/bin/native-image"
  chmod +x "$home/bin/native-image"
  echo "$home"
}

GRAAL_MATCH="$(mkjdk_graal_native graal_match \
  "openjdk version \"${WANT_LINE}.3\" 2025-01-01
OpenJDK Runtime Environment GraalVM CE (build ${WANT_LINE}.3)
OpenJDK 64-Bit Server VM GraalVM CE (build ${WANT_LINE}.3, mixed mode)")"

GRAAL_OTHER="$(mkjdk graal_other \
  "openjdk version \"24.1.0\" 2024-01-01
OpenJDK Runtime Environment GraalVM CE (build 24.1.0)
OpenJDK 64-Bit Server VM GraalVM CE (build 24.1.0, mixed mode)")"

PLAIN_JDK="$(mkjdk plain \
  "openjdk version \"21.0.2\" 2024-01-16
OpenJDK Runtime Environment (build 21.0.2)
OpenJDK 64-Bit Server VM (build 21.0.2, mixed mode)")"

OLD_JDK="$(mkjdk old \
  "openjdk version \"17.0.9\" 2023-10-17
OpenJDK Runtime Environment (build 17.0.9)")"

fail=0
# Run `mez doctor` with ONLY the given GraalVM home visible (empty PATH so the
# host JDK never leaks in), and assert a substring appears in the output.
expect() { # <name> <graalvm-home|""> <wanted-substring>
  local name="$1" home="$2" want="$3"
  local out
  if [ -n "$home" ]; then
    out="$(env -i HOME="$WORK" PATH="/usr/bin:/bin" MEZZE_HOME="$WORK/.mezze" \
           MEZZE_GRAALVM_HOME="$home" "$MEZ_NATIVE" doctor 2>&1)"
  else
    out="$(env -i HOME="$WORK" PATH="/usr/bin:/bin" MEZZE_HOME="$WORK/.mezze" \
           "$MEZ_NATIVE" doctor 2>&1)"
  fi
  if grep -qF -- "$want" <<<"$out"; then echo "  ✅ $name"
  else echo "  ❌ $name — wanted '$want', got:"; echo "$out" | sed 's/^/       /'; fail=1; fi
}

echo "GraalVM detection + degrade (native mez → stub JDKs, line $WANT_LINE):"
expect "matching GraalVM → JIT enabled"        "$GRAAL_MATCH"  "JIT enabled"
expect "matching GraalVM → native yes"          "$GRAAL_MATCH"  "native-image (--native):  ✓ yes"
expect "other-line GraalVM → line warning"      "$GRAAL_OTHER"  "mez wants $WANT_LINE"
expect "plain JDK → interpreter only"           "$PLAIN_JDK"    "interpreter only"
expect "plain JDK → native no"                   "$PLAIN_JDK"    "native-image (--native):  ✗ no"
expect "too-old JDK → too old"                   "$OLD_JDK"      "too old"
expect "no JDK → none"                           ""             "no JDK found"
expect "degraded doctor advises MEZZE_GRAALVM_HOME" "$PLAIN_JDK" "MEZZE_GRAALVM_HOME"

# --require-jit / --require-native should hard-fail on a plain JDK. Run inside a
# real project so `build` reaches the resolver gate (not the no-manifest error).
echo "CI strictness flags:"
PROJ="$WORK/proj"; mkdir -p "$PROJ/src"
cat > "$PROJ/Mezze.toml" <<EOF
[package]
name = "proj"
version = "0.1.0"

[[app]]
name = "proj"
path = "src/app.mz"
EOF
printf 'let main = {} -> 0\n' > "$PROJ/src/app.mz"
req() { # <name> <flag> <graalvm-home>
  local name="$1" flag="$2" home="$3" out
  out="$(cd "$PROJ" && env -i HOME="$WORK" PATH="/usr/bin:/bin" MEZZE_HOME="$WORK/.mezze" \
         MEZZE_GRAALVM_HOME="$home" "$MEZ_NATIVE" build "$flag" 2>&1)"
  # Assert the requirement gate rejects first (message names the flag).
  if grep -qF -- "$flag" <<<"$out"; then echo "  ✅ $name"
  else echo "  ❌ $name — '$flag' gate did not fire, got:"; echo "$out" | sed 's/^/       /'; fail=1; fi
}
req "build --require-jit rejects plain JDK"    "--require-jit"    "$PLAIN_JDK"
req "build --require-native rejects plain JDK" "--require-native" "$PLAIN_JDK"

if [ "$fail" -eq 0 ]; then echo "graalvm-e2e: ALL PASSED"; else echo "graalvm-e2e: FAILURES"; fi
exit "$fail"
