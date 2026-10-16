#!/bin/sh
# The Mezze toolchain installer.
#
#   curl -fsSL https://github.com/mezze-lang/mezze/releases/latest/download/install.sh | sh
#
# Detects your OS + CPU, downloads the matching release, and unpacks the toolchain
# into ~/.mezze. `mez check`/`fmt`/`init` work immediately (no JVM); `mez dev`/
# `build` light up once a GraalVM is installed — mez detects it and prompts.
#
# Env knobs:
#   MEZ_VERSION   install a specific version (e.g. 0.1.0) instead of the latest
#   MEZZE_HOME    install location (default: ~/.mezze)
#   MEZ_REPO      owner/repo to fetch releases from (default: mezze-lang/mezze)
#   GITHUB_TOKEN  bearer token for a PRIVATE repo's API + asset downloads
#   NO_COLOR      disable colored output
set -eu

REPO="${MEZ_REPO:-mezze-lang/mezze}"
DEST="${MEZZE_HOME:-$HOME/.mezze}"

# ── styling (only when stdout is a terminal, TERM is capable, NO_COLOR unset) ──
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
  B=$(printf '\033[1m'); D=$(printf '\033[2m'); R=$(printf '\033[0m')
  RED=$(printf '\033[31m'); GRN=$(printf '\033[32m'); YLW=$(printf '\033[33m')
  CYN=$(printf '\033[36m'); MAG=$(printf '\033[35m')
  PROGRESS='--progress-bar'
else
  B=; D=; R=; RED=; GRN=; YLW=; CYN=; MAG=; PROGRESS='-sS'
fi

step() { printf '  %s▸%s %s\n'  "$CYN" "$R" "$*"; }
ok()   { printf '  %s✓%s %s\n'  "$GRN" "$R" "$*"; }
warn() { printf '  %s!%s %s\n'  "$YLW" "$R" "$*"; }
die()  { printf '\n  %s✗ %s%s\n\n' "$RED" "$*" "$R" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

printf '\n  %s%smez%s %s· the Mezze toolchain installer%s\n' "$B" "$MAG" "$R" "$D" "$R"
printf '  %s──────────────────────────────────────%s\n\n' "$D" "$R"

have curl || die "curl is required but not found"
have tar  || die "tar is required but not found"

# ── detect platform ───────────────────────────────────────────────────────────
step "Detecting your platform"
os=$(uname -s)
case "$os" in
  Linux)  os=linux ;;
  Darwin) os=darwin ;;
  *) die "unsupported OS '$os' — linux and macOS are supported (Windows is not yet)" ;;
esac
arch=$(uname -m)
case "$arch" in
  x86_64|amd64)  arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) die "unsupported CPU '$arch' — x86_64 and arm64 are supported" ;;
esac
platform="$os-$arch"
ok "Platform  ${B}${platform}${R}"

# A token (for a private repo) is optional; add it to API + download requests.
set --
[ -n "${GITHUB_TOKEN:-}" ] && set -- -H "Authorization: Bearer ${GITHUB_TOKEN}"

# ── resolve version ────────────────────────────────────────────────────────────
step "Resolving the release"
ver="${MEZ_VERSION:-}"
if [ -z "$ver" ]; then
  tag=$(curl -fsSL "$@" "https://api.github.com/repos/$REPO/releases/latest" \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  [ -n "$tag" ] || die "couldn't find the latest release of ${B}$REPO${R} — is it public, or is GITHUB_TOKEN set?"
  ver="${tag#v}"
else
  tag="v$ver"
fi
ok "Version   ${B}${ver}${R}  ${D}(from $REPO)${R}"

asset="mez-$ver-$platform.tar.gz"
url="https://github.com/$REPO/releases/download/$tag/$asset"

# ── download ───────────────────────────────────────────────────────────────────
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM
step "Downloading ${D}${asset}${R}"
curl -fL "$@" $PROGRESS -o "$tmp/$asset" "$url" \
  || die "download failed — no ${B}$platform${R} build in ${B}$tag${R}? ($url)"
ok "Downloaded  ${D}$(du -h "$tmp/$asset" | cut -f1 | tr -d ' ')${R}"

# ── install ────────────────────────────────────────────────────────────────────
step "Installing into ${D}${DEST}${R}"
mkdir -p "$DEST"
tar -xzf "$tmp/$asset" -C "$DEST"
[ -x "$DEST/bin/mez" ] || die "install looks incomplete — $DEST/bin/mez is missing"
installed=$("$DEST/bin/mez" --version 2>/dev/null | head -n1 || echo "mez $ver")
ok "Installed  ${B}${installed}${R}"

# ── PATH guidance ────────────────────────────────────────────────────────────────
bindir="$DEST/bin"
printf '\n'
case ":${PATH}:" in
  *":$bindir:"*)
    printf '  %s%s✓ mez is ready.%s  Run %smez --version%s to start.\n\n' "$B" "$GRN" "$R" "$B" "$R"
    ;;
  *)
    # Suggest the right rc file for the user's shell.
    shname=$(basename "${SHELL:-sh}")
    case "$shname" in
      zsh)  rc="$HOME/.zshrc";                  line="export PATH=\"$bindir:\$PATH\"" ;;
      bash) rc="$HOME/.bashrc";                 line="export PATH=\"$bindir:\$PATH\"" ;;
      fish) rc="$HOME/.config/fish/config.fish"; line="fish_add_path $bindir" ;;
      *)    rc="your shell profile";            line="export PATH=\"$bindir:\$PATH\"" ;;
    esac
    printf '  %s%s✓ Done.%s One step left — put mez on your PATH:\n\n' "$B" "$GRN" "$R"
    printf '      %s%s%s\n\n' "$B" "$line" "$R"
    printf '  Add that to %s%s%s, then restart your shell (or run the line now).\n' "$B" "$rc" "$R"
    printf '  %sThen:%s %smez --version%s\n\n' "$D" "$R" "$B" "$R"
    ;;
esac

printf '  %sDocs & getting started: https://github.com/%s%s\n\n' "$D" "$REPO" "$R"
