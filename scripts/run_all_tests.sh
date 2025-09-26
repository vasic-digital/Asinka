#!/bin/bash

set -e

echo "========================================="
echo "Running all Asinka tests"
echo "========================================="
echo ""

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
MAIN_TEST_DIR="Tests/all_tests_$TIMESTAMP"

mkdir -p "$MAIN_TEST_DIR"

echo "Step 1: Unit Tests"
echo "-------------------"
./scripts/run_unit_tests.sh
cp -r Tests/unit_tests_* "$MAIN_TEST_DIR/" 2>/dev/null || true
echo ""

echo "Step 2: Instrumentation Tests"
echo "------------------------------"
if adb devices | grep -q "device$"; then
    ./scripts/run_instrumentation_tests.sh
    cp -r Tests/instrumentation_tests_* "$MAIN_TEST_DIR/" 2>/dev/null || true
else
    echo "WARNING: No Android device/emulator connected. Skipping instrumentation tests."
fi
echo ""

echo "========================================="
echo "Test Summary"
echo "========================================="
echo "All test reports saved to: $MAIN_TEST_DIR"
echo ""

if [ -f "$MAIN_TEST_DIR"/unit_tests_*/TEST-*.xml ]; then
    echo "Unit Test Results:"
    grep -h "tests=" "$MAIN_TEST_DIR"/unit_tests_*/TEST-*.xml | head -1
fi

if [ -f "$MAIN_TEST_DIR"/instrumentation_tests_*/TEST-*.xml ]; then
    echo "Instrumentation Test Results:"
    grep -h "tests=" "$MAIN_TEST_DIR"/instrumentation_tests_*/TEST-*.xml | head -1
fi

echo ""
echo "========================================="
echo "All tests completed!"
echo "========================================="