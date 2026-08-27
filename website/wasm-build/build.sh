#!/usr/bin/env bash
#
# Compiles the Mezze compiler to WebAssembly and installs it into public/wasm.
#
# GraalVM Web Image (--tool:svm-wasm) is Oracle-GraalVM-only and not in the CE
# distribution, so the build runs inside Oracle's published native-image image
# rather than against a local JDK.
#
# Takes roughly 20-40 minutes and needs ~16GB of RAM available to Docker.
#
#   ./build.sh          build if public/wasm is missing or stale
#   ./build.sh --force  build unconditionally
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBSITE="$(dirname "$HERE")"
REPO="$(cd "$WEBSITE/.." && pwd)"
OUT="$WEBSITE/public/wasm"

IMAGE="container-registry.oracle.com/graalvm/native-image:25i3"
FORCE="${1:-}"

say() { printf '\033[1m▸ %s\033[0m\n' "$*"; }

# --- skip when the output is already newer than every input ----------------
# GNU and BSD stat disagree on the mtime flag; both are in play on macOS.
mtime() { stat -c %Y "$1" 2>/dev/null || stat -f %m "$1"; }

if [[ "$FORCE" != "--force" && -f "$OUT/mezze.js.wasm" ]]; then
  newest_src=$(find "$HERE/src" "$REPO/interpreter/src" "$REPO/typechecker/src" \
                 \( -name '*.java' -o -name '*.kt' \) 2>/dev/null |
               while read -r f; do mtime "$f"; done | sort -rn | head -1)
  built=$(mtime "$OUT/mezze.js.wasm")
  if [[ "${newest_src:-0}" -lt "$built" ]]; then
    say "public/wasm is up to date (use --force to rebuild)"
    exit 0
  fi
fi

# --- 1. JVM artifacts the image is built from ------------------------------
say "Building JVM artifacts"
(cd "$REPO" && ./gradlew --quiet :cli:jar :interpreter:jar :typechecker:jar)

say "Resolving the runtime classpath"
CP_FILE="$HERE/.classpath"
(cd "$REPO" && ./gradlew --quiet :cli:printRuntimeClasspath) > "$CP_FILE"
[[ -s "$CP_FILE" ]] || { echo "empty classpath — is :cli:printRuntimeClasspath defined?" >&2; exit 1; }

# --- 2. native-image, inside the Oracle container --------------------------
# Everything the classpath names must be visible in the container, so the whole
# repo and the Gradle cache are mounted at their host paths.
say "Compiling to WebAssembly (this takes a while)"
mkdir -p "$OUT"
docker run --rm \
  -v "$REPO:$REPO" \
  -v "$HOME/.gradle:$HOME/.gradle" \
  -v "$OUT:/out" \
  -w "$HERE" \
  --entrypoint sh "$IMAGE" -c "
    set -e
    javac -nowarn -cp \"\$(cat '$CP_FILE')\" -d /tmp/classes $HERE/src/*.java

    native-image --tool:svm-wasm --macro:truffle-svm \
      -H:+UnlockExperimentalVMOptions \
      -H:+EnableFallbackCompilation \
      -H:IncludeResources='.*\.mz\$|.*stdlib-snapshot\.cbor\$' \
      --initialize-at-build-time=com.mezze,kotlin,kotlinx \
      -cp \"\$(cat '$CP_FILE'):/tmp/classes\" \
      MezzeWeb -o /out/mezze
  "

say "Done — $(du -h "$OUT/mezze.js.wasm" | cut -f1) at public/wasm/"
