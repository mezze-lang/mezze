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
  # No `| sort -rn | head -1` here, for the reason this file keeps rediscovering:
  # head exits after one line, the closed pipe SIGPIPEs sort, and under
  # `set -o pipefail` the command substitution yields 141 -- which `set -e`
  # turns into a silent exit before the first `say`, so the build appears to do
  # nothing at all. It stayed hidden until public/wasm existed, since this
  # branch only runs once there is an output to compare against. awk reads its
  # whole input and needs no sort.
  newest_src=$(find "$HERE/src" "$REPO/interpreter/src" "$REPO/typechecker/src" \
                 \( -name '*.java' -o -name '*.kt' \) 2>/dev/null |
               while read -r f; do mtime "$f"; done |
               awk '$1 > newest { newest = $1 } END { print newest + 0 }')
  built=$(mtime "$OUT/mezze.js.wasm")
  if [[ "${newest_src:-0}" -lt "$built" ]]; then
    say "public/wasm is up to date (use --force to rebuild)"
    exit 0
  fi
fi

# --- 1. JVM artifacts the image is built from ------------------------------
# :cli is here for one reason — its jar carries META-INF/mezze/stdlib-snapshot.cbor,
# which IncludeResources embeds. Without it the image builds fine and then
# fails at runtime with no stdlib.
MODULES="ast parser typechecker langservice stdlib store interpreter datastructures formatter cli"

say "Building JVM artifacts"
# -PslimSnapshot=true omits stdlib TypedModules from the embedded snapshot: ~22 MB
# the browser image bakes into its heap but never needs (a mono fast-path only).
# Keeps the WASM runtime loadable; JVM/native builds omit the flag and keep them.
(cd "$REPO" && ./gradlew --quiet -PslimSnapshot=true $(for m in $MODULES; do printf ':%s:jar ' "$m"; done))

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
  # Newest non-sources jar. Done with a glob + arithmetic mtime compare rather
  # than `ls -t | grep | head -1`: that pipeline SIGPIPEs `ls`/`grep` when
  # `head` closes early, and under `set -o pipefail` a 141 there aborts the
  # whole build (intermittently, by directory size).
  jar=""; jar_mtime=0
  for cand in "$REPO/$m"/build/libs/*.jar; do
    [[ -f "$cand" ]] || continue
    [[ "$cand" == *sources* ]] && continue
    cand_mtime=$(mtime "$cand")
    if [[ "$cand_mtime" -ge "$jar_mtime" ]]; then jar="$cand"; jar_mtime="$cand_mtime"; fi
  done
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

# Kotlin runtime, serialization, and the JSON backend, straight from the
# Gradle cache.
#
# dsl-json backs `std::codec::json`. Without it the image builds fine and then
# every parse dies at runtime with `NoClassDefFoundError: DslJson` — the same
# class of silent breakage the snapshot check below exists to prevent, which is
# why the codec was unusable in the browser while working on the JVM.
#
# Capture gradle's output into a variable first: piping it straight into
# `tail -1` lets tail close the pipe while gradle is still writing, which
# SIGPIPEs gradle and — under `set -o pipefail` — aborts the whole script.
runtime_cp="$(cd "$REPO" && ./gradlew --quiet :cli:printRuntimeClasspath)"
printf '%s\n' "$runtime_cp" \
  | grep -E '^/.*:/' | tail -1 \
  | tr ':' '\n' \
  | grep -E '/(kotlin-stdlib|kotlinx-serialization|dsl-json)[^/]*[.]jar$' \
  | while read -r j; do [[ -f "$j" ]] && cp -n "$j" "$STAGE/" 2>/dev/null || true; done

# Fail loudly here rather than shipping an image with no stdlib: the build
# succeeds either way, and the breakage only shows up in the browser.
#
# Read each listing into a variable before matching it. Piping `unzip -l`
# straight into `grep -q` lets grep close the pipe on its first match, which
# SIGPIPEs unzip; under `set -o pipefail` the pipeline then reports 141 and the
# `if` reads it as "not found" — so a jar that DOES carry the snapshot is
# rejected and the build dies claiming the classpath has none. Same hazard as
# the `ls | grep | head` and `gradle | tail -1` cases above, and it only
# started firing here once the cli jar grew its 43MB native-classpath.zip.
snapshot_jar=""
for j in "$STAGE"/*.jar; do
  listing="$(unzip -l "$j" 2>/dev/null || true)"
  if grep -q 'stdlib-snapshot\.cbor' <<<"$listing"; then
    snapshot_jar="$j"
    break
  fi
done
[[ -n "$snapshot_jar" ]] ||
  { echo "no stdlib-snapshot.cbor on the classpath — the image would have no stdlib" >&2; exit 1; }

# A library staged at two different versions is a silent correctness bug: the
# stdlib snapshot is CBOR-encoded by whichever version the gradle build resolved,
# but native-image builds its classpath alphabetically (the `paste` below), so a
# lower version sorting first DECODES what the higher one ENCODED. The two CBOR
# formats need not agree — kotlinx-serialization 1.6.2 reading 1.9.0's bytes threw
# "Field 'hex' is required for Hash, but it was missing" and, with no source
# fallback in the browser, broke every `use std::` program in the playground.
# Catch it here rather than ship another broken runtime.
dupes=$(for j in "$STAGE"/*.jar; do
          b=$(basename "$j")
          printf '%s\t%s\n' "$(printf '%s' "$b" | sed -E 's/-[0-9][0-9A-Za-z.]*\.jar$//')" "$b"
        done | sort -u | awk -F'\t' '{ n[$1]++; seen[$1]=seen[$1] " " $2 } END { for (k in n) if (n[k] > 1) print k ":" seen[k] }')
if [[ -n "$dupes" ]]; then
  echo "staged classpath carries a library at two versions — the snapshot would not round-trip:" >&2
  printf '  %s\n' "$dupes" >&2
  exit 1
fi

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
      -H:IncludeResources='.*\.mz\$' \
      --initialize-at-build-time=com.mezze,kotlin,kotlinx \
      --initialize-at-run-time=com.mezze.language.typechecker.ModuleParsePool \
      --initialize-at-run-time=com.mezze.language.typechecker.MezzeExecutors \
      -cp \"\$(cat '$IMAGE_CP_FILE'):/tmp/classes\" \
      -H:WasmAsPath=$BINARYEN/bin/wasm-as \
      MezzeWeb -o /out/mezze
  "

# native-image also emits a .wat text dump — 545MB, and useless to the site.
rm -f "$OUT/mezze.js.wat"

say "Done — $(du -h "$OUT/mezze.js.wasm" | cut -f1) at public/wasm/"
