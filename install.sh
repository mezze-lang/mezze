#!/bin/sh
# The Mezze toolchain installer.
#
#   curl -fsSL https://github.com/mezze-lang/mezze/releases/latest/download/install.sh | sh
#
# Detects your OS + CPU, downloads the matching GitHub release, and unpacks the
# toolchain into ~/.mezze. `mez check`/`fmt`/`init` work immediately (no JVM);
# `mez dev`/`build` light up once a GraalVM is installed — mez detects it and
# prompts. (Windows: use install.ps1 instead.)
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
RELEASES="https://github.com/$REPO/releases"
ISSUES="https://github.com/$REPO/issues"

# ── styling (only when stdout is a terminal, TERM is capable, NO_COLOR unset) ──
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
  B=$(printf '\033[1m'); D=$(printf '\033[2m'); R=$(printf '\033[0m')
  RED=$(printf '\033[31m'); GRN=$(printf '\033[32m'); YLW=$(printf '\033[33m')
  CYN=$(printf '\033[36m'); MAG=$(printf '\033[35m')
  PROGRESS='--progress-bar'
else
  B=; D=; R=; RED=; GRN=; YLW=; CYN=; MAG=; PROGRESS='-sS'
fi

step() { printf '  %s▸%s %s\n' "$CYN" "$R" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GRN" "$R" "$*"; }
# die <message> [hint]: explain what failed AND what to do next.
die()  {
  printf '\n  %s✗ %s%s\n' "$RED" "$1" "$R" >&2
  [ $# -gt 1 ] && [ -n "$2" ] && printf '    %s%s%s\n' "$D" "$2" "$R" >&2
  printf '\n' >&2
  DIED=1
  exit 1
}
have() { command -v "$1" >/dev/null 2>&1; }

# Catch-all: if we exit non-zero for a reason die() did NOT already explain
# (an unexpected command failure under `set -e`), leave a clear pointer.
DIED=0
on_exit() {
  code=$?
  [ -n "${tmp:-}" ] && rm -rf "$tmp" 2>/dev/null || true
  if [ "$code" -ne 0 ] && [ "$DIED" -eq 0 ]; then
    printf '\n  %s✗ install failed unexpectedly (exit %s)%s\n' "$RED" "$code" "$R" >&2
    printf '    %sPlease report the output above at %s%s\n\n' "$D" "$ISSUES" "$R" >&2
  fi
}
trap on_exit EXIT INT TERM

printf '\n  %s%smez%s %s· the Mezze toolchain installer%s\n' "$B" "$MAG" "$R" "$D" "$R"
printf '  %s──────────────────────────────────────%s\n\n' "$D" "$R"

have curl || die "curl is required but not found" \
  "Install it with your package manager (e.g. 'apt install curl' or 'brew install curl'), then re-run."
have tar  || die "tar is required but not found" \
  "Install it with your package manager (e.g. 'apt install tar'), then re-run."

# ── detect platform ───────────────────────────────────────────────────────────
step "Detecting your platform"
os=$(uname -s)
case "$os" in
  Linux)  os=linux ;;
  Darwin) os=darwin ;;
  *) die "unsupported OS '$os'" \
         "mez ships for linux and macOS. On Windows use install.ps1 (PowerShell). Or build from source: https://github.com/$REPO" ;;
esac
arch=$(uname -m)
case "$arch" in
  x86_64|amd64)  arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) die "unsupported CPU '$arch'" \
         "mez ships for x86_64 and arm64. Build from source: https://github.com/$REPO" ;;
esac
platform="$os-$arch"
ok "Platform  ${B}${platform}${R}"

# A token (for a private repo) is optional; add it to API + download requests.
set --
[ -n "${GITHUB_TOKEN:-}" ] && set -- -H "Authorization: Bearer ${GITHUB_TOKEN}"

# ── resolve version (from GitHub Releases) ─────────────────────────────────────
step "Resolving the release ${D}(github.com/$REPO)${R}"
ver="${MEZ_VERSION:-}"
if [ -z "$ver" ]; then
  tag=$(curl -fsSL "$@" "https://api.github.com/repos/$REPO/releases/latest" \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1) || true
  [ -n "$tag" ] || die "couldn't resolve the latest release of ${B}$REPO${R}" \
    "Is the repo public? For a private repo set GITHUB_TOKEN. Check your network. Releases: $RELEASES"
  ver="${tag#v}"
else
  tag="v$ver"
fi
ok "Version   ${B}${ver}${R}"

asset="mez-$ver-$platform.tar.gz"
url="$RELEASES/download/$tag/$asset"

# ── download ───────────────────────────────────────────────────────────────────
tmp=$(mktemp -d)
step "Downloading ${D}${asset}${R}"
curl -fL "$@" $PROGRESS -o "$tmp/$asset" "$url" \
  || die "download failed for ${B}$platform${R} ${tag}" \
         "No build for $platform in $tag? See $RELEASES, or pin a working one with MEZ_VERSION=<x.y.z>. ($url)"
ok "Downloaded  ${D}$(du -h "$tmp/$asset" | cut -f1 | tr -d ' ')${R}"

# ── install ────────────────────────────────────────────────────────────────────
step "Installing into ${D}${DEST}${R}"
mkdir -p "$DEST"
tar -xzf "$tmp/$asset" -C "$DEST" \
  || die "could not unpack the archive into $DEST" \
         "The download may be corrupt or $DEST may not be writable; re-run, or unpack $RELEASES manually."
[ -x "$DEST/bin/mez" ] || die "install looks incomplete — $DEST/bin/mez is missing" \
  "The archive may be corrupt; re-run the installer, or download manually from $RELEASES"
installed=$("$DEST/bin/mez" --version 2>/dev/null | head -n1 || echo "mez $ver")
ok "Installed  ${B}${installed}${R}"

# ── PATH guidance (shell-aware) ──────────────────────────────────────────────────
bindir="$DEST/bin"
printf '\n'
case ":${PATH}:" in
  *":$bindir:"*)
    printf '  %s%s✓ mez is ready.%s  Run %smez --version%s to start.\n\n' "$B" "$GRN" "$R" "$B" "$R"
    ;;
  *)
    # Pick the right rc line for the user's shell; everything POSIX falls back to
    # ~/.profile, which login shells read.
    shname=$(basename "${SHELL:-sh}")
    case "$shname" in
      zsh)  rc="$HOME/.zshrc";                   line="export PATH=\"$bindir:\$PATH\"" ;;
      bash) rc="$HOME/.bashrc";                  line="export PATH=\"$bindir:\$PATH\"" ;;
      fish) rc="$HOME/.config/fish/config.fish"; line="fish_add_path $bindir" ;;
      ksh)  rc="$HOME/.kshrc";                   line="export PATH=\"$bindir:\$PATH\"" ;;
      *)    rc="$HOME/.profile";                 line="export PATH=\"$bindir:\$PATH\"" ;;
    esac
    printf '  %s%s✓ Done.%s One step left — put mez on your PATH:\n\n' "$B" "$GRN" "$R"
    printf '      %s%s%s\n\n' "$B" "$line" "$R"
    printf '  Add that to %s%s%s (your %s%s%s shell), then restart your shell or run the line now.\n' \
      "$B" "$rc" "$R" "$B" "$shname" "$R"
    printf '  %sThen:%s %smez --version%s\n\n' "$D" "$R" "$B" "$R"
    ;;
esac

printf '  %sDocs & getting started: https://github.com/%s%s\n\n' "$D" "$REPO" "$R"
