#!/usr/bin/env bash
#
# Precomputes hover types for the landing page samples.
#
# The samples never change at runtime, so the types are extracted once here
# rather than shipping the 55MB runtime to the landing page. Same code path as
# the tour's hover (TypedAstLookup + prettyPrintType), just on the JVM.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBSITE="$(dirname "$HERE")"
REPO="$(cd "$WEBSITE/.." && pwd)"
WORK="$HERE/.hover"
OUT="$WEBSITE/src/data/hovers.json"

say() { printf '\033[1m▸ %s\033[0m\n' "$*"; }

say "Resolving classpath"
(cd "$REPO" && ./gradlew --quiet :cli:jar :typechecker:jar :parser:jar :ast:jar)
# A DIFFERENT file from build.sh-s .classpath: that one lists the staged,
# version-matched jars in .jars/, this one the raw Gradle runtime classpath.
# Sharing the name meant whichever script ran second clobbered the other.
(cd "$REPO" && ./gradlew --quiet :cli:printRuntimeClasspath) > "$HERE/.classpath-hovers"
CP="$(cat "$HERE/.classpath-hovers")"

say "Compiling the extractor"
mkdir -p "$WORK/classes"
# Wipe first: a renamed tab would otherwise leave its old sample behind and
# ship hovers for code that is no longer on the page.
rm -rf "$WORK/samples" && mkdir -p "$WORK/samples"
javac -nowarn -cp "$CP" -d "$WORK/classes" "$HERE/tools/HoverExtractor.java"

# The samples live in content.ts; write each tab out as a .mz file first.
say "Extracting samples"
node "$HERE/extract-samples.mjs" "$WEBSITE/src/data/content.ts" "$WORK/samples"

say "Typechecking and extracting hovers"
java -cp "$CP:$WORK/classes" HoverExtractor "$WORK/samples"/*.mz > "$OUT"

say "Wrote $(wc -c < "$OUT" | tr -d ' ') bytes to src/data/hovers.json"

# Record which samples this was built from, so a later edit is caught.
node "$WEBSITE/tools/hover-stamp.mjs" --write
