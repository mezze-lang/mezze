#!/bin/bash

# Comprehensive test runner for Funk language
echo "🧪 Running Funk Language Test Suite"
echo "=================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Function to run a single test
run_test() {
    local test_file=$1
    local test_name=$(basename "$test_file" .funk)
    
    echo -e "${BLUE}Testing: $test_name${NC}"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    # Run type check
    if funk check "$test_file" > /dev/null 2>&1; then
        echo -e "  ✅ Type check: ${GREEN}PASSED${NC}"
        
        # Run execution
        if funk exec "$test_file" > /dev/null 2>&1; then
            echo -e "  ✅ Execution: ${GREEN}PASSED${NC}"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo -e "  ❌ Execution: ${RED}FAILED${NC}"
            echo -e "  ${YELLOW}Error output:${NC}"
            funk exec "$test_file" 2>&1 | sed 's/^/    /'
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo -e "  ❌ Type check: ${RED}FAILED${NC}"
        echo -e "  ${YELLOW}Error output:${NC}"
        funk check "$test_file" 2>&1 | sed 's/^/    /'
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo ""
}

# Function to run compiler tests
run_compiler_test() {
    local test_file=$1
    local test_name=$(basename "$test_file" .funk)
    
    echo -e "${BLUE}Compiler Test: $test_name${NC}"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    # Run compilation
    if funk compile --target js "$test_file" > /dev/null 2>&1; then
        echo -e "  ✅ JavaScript compilation: ${GREEN}PASSED${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "  ❌ JavaScript compilation: ${RED}FAILED${NC}"
        echo -e "  ${YELLOW}Error output:${NC}"
        funk compile --target js "$test_file" 2>&1 | sed 's/^/    /'
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    echo ""
}

# Check if funk binary exists
if ! command -v funk &> /dev/null; then
    echo -e "${RED}Error: funk binary not found. Please build it first with 'gradle nativeCompile'${NC}"
    exit 1
fi

echo -e "${YELLOW}Running interpreter tests...${NC}"
echo ""

# Run all test files
for test_file in tests/*.funk; do
    if [ -f "$test_file" ]; then
        run_test "$test_file"
    fi
done

echo -e "${YELLOW}Running compiler tests...${NC}"
echo ""

# Run compiler tests on a subset of files
for test_file in tests/basic_literals.funk tests/arithmetic_operations.funk tests/functions.funk; do
    if [ -f "$test_file" ]; then
        run_compiler_test "$test_file"
    fi
done

# Summary
echo "=================================="
echo -e "${BLUE}Test Summary:${NC}"
echo -e "Total tests: $TOTAL_TESTS"
echo -e "Passed: ${GREEN}$PASSED_TESTS${NC}"
echo -e "Failed: ${RED}$FAILED_TESTS${NC}"

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}🎉 All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}❌ Some tests failed.${NC}"
    exit 1
fi
