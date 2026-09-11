#!/usr/bin/env bash
#
# Install a `mez` toolchain from a release tarball (local path or https URL) into
# the versioned layout, and wire up the PATH proxy.
#
#     scripts/install-mez.sh <tarball-path-or-url> [--default]
#     curl -fsSL <url>/install-mez.sh | bash -s -- <url>/mez-0.1.3-darwin-arm64.tar.gz --default
#
# Lays down:
#   $MEZZE_HOME/toolchains/<version>/{bin/mez,lib,stdlib}   the toolchain
#   $MEZZE_HOME/bin/mez                                     the PATH proxy
#   $MEZZE_HOME/settings.toml  default = "<version>"        if none set, or --default
#
# Honors MEZZE_HOME (default ~/.mezze). Several versions coexist; `mez toolchain`
# and the proxy pick which one a project uses.
set -euo pipefail

SRC="${1:?usage: install-mez.sh <tarball path|url> [--default]}"
MAKE_DEFAULT=0; [ "${2:-}" = "--default" ] && MAKE_DEFAULT=1
MEZ_HOME="${MEZZE_HOME:-$HOME/.mezze}"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
TB="$TMP/mez.tar.gz"
case "$SRC" in
  http://*|https://*) echo "▸ Downloading $SRC …"; curl -fSL "$SRC" -o "$TB" ;;
  *) [ -f "$SRC" ] || { echo "❌ no such tarball: $SRC"; exit 1; }; cp "$SRC" "$TB" ;;
esac

# Optional integrity check against a sibling .sha256 (path installs only).
if [ -f "$SRC.sha256" ]; then
  want="$(awk '{print $1}' "$SRC.sha256")"
  got="$(shasum -a 256 "$TB" | awk '{print $1}')"
  [ "$want" = "$got" ] || { echo "❌ checksum mismatch: want $want got $got"; exit 1; }
fi

# The single top-level dir in the archive is the version. (sed prints just the
# first entry's top dir but still consumes the whole listing — `| head` would
# SIGPIPE tar and trip `set -o pipefail`.)
VERSION="$(tar -tzf "$TB" | sed -n '1s|/.*||p')"
[ -n "$VERSION" ] || { echo "❌ could not read version from tarball"; exit 1; }

echo "▸ Installing toolchain $VERSION into $MEZ_HOME/toolchains …"
mkdir -p "$MEZ_HOME/toolchains"
rm -rf "${MEZ_HOME:?}/toolchains/$VERSION"
tar -C "$MEZ_HOME/toolchains" -xzf "$TB"

# Proxy = a copy of the toolchain binary at $MEZ_HOME/bin. Its PATH, not its
# version, is what makes it the proxy (ProxyDispatch), so any copy works.
BIN="$MEZ_HOME/toolchains/$VERSION/bin/mez"
[ -f "$BIN" ] || BIN="$MEZ_HOME/toolchains/$VERSION/bin/mez.exe"
[ -f "$BIN" ] || { echo "❌ toolchain binary missing in archive"; exit 1; }
mkdir -p "$MEZ_HOME/bin"
cp "$BIN" "$MEZ_HOME/bin/$(basename "$BIN")"; chmod +x "$MEZ_HOME/bin/$(basename "$BIN")"

# Seed the global default if unset (or forced with --default).
SETTINGS="$MEZ_HOME/settings.toml"
if [ "$MAKE_DEFAULT" = 1 ] || ! grep -q '^[[:space:]]*default' "$SETTINGS" 2>/dev/null; then
  { grep -v '^[[:space:]]*default' "$SETTINGS" 2>/dev/null || true; echo "default = \"$VERSION\""; } > "$SETTINGS.tmp"
  mv "$SETTINGS.tmp" "$SETTINGS"
fi

echo
echo "✅ mez $VERSION installed."
echo "   Add the proxy to your PATH (once):"
echo "     export PATH=\"$MEZ_HOME/bin:\$PATH\""
echo "   Then:  mez toolchain list"
