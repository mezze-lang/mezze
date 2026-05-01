#!/bin/bash
# Mezze Language Installer
#
# Installs the `mez` CLI for end users.
#
#   ./scripts/install.sh
#
# Builds the native binary + runtime jar from this checkout via gradle,
# installs them into ~/.mezze/, and adds ~/.mezze/bin to your shell's PATH.
#
# All build + copy work is delegated to `gradle :cli:installNative` —
# see cli/build.gradle.kts for the actual logic. This script's only
# unique responsibility is the PATH wiring at the end.
#
# Layout after install:
#   ~/.mezze/bin/mez            # native binary (~80 MB Mach-O / ELF)
#   ~/.mezze/lib/mezze-lang.jar # JVM fallback used by `mez dev` and `mez build`

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

MEZZE_HOME="$HOME/.mezze"

echo -e "${BLUE}"
echo "    __  ___                     "
echo "   /  |/  /__  ________  ___    "
echo "  / /|_/ / _ \\/_  /_  / / _ \\   "
echo " / /  / /  __/ / /_/ /_/  __/   "
echo "/_/  /_/\\___/ /___/___/\\___/    "
echo -e "${NC}"
echo "Mezze Language Installer"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

if [ ! -f "$REPO_ROOT/gradlew" ]; then
    echo "Error: $REPO_ROOT does not look like the Mezze repo (no gradlew)."
    echo "Run this script from inside the Mezze checkout."
    exit 1
fi

echo "Building + installing native binary and runtime jar (~3 min first time)..."
echo ""
cd "$REPO_ROOT"
./gradlew :cli:installNative

echo ""
echo -e "${GREEN}Installed to $MEZZE_HOME${NC}"
ls -lh "$MEZZE_HOME/bin/mez" "$MEZZE_HOME/lib/mezze-lang.jar" 2>/dev/null | sed 's/^/  /'
echo ""

# ---- PATH wiring ----------------------------------------------------------
detect_shell_config() {
    case "$SHELL" in
        */zsh)  echo "$HOME/.zshrc" ;;
        */bash) [ -f "$HOME/.bash_profile" ] && echo "$HOME/.bash_profile" || echo "$HOME/.bashrc" ;;
        */fish) echo "$HOME/.config/fish/config.fish" ;;
        *)      echo "$HOME/.profile" ;;
    esac
}

SHELL_CONFIG="$(detect_shell_config)"

if grep -q '.mezze/bin' "$SHELL_CONFIG" 2>/dev/null; then
    echo -e "${GREEN}PATH already configured in $SHELL_CONFIG${NC}"
else
    echo "" >> "$SHELL_CONFIG"
    echo "# Mezze Language" >> "$SHELL_CONFIG"
    echo 'export PATH="$HOME/.mezze/bin:$PATH"' >> "$SHELL_CONFIG"
    echo -e "${GREEN}Added ~/.mezze/bin to PATH in $SHELL_CONFIG${NC}"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "${GREEN}Done. Open a new terminal (or source $SHELL_CONFIG), then:${NC}"
echo ""
echo "  mez --version"
echo "  mez init my_app --app"
echo "  cd my_app && mez dev"
echo ""
