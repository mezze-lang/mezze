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

# The GraalVM jars staged below must match this image's version — see the
# staging step. 25i1 is not an alternative: its truffle-svm macro is
# inconsistent with its own Truffle and cannot build this at all.
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
MODULES="ast parser typechecker stdlib store interpreter datastructures formatter"

say "Building JVM artifacts"
(cd "$REPO" && ./gradlew --quiet $(for m in $MODULES; do printf ':%s:jar ' "$m"; done))

# The classpath is staged by hand. Gradle's is wrong here for two reasons:
#
#   1. It pins GraalVM 25.0.3, but native-image in this image reports feature
#      version 25.3.4.1. Mixing them fails the polyglot version check, and
#      --macro:truffle-svm then substitutes against a Truffle that is not the
#      one on the classpath ("Could not find target method ...
#      getStackOverflowLimit"). The GraalVM jars must MATCH the image.
#   2. The shadow jar is no use either — it flattens polyglot's module
#      descriptor.
#
# So: our own module jars, the Kotlin runtime they need, and GraalVM at the
# image's own version fetched from Maven Central.
GRAAL_VERSION="25.3.4.1"
say "Staging the classpath"
CP_FILE="$HERE/.classpath"
STAGE="$HERE/.jars"
GRAAL_CACHE="$HERE/.graalvm-$GRAAL_VERSION"
rm -rf "$STAGE" && mkdir -p "$STAGE" "$GRAAL_CACHE"

for m in $MODULES; do
  jar=$(ls -t "$REPO/$m"/build/libs/*.jar 2>/dev/null | grep -v sources | head -1)
  [[ -n "$jar" ]] || { echo "no jar for :$m — did the build fail?" >&2; exit 1; }
  cp "$jar" "$STAGE/"
done

# GraalVM at the image's version. Cached between runs; ~22MB.
graal_fetch() {  # group artifact
  local jar="$2-$GRAAL_VERSION.jar"
  [[ -f "$GRAAL_CACHE/$jar" ]] || {
    curl -sfL -o "$GRAAL_CACHE/$jar" \
      "https://repo1.maven.org/maven2/org/graalvm/$1/$2/$GRAAL_VERSION/$jar" \
      || { echo "could not fetch $jar" >&2; exit 1; }
  }
  cp "$GRAAL_CACHE/$jar" "$STAGE/"
}
graal_fetch sdk graal-sdk
graal_fetch sdk collections
graal_fetch sdk jniutils
graal_fetch sdk nativeimage
graal_fetch sdk word
graal_fetch polyglot polyglot
graal_fetch truffle truffle-api
graal_fetch truffle truffle-dsl-processor

# Kotlin runtime and serialization, straight from the Gradle cache.
(cd "$REPO" && ./gradlew --quiet :cli:printRuntimeClasspath) \
  | grep -E '^/.*:/' | tail -1 \
  | tr ':' '\n' \
  | grep -E '/(kotlin-stdlib|kotlinx-serialization)[^/]*[.]jar$' \
  | while read -r j; do [[ -f "$j" ]] && cp -n "$j" "$STAGE/" 2>/dev/null || true; done

printf '%s\n' "$STAGE"/*.jar | paste -sd: - > "$CP_FILE"
[[ -s "$CP_FILE" ]] || { echo "staging produced an empty classpath" >&2; exit 1; }
IMAGE_CP_FILE="$CP_FILE"

# --- 2. native-image, inside the Oracle container --------------------------
#
# native-image shells out to Binaryen's wasm-as, which the Oracle image does
# not ship. Fetch the Linux build matching the container's architecture.
BINARYEN_VERSION=131
BINARYEN="$HERE/.binaryen-$BINARYEN_VERSION"
if [[ ! -x "$BINARYEN/bin/wasm-as" ]]; then
  say "Fetching Binaryen $BINARYEN_VERSION"
  case "$(uname -m)" in
    arm64|aarch64) BIN_ARCH=aarch64 ;;
    *)             BIN_ARCH=x86_64  ;;
  esac
  mkdir -p "$BINARYEN"
  curl -sfL "https://github.com/WebAssembly/binaryen/releases/download/version_$BINARYEN_VERSION/binaryen-version_$BINARYEN_VERSION-$BIN_ARCH-linux.tar.gz" \
    | tar xz -C "$BINARYEN" --strip-components=1 \
    || { echo "could not fetch Binaryen" >&2; exit 1; }
fi

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
      -J-Dpolyglotimpl.DisableVersionChecks=true \
      -H:+EnableFallbackCompilation \
      -H:IncludeResources='.*\.mz\$|.*stdlib-snapshot\.cbor\$' \
      --initialize-at-build-time=com.mezze,kotlin,kotlinx \
      -cp \"\$(cat '$IMAGE_CP_FILE'):/tmp/classes\" \
      -H:WasmAsPath=$BINARYEN/bin/wasm-as \
      MezzeWeb -o /out/mezze
  "

# native-image also emits a .wat text dump — 545MB, and useless to the site.
rm -f "$OUT/mezze.js.wat"

say "Done — $(du -h "$OUT/mezze.js.wasm" | cut -f1) at public/wasm/"
