#!/bin/bash

set -e

echo "Running instrumentation tests..."

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
TEST_DIR="Tests/instrumentation_tests_$TIMESTAMP"

mkdir -p "$TEST_DIR"

./gradlew clean connectedAndroidTest --continue

find . -name "TEST-*.xml" -path "*/androidTest-results/*" -exec cp {} "$TEST_DIR/" \;
find . -name "*.html" -path "*/androidTest-results/*" -exec cp --parents {} "$TEST_DIR/" \; 2>/dev/null || true

adb logcat -d > "$TEST_DIR/logcat.txt"

echo "Instrumentation tests completed. Reports saved to: $TEST_DIR"
echo ""
echo "Summary:"
grep -r "tests=" "$TEST_DIR"/*.xml 2>/dev/null || echo "No test results found"