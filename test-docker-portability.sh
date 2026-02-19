#!/bin/bash
set -e

echo "Building Mezze CLI..."
./gradlew :cli:installDist

echo "Building Docker image..."
docker build -f Dockerfile.test -t mezze-test:latest .

echo "Testing CLI portability in Docker..."
echo ""
echo "=== Step 1: Check command works (package mode) ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/mez:/home/mezzeuser/mezze-cli:ro" \
    mezze-test:latest \
    mez check

echo ""
echo "=== Step 2: Dev mode (run directly) ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/mez:/home/mezzeuser/mezze-cli:ro" \
    mezze-test:latest \
    mez dev

echo ""
echo "=== Step 3: Build JAR ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/mez:/home/mezzeuser/mezze-cli:ro" \
    mezze-test:latest \
    bash -c "mez build && ls -lh *.jar"

echo ""
echo "=== Step 4: Execute JAR ==="
docker run --rm \
    -v "$(pwd)/cli/build/install/mez:/home/mezzeuser/mezze-cli:ro" \
    mezze-test:latest \
    bash -c "mez build > /dev/null 2>&1 && java -jar build_test.jar"

echo ""
echo "All Docker portability tests passed!"
