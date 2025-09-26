#!/bin/bash

set -e

echo "Running unit tests..."

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
TEST_DIR="Tests/unit_tests_$TIMESTAMP"

mkdir -p "$TEST_DIR"

./gradlew clean test --continue

find . -name "TEST-*.xml" -exec cp {} "$TEST_DIR/" \;
find . -name "*.html" -path "*/test-results/*" -exec cp --parents {} "$TEST_DIR/" \; 2>/dev/null || true

echo "Unit tests completed. Reports saved to: $TEST_DIR"
echo ""
echo "Summary:"
grep -r "tests=" "$TEST_DIR"/*.xml 2>/dev/null || echo "No test results found"