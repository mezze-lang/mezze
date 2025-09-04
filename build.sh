#!/bin/bash

# Simple build script for Funk language project
# This can be used while devenv environment is being set up

echo "🚀 Building Funk Language Project"

# Check if we're in devenv shell
if [ -n "$DEVENV_PROFILE" ]; then
    echo "✅ Using devenv environment"
    GRADLE_CMD="gradle"
else
    echo "⚠️  Not in devenv shell - using system gradle if available"
    if command -v gradle &> /dev/null; then
        GRADLE_CMD="gradle"
    elif [ -f "./gradlew" ]; then
        GRADLE_CMD="./gradlew"
    else
        echo "❌ No gradle found. Please install gradle or use devenv shell"
        exit 1
    fi
fi

echo "📦 Generating ANTLR parsers..."
$GRADLE_CMD generateGrammarSource

echo "🔨 Building project..."
$GRADLE_CMD build

echo "✅ Build complete!"
echo ""
echo "Available commands:"
echo "  $GRADLE_CMD :cli:run --args='parse examples/hello.funk'"
