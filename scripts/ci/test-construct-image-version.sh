#!/bin/sh
# Dependency-free contract tests for image version creation. Run in any POSIX sh.
set -eu
export LC_ALL=C

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
constructor="$root/scripts/ci/construct-image-version.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
output="$work/version.txt"

# Parameters: branch, ref slug, IID, source, protected, tag, major, minor.
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

# Stable images are built only from protected main pushes.
expect_version '0.1.42' main main 42 push true '' 0 1
expect_version '0.1.43' main main 43 push true '' 0 1

# dev-<slug> is an opt-in Git tag, not a feature branch or part of the version.
expect_version '0.1.42-demo' '' dev-demo 42 push true dev-demo 0 1
expect_version '0.1.43-feature-foo' '' dev-feature-foo 43 push true dev-feature-foo 0 1
expect_version '0.1.44-main' '' dev-main 44 push true dev-main 0 1
expect_version '0.1.45-0' '' dev-0 45 push true dev-0 0 1
# The suffix comes from the full tag, not CI_COMMIT_REF_SLUG.
expect_version '0.1.46-tag-derived' '' unrelated-ref-slug 46 push true dev-tag-derived 0 1
expect_version '0.1.47-01alpha' '' dev-01alpha 47 push true dev-01alpha 0 1
long_slug=$(printf '%063d' 0 | tr 0 a)
expect_version "0.1.42-$long_slug" '' "dev-$long_slug" 42 push true "dev-$long_slug" 0 1

# Main must be an actual protected branch. Feature branches never publish.
expect_rejection main main 42 push false '' 0 1
expect_rejection feature/foo feature-foo 42 push false '' 0 1
expect_rejection feature/foo feature-foo 42 push true '' 0 1
expect_rejection Main main 42 push true '' 0 1
expect_rejection '' main 42 push true '' 0 1
expect_rejection main main 42 push true dev-demo 0 1
expect_rejection feature/foo feature-foo 42 push true dev-demo 0 1

# Preview tags must be explicitly named, protected, and originate from pushes.
expect_rejection '' dev-demo 42 push false dev-demo 0 1
expect_rejection '' dev-demo 42 web true dev-demo 0 1
expect_rejection '' dev-demo 42 api true dev-demo 0 1
expect_rejection '' dev-demo 42 schedule true dev-demo 0 1
expect_rejection '' v1-0 42 push true v1-0 0 1
expect_rejection '' dev- 42 push true dev- 0 1
expect_rejection '' dev-Foo 42 push true dev-Foo 0 1
expect_rejection '' dev-foo/bar 42 push true dev-foo/bar 0 1
expect_rejection '' dev_foo 42 push true dev_foo 0 1
expect_rejection '' dev-foo_bar 42 push true dev-foo_bar 0 1
expect_rejection '' dev-foo.bar 42 push true dev-foo.bar 0 1
expect_rejection '' dev--foo 42 push true dev--foo 0 1
expect_rejection '' dev-foo- 42 push true dev-foo- 0 1
expect_rejection '' dev-foo--bar 42 push true dev-foo--bar 0 1
expect_rejection '' dev-01 42 push true dev-01 0 1
expect_rejection '' "dev-${long_slug}a" 42 push true "dev-${long_slug}a" 0 1

# Shared version-prefix and canonical IID enforcement applies to both refs.
expect_rejection main main '' push true '' 0 1
expect_rejection main main 0 push true '' 0 1
expect_rejection main main 01 push true '' 0 1
expect_rejection main main -1 push true '' 0 1
expect_rejection main main 4x push true '' 0 1
expect_rejection main main 1.2 push true '' 0 1
expect_rejection main main 42 push true '' 1 0
expect_rejection main main 42 push true '' 0 2
expect_rejection main main 42 push true '' 00 1
expect_rejection '' dev-demo 42 push true dev-demo 0 2
expect_rejection main main 42 web true '' 0 1
expect_rejection main main 42 api true '' 0 1
expect_rejection main main 42 schedule true '' 0 1
expect_rejection '' dev-demo 42 merge_request_event true dev-demo 0 1
huge_iid=$(printf '%0150d' 0 | tr 0 9)
expect_rejection main main "$huge_iid" push true '' 0 1

printf 'Image version contract: %s cases passed\n' "$count"
