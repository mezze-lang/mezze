#!/bin/bash
set -e

echo "=== Mezze Docker Portability Test ==="
echo ""
echo "This builds the native binary INSIDE Docker (Linux) and runs tests."
echo "Note: This takes a while on first run due to native-image compilation."
echo ""

# Build Docker image (this includes building native binary inside container)
echo "Step 1: Building Docker image (includes native compilation)..."
echo "This may take 5-10 minutes on first run..."
docker build -f Dockerfile.test -t mezze-test:latest .
echo ""

# Run tests
echo "=== Test 1: mez --version ==="
docker run --rm mezze-test:latest mez --version
echo ""

echo "=== Test 2: mez check (type-check project) ==="
docker run --rm mezze-test:latest mez check
echo ""

echo "=== Test 3: mez dev (run project) ==="
docker run --rm mezze-test:latest mez dev
echo ""

echo "=== Test 4: mez build (compile to JAR) ==="
docker run --rm mezze-test:latest bash -c "mez build && ls -lh *.jar"
echo ""

echo "=== Test 5: Execute compiled JAR ==="
docker run --rm mezze-test:latest bash -c "mez build > /dev/null 2>&1 && java -jar build_test.jar"
echo ""

echo "=== All Docker portability tests passed! ==="
