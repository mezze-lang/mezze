#!/usr/bin/env bash
#
# Build + package a versioned `mez` toolchain into a distributable tarball.
#
#     scripts/release.sh 0.1.3
#
# Produces  dist/mez-<version>-<os>-<arch>.tar.gz  (+ .sha256), containing the
# `<version>/` toolchain tree (bin/mez, lib/, stdlib/) exactly as it lives under
# ~/.mezze/toolchains/. Install it anywhere with scripts/install-mez.sh.
#
# No CI / no hosting: this is the local, reproducible packaging step. Host the
# tarball wherever you like; the installer takes a path or an https URL.
set -euo pipefail

VERSION="${1:?usage: release.sh <version>   (e.g. 0.1.3)}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

echo "▸ Building toolchain $VERSION (this runs a full native build)…"
( cd "$REPO" && ./gradlew -Pversion="$VERSION" :cli:installNative )

SRC="$HOME/.mezze/toolchains/$VERSION"
[ -d "$SRC" ] || { echo "❌ install produced no $SRC"; exit 1; }

DIST="$REPO/dist"; mkdir -p "$DIST"
TARBALL="$DIST/mez-$VERSION-$OS-$ARCH.tar.gz"

# Package the toolchain tree with `<version>/` as the single top-level dir, so the
# installer can read the version straight from the archive.
tar -C "$HOME/.mezze/toolchains" -czf "$TARBALL" "$VERSION"

SHA="$(shasum -a 256 "$TARBALL" | awk '{print $1}')"
echo "$SHA  $(basename "$TARBALL")" > "$TARBALL.sha256"

echo
echo "✅ $TARBALL"
echo "   sha256: $SHA"
echo "   install:  scripts/install-mez.sh $TARBALL --default"
echo "   or host it and:  curl -fsSL <url>/install-mez.sh | bash -s -- <url>/$(basename "$TARBALL")"
