#!/bin/bash

echo "🧪 Testing all example files..."
echo "================================"

PASSED=0
FAILED=0
PARSING_FAILED=0

for file in examples/*.funk; do
    filename=$(basename "$file")
    echo -n "Testing $filename... "
    
    # Test parsing first
    if ./funk parse "$file" > /dev/null 2>&1; then
        # If parsing succeeds, test type checking
        if ./funk check "$file" > /dev/null 2>&1; then
            echo "✅ PASS"
            ((PASSED++))
        else
            echo "❌ TYPE CHECK FAILED"
            echo "   Error output:"
            ./funk check "$file" 2>&1 | sed 's/^/   /'
            ((FAILED++))
        fi
    else
        echo "💥 PARSE FAILED"
        echo "   Error output:"
        ./funk parse "$file" 2>&1 | sed 's/^/   /'
        ((PARSING_FAILED++))
    fi
    echo
done

echo "================================"
echo "📊 Summary:"
echo "   ✅ Passed: $PASSED"
echo "   ❌ Type check failed: $FAILED"
echo "   💥 Parse failed: $PARSING_FAILED"
echo "   📁 Total files: $((PASSED + FAILED + PARSING_FAILED))"

if [ $FAILED -eq 0 ] && [ $PARSING_FAILED -eq 0 ]; then
    echo "🎉 All tests passed!"
    exit 0
else
    echo "⚠️  Some tests failed."
    exit 1
fi
