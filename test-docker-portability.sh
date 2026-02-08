#!/bin/bash
set -e

echo "🔧 Building Funk CLI..."
./gradlew :cli:installDist

echo "🐳 Building Docker image..."
docker build -f Dockerfile.test -t funk-test:latest .

echo "📦 Testing CLI portability in Docker..."
echo ""
echo "=== Step 1: Check command works (package mode) ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/funk:/home/funkuser/funk-cli:ro" \
    funk-test:latest \
    funk check

echo ""
echo "=== Step 2: Dev mode (run directly) ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/funk:/home/funkuser/funk-cli:ro" \
    funk-test:latest \
    funk dev

echo ""
echo "=== Step 3: Build JAR ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/funk:/home/funkuser/funk-cli:ro" \
    funk-test:latest \
    bash -c "funk build && ls -lh *.jar"

echo ""
echo "=== Step 4: Execute JAR ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/funk:/home/funkuser/funk-cli:ro" \
    funk-test:latest \
    bash -c "funk build > /dev/null 2>&1 && java -jar build_test.jar"

echo ""
echo "✅ All Docker portability tests passed!"
