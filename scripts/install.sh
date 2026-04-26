#!/bin/bash
# Mezze Language Installer
#
# Usage:
#   Local install (from repo):  ./scripts/install.sh
#   Remote install (future):    curl -fsSL https://mezze.dev/install.sh | sh
#
# This script installs the Mezze CLI to ~/.mezze/bin and adds it to your PATH.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Installation directory
MEZZE_HOME="$HOME/.mezze"
MEZZE_BIN="$MEZZE_HOME/bin"

echo -e "${BLUE}"
echo "    __  ___                     "
echo "   /  |/  /__  ________  ___    "
echo "  / /|_/ / _ \\/_  /_  / / _ \\   "
echo " / /  / /  __/ / /_/ /_/  __/   "
echo "/_/  /_/\\___/ /___/___/\\___/    "
echo "                                "
echo -e "${NC}"
echo "Mezze Language Installer"
echo "========================"
echo ""

# Detect OS and architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Darwin)
        OS_NAME="macos"
        ;;
    Linux)
        OS_NAME="linux"
        ;;
    *)
        echo -e "${RED}Error: Unsupported operating system: $OS${NC}"
        exit 1
        ;;
esac

case "$ARCH" in
    x86_64|amd64)
        ARCH_NAME="x64"
        ;;
    arm64|aarch64)
        ARCH_NAME="aarch64"
        ;;
    *)
        echo -e "${RED}Error: Unsupported architecture: $ARCH${NC}"
        exit 1
        ;;
esac

echo -e "Detected: ${GREEN}$OS_NAME-$ARCH_NAME${NC}"
echo ""

# Check if we're running from the repo (local install)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

NATIVE_BINARY="$REPO_ROOT/cli/build/native/nativeCompile/mezze"

if [ -f "$NATIVE_BINARY" ]; then
    echo -e "${GREEN}Found native binary${NC}"
    INSTALL_MODE="local"
elif [ -f "$REPO_ROOT/gradlew" ]; then
    echo -e "${YELLOW}Native binary not found. Building from source...${NC}"
    echo ""
    echo "This may take a few minutes on first build."
    echo ""
    INSTALL_MODE="build"
    cd "$REPO_ROOT"
    ./gradlew :cli:nativeCompile

    if [ ! -f "$NATIVE_BINARY" ]; then
        echo -e "${RED}Error: Native compilation failed${NC}"
        echo ""
        echo "Try running manually:"
        echo "  ./gradlew :cli:nativeCompile"
        exit 1
    fi
else
    # Future: download from URL
    echo -e "${RED}Error: No local build found and remote install not yet supported.${NC}"
    echo ""
    echo "To install locally, run this script from the Mezze repository:"
    echo "  cd /path/to/mezze"
    echo "  ./scripts/install.sh"
    exit 1
fi

# Create installation directories
echo ""
echo -e "Installing to ${GREEN}$MEZZE_HOME${NC}..."
mkdir -p "$MEZZE_BIN"
mkdir -p "$MEZZE_HOME/lib"

# Copy the native binary (remove first to avoid OS caching issues with overwritten executables)
rm -f "$MEZZE_BIN/mez"
cp "$NATIVE_BINARY" "$MEZZE_BIN/mez"
chmod +x "$MEZZE_BIN/mez"

# Copy the runtime JAR (needed for mez dev JVM subprocess and mez build fat JAR)
SHADOW_JAR="$REPO_ROOT/cli/build/libs/mezze.jar"
if [ ! -f "$SHADOW_JAR" ]; then
    echo -e "${YELLOW}Building shadow JAR...${NC}"
    cd "$REPO_ROOT"
    ./gradlew :cli:shadowJar
fi
if [ -f "$SHADOW_JAR" ]; then
    cp "$SHADOW_JAR" "$MEZZE_HOME/lib/mezze-lang.jar"
    echo -e "${GREEN}Runtime JAR installed ($(du -h "$MEZZE_HOME/lib/mezze-lang.jar" | cut -f1))${NC}"
else
    echo -e "${YELLOW}Warning: mezze-lang.jar not found. mez dev and mez build will be limited.${NC}"
fi

# Copy pre-compiled stdlib.sqlite for fast startup (both native binary and JVM)
STDLIB_SQLITE="$REPO_ROOT/cli/build/generated/resources/stdlib.sqlite"
if [ -f "$STDLIB_SQLITE" ]; then
    mkdir -p "$MEZZE_HOME/cache"
    cp "$STDLIB_SQLITE" "$MEZZE_HOME/cache/stdlib.sqlite"
    echo -e "${GREEN}Pre-compiled stdlib installed ($(du -h "$MEZZE_HOME/cache/stdlib.sqlite" | cut -f1))${NC}"
fi

# Store the stdlib location for the binary to find
echo "$REPO_ROOT" > "$MEZZE_HOME/project-root"

echo -e "${GREEN}Mezze installed!${NC}"
echo ""

# Detect shell and config file
detect_shell_config() {
    case "$SHELL" in
        */zsh)
            echo "$HOME/.zshrc"
            ;;
        */bash)
            if [ -f "$HOME/.bash_profile" ]; then
                echo "$HOME/.bash_profile"
            else
                echo "$HOME/.bashrc"
            fi
            ;;
        */fish)
            echo "$HOME/.config/fish/config.fish"
            ;;
        *)
            echo "$HOME/.profile"
            ;;
    esac
}

SHELL_CONFIG="$(detect_shell_config)"

# Check if PATH is already configured
if grep -q '.mezze/bin' "$SHELL_CONFIG" 2>/dev/null; then
    echo -e "${GREEN}PATH already configured in $SHELL_CONFIG${NC}"
else
    echo -e "${YELLOW}Adding Mezze to PATH...${NC}"
    echo "" >> "$SHELL_CONFIG"
    echo "# Mezze Language" >> "$SHELL_CONFIG"
    echo 'export PATH="$HOME/.mezze/bin:$PATH"' >> "$SHELL_CONFIG"
    echo -e "${GREEN}Added to $SHELL_CONFIG${NC}"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "${GREEN}Installation complete!${NC}"
echo ""
echo "To start using Mezze, either:"
echo ""
echo "  1. Open a new terminal, or"
echo "  2. Run: source $SHELL_CONFIG"
echo ""
echo "Then verify the installation:"
echo ""
echo "  mez --version"
echo ""
echo "Get started:"
echo ""
echo "  mez init --app      # Create a new app"
echo "  mez dev             # Run your app"
echo "  mez --help          # See all commands"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
