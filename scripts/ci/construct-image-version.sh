#!/bin/sh
# Produce the single version artifact consumed by subsequent image jobs.
set -eu
export LC_ALL=C

fail() {
    printf 'Invalid image version input: %s\n' "$1" >&2
    exit 1
}

[ "$#" -eq 1 ] || fail 'expected exactly one output path'
output=$1
[ -n "$output" ] || fail 'output path is empty'

# A version does not authorize publication: CI job rules also restrict which
# project/ref is trusted. Reject tag/MR/manual contexts if called directly.
[ "${CI_PIPELINE_SOURCE:-}" = push ] || fail 'only branch push pipelines are versioned'
[ -n "${CI_COMMIT_BRANCH:-}" ] || fail 'branch identity is missing'
[ -z "${CI_COMMIT_TAG:-}" ] || fail 'tag pipelines are not versioned'

# Initial approved SemVer prefix. Any bump requires an explicit code review.
[ "${MAJOR_VERSION:-}.${MINOR_VERSION:-}" = 0.1 ] || fail 'unapproved MAJOR_VERSION.MINOR_VERSION prefix'

iid=${CI_PIPELINE_IID:-}
case "$iid" in
    ''|*[!0-9]*|0|0*) fail 'CI_PIPELINE_IID must be a positive canonical decimal integer' ;;
esac

if [ "$CI_COMMIT_BRANCH" = main ]; then
    [ "${CI_COMMIT_REF_PROTECTED:-}" = true ] || fail 'main must be protected'
    version="0.1.$iid"
else
    slug=${CI_COMMIT_REF_SLUG:-}
    case "$slug" in
        ''|*[!a-z0-9-]*|-*|*-) fail 'invalid CI_COMMIT_REF_SLUG' ;;
    esac
    [ "${#slug}" -le 63 ] || fail 'CI_COMMIT_REF_SLUG exceeds 63 bytes'
    version="0.1.$iid-$slug"
fi

# OCI/Docker tag: ASCII, at most 128 characters.
[ "${#version}" -le 128 ] || fail 'Docker image tag exceeds 128 bytes'

mkdir -p "$(dirname "$output")"
printf '%s\n' "$version" > "$output"
printf 'Image version: %s\n' "$version"
