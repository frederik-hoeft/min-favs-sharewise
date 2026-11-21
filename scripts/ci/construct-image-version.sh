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

# A version does not authorize publication: CI rules also restrict which
# project/ref is trusted. Accept only a protected main push or a protected,
# explicitly named dev-<slug> tag push.
[ "${CI_PIPELINE_SOURCE:-}" = push ] || fail 'only push pipelines are versioned'

# Initial approved SemVer prefix. Any bump requires an explicit code review.
[ "${MAJOR_VERSION:-}.${MINOR_VERSION:-}" = 0.1 ] || fail 'unapproved MAJOR_VERSION.MINOR_VERSION prefix'

iid=${CI_PIPELINE_IID:-}
case "$iid" in
    ''|*[!0-9]*|0|0*) fail 'CI_PIPELINE_IID must be a positive canonical decimal integer' ;;
esac

[ "${CI_COMMIT_REF_PROTECTED:-}" = true ] || fail 'publication ref must be protected'

if [ "${CI_COMMIT_BRANCH:-}" = main ] && [ -z "${CI_COMMIT_TAG:-}" ]; then
    version="0.1.$iid"
else
    [ -z "${CI_COMMIT_BRANCH:-}" ] || fail 'non-main branch pipelines are not versioned'
    tag=${CI_COMMIT_TAG:-}
    case "$tag" in
        dev-*) slug=${tag#dev-} ;;
        *) fail 'only dev-<slug> tags are versioned' ;;
    esac
    case "$slug" in
        ''|*[!a-z0-9-]*|-*|*-|*--*) fail 'invalid dev tag slug' ;;
    esac
    [ "${#slug}" -le 63 ] || fail 'dev tag slug exceeds 63 bytes'
    # A numeric-only prerelease identifier with leading zeroes is not SemVer.
    case "$slug" in
        0[0-9]*)
            case "$slug" in
                *[!0-9]*) ;; # Mixed alphanumeric identifiers allow zeroes.
                *) fail 'numeric prerelease slug must not have leading zeroes' ;;
            esac
            ;;
    esac
    version="0.1.$iid-$slug"
fi

# OCI/Docker tag: ASCII, at most 128 characters.
[ "${#version}" -le 128 ] || fail 'Docker image tag exceeds 128 bytes'

mkdir -p "$(dirname "$output")"
printf '%s\n' "$version" > "$output"
printf 'Image version: %s\n' "$version"
