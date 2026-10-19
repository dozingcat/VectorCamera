#!/bin/bash
# Builds and runs the host C++ tests (every *_test.cpp in this directory) with the host clang++.
#
# Usage: app/src/test/cpp/run_tests.sh [--tsan] [--keep-going] [name...]
#   --tsan        build with ThreadSanitizer (-O1 -g -fsanitize=thread) instead of -O2
#   --keep-going  run the remaining tests after a failure
#   name          only tests whose file name contains this (e.g. "oil", "ascii")
#
# Binaries go in app/build/cpp-tests. Exits non-zero if any test fails to build or run.

set -u

cd "$(dirname "$0")/../../../.." || exit 1
TEST_DIR=app/src/test/cpp
OUT_DIR=app/build/cpp-tests
CXX=${CXX:-clang++}
FLAGS=(-std=c++17 -Wall -DVC_NO_JNI)
MODE_FLAGS=(-O2)
KEEP_GOING=0
FILTERS=()

for arg in "$@"; do
    case "$arg" in
        --tsan) MODE_FLAGS=(-O1 -g -fsanitize=thread) ;;
        --keep-going) KEEP_GOING=1 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        -*) echo "Unknown option: $arg" >&2; exit 2 ;;
        *) FILTERS+=("$arg") ;;
    esac
done

mkdir -p "$OUT_DIR"
passed=()
failed=()
for src in "$TEST_DIR"/*_test.cpp; do
    name=$(basename "$src" .cpp)
    if [ ${#FILTERS[@]} -gt 0 ]; then
        match=0
        for f in "${FILTERS[@]}"; do
            case "$name" in *"$f"*) match=1 ;; esac
        done
        [ $match -eq 1 ] || continue
    fi
    bin="$OUT_DIR/$name"
    echo "== $name"
    if "$CXX" "${FLAGS[@]}" "${MODE_FLAGS[@]}" "$src" -o "$bin" && "$bin"; then
        passed+=("$name")
    else
        failed+=("$name")
        echo "FAILED: $name"
        [ $KEEP_GOING -eq 1 ] || break
    fi
done

echo
echo "${#passed[@]} passed, ${#failed[@]} failed"
if [ ${#failed[@]} -gt 0 ]; then
    echo "Failed: ${failed[*]}"
    exit 1
fi
