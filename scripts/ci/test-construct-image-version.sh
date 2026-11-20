#!/bin/sh
# Dependency-free contract tests for image version creation. Run in any POSIX sh.
set -eu
export LC_ALL=C

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
constructor="$root/scripts/ci/construct-image-version.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
output="$work/version.txt"

# Parameters: branch, slug, IID, source, protected, tag, major, minor.
run_case() {
    env -i PATH="$PATH" \
        CI_COMMIT_BRANCH="$1" CI_COMMIT_REF_SLUG="$2" \
        CI_PIPELINE_IID="$3" CI_PIPELINE_SOURCE="$4" \
        CI_COMMIT_REF_PROTECTED="$5" CI_COMMIT_TAG="$6" \
        MAJOR_VERSION="$7" MINOR_VERSION="$8" \
        sh "$constructor" "$output" > "$work/log" 2>&1
}

count=0
expect_version() {
    expected=$1
    shift
    rm -f "$output"
    if ! run_case "$@"; then
        cat "$work/log" >&2
        printf 'Expected version %s, but constructor failed\n' "$expected" >&2
        exit 1
    fi
    actual=$(cat "$output")
    [ "$actual" = "$expected" ] || {
        printf 'Expected %s, got %s\n' "$expected" "$actual" >&2
        exit 1
    }
    count=$((count + 1))
}
expect_rejection() {
    rm -f "$output"
    if run_case "$@"; then
        printf 'Expected invalid input to be rejected, got %s\n' "$(cat "$output")" >&2
        exit 1
    fi
    [ ! -e "$output" ] || {
        printf 'Rejected input unexpectedly created an artifact\n' >&2
        exit 1
    }
    count=$((count + 1))
}

expect_version '0.1.42' main main 42 push true '' 0 1
expect_version '0.1.43' main main 43 push true '' 0 1
expect_version '0.1.42-feature-ui' feature/ui feature-ui 42 push false '' 0 1
expect_version '0.1.42-feature-foo-bar' feature/foo/bar feature-foo-bar 42 push false '' 0 1
# Never decide main from the slug: a distinct branch may normalize to 'main'.
expect_version '0.1.42-main' Main main 42 push false '' 0 1
long_slug=$(printf '%063d' 0 | tr 0 a)
expect_version "0.1.42-$long_slug" feature/very-long-branch "$long_slug" 42 push false '' 0 1

expect_rejection main main '' push true '' 0 1
expect_rejection main main 0 push true '' 0 1
expect_rejection main main 01 push true '' 0 1
expect_rejection main main -1 push true '' 0 1
expect_rejection main main 4x push true '' 0 1
expect_rejection main main 1.2 push true '' 0 1
expect_rejection main main 42 push false '' 0 1
expect_rejection main main 42 push true '' 1 0
expect_rejection main main 42 push true '' 0 2
expect_rejection main main 42 push true '' 00 1
expect_rejection '' main 42 push true main 0 1
expect_rejection '' main 42 merge_request_event false '' 0 1
expect_rejection main main 42 web true '' 0 1
expect_rejection main main 42 api true '' 0 1
expect_rejection main main 42 schedule true '' 0 1
expect_rejection main main 42 push true main 0 1
expect_rejection feature/foo '' 42 push false '' 0 1
expect_rejection feature/foo Feature-Foo 42 push false '' 0 1
expect_rejection feature/foo feature/foo 42 push false '' 0 1
expect_rejection feature/foo feature_foo 42 push false '' 0 1
expect_rejection feature/foo -feature 42 push false '' 0 1
expect_rejection feature/foo feature- 42 push false '' 0 1
expect_rejection feature/foo "${long_slug}a" 42 push false '' 0 1
huge_iid=$(printf '%0150d' 0 | tr 0 9)
expect_rejection main main "$huge_iid" push true '' 0 1

printf 'Image version contract: %s cases passed\n' "$count"
