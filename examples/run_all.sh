#!/bin/bash
# Run all Mezze examples and save output
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$DIR/.." && pwd)"
cd "$PROJECT_ROOT"

for f in "$DIR"/*.mz; do
    name=$(basename "$f" .mz)
    echo "=== Running $name ==="
    output_file="$DIR/${name}.output.txt"
    if ./gradlew :cli:run --args="exec $f" --console=plain 2>&1 | grep -v "^> Task\|SKIPPED\|UP-TO-DATE\|NO-SOURCE\|^$\|Deprecated\|warning-mode\|please refer\|Problems report\|Incubating\|actionable\|BUILD \|WARNING:" > "$output_file" 2>&1; then
        echo "  OK"
        cat "$output_file"
    else
        echo "  FAILED"
        cat "$output_file"
    fi
    echo ""
done
