#!/bin/sh
# Registry-side checks shared by preflight, release and latest promotion.
# Source from POSIX shell jobs with crane + jq installed.

registry_fail() { printf 'Image registry: %s\n' "$1" >&2; exit 1; }

registry_login() {
    [ -n "${CI_REGISTRY:-}" ] && [ -n "${CI_REGISTRY_USER:-}" ] && [ -n "${CI_REGISTRY_PASSWORD:-}" ] || registry_fail 'missing registry credentials'
    DOCKER_CONFIG="$(mktemp -d)"
    export DOCKER_CONFIG
    chmod 700 "$DOCKER_CONFIG"
    # Keep credentials out of command arguments and logs.
    printf '%s' "$CI_REGISTRY_PASSWORD" | crane auth login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY" >/dev/null
}

# On a first release, the image path or tag can be absent. Other failures,
# notably auth or connectivity errors, must not be mistaken for absence.
registry_lookup_digest() {
    ref=$1
    errfile=$(mktemp)
    if crane digest "$ref" 2>"$errfile"; then
        rm -f "$errfile"
        return 0
    fi
    if grep -Eiq 'MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found|status code 404' "$errfile"; then
        rm -f "$errfile"
        return 1
    fi
    cat "$errfile" >&2
    rm -f "$errfile"
    return 2
}

registry_assert_sha256() {
    case "$1" in sha256:*) hex=${1#sha256:} ;; *) registry_fail 'invalid sha256 digest prefix' ;; esac
    [ "${#hex}" -eq 64 ] || registry_fail 'invalid sha256 digest length'
    case "$hex" in *[!0123456789abcdef]*|'') registry_fail 'invalid sha256 digest characters' ;; esac
}

registry_version() {
    [ -s "$1" ] || registry_fail 'missing version artifact'
    IFS= read -r image_version < "$1" || [ -n "${image_version:-}" ]
    [ "$(wc -l < "$1" | tr -d ' ')" -eq 1 ] || registry_fail 'multiline version artifact'
    case "$image_version" in
        0.1.[1-9]*|0.1.[1-9]*-*) ;;
        *) registry_fail 'unexpected image version' ;;
    esac
    case "$image_version" in *[!a-z0-9.-]* ) registry_fail 'invalid version characters' ;; esac
    # The artifact remains authoritative; these checks only ensure it belongs
    # to this trusted pipeline and cannot be substituted across job retries.
    [ "${CI_PIPELINE_SOURCE:-}" = push ] && [ "${CI_COMMIT_REF_PROTECTED:-}" = true ] && [ "${CI_PROJECT_PATH:-}" = min-favs/sharewise ] || registry_fail 'untrusted image-publishing context'
    case "${CI_PIPELINE_IID:-}" in ''|0|0*|*[!0-9]*) registry_fail 'invalid pipeline IID' ;; esac
    if [ "${CI_COMMIT_BRANCH:-}" = main ] && [ -z "${CI_COMMIT_TAG:-}" ]; then
        expected="0.1.$CI_PIPELINE_IID"
    else
        [ -z "${CI_COMMIT_BRANCH:-}" ] || registry_fail 'cannot publish from a non-main branch'
        case "${CI_COMMIT_TAG:-}" in dev-*) slug=${CI_COMMIT_TAG#dev-} ;; *) registry_fail 'invalid preview tag' ;; esac
        case "$slug" in ''|*[!a-z0-9-]*|-*|*-|*--*) registry_fail 'invalid preview slug' ;; esac
        expected="0.1.$CI_PIPELINE_IID-$slug"
    fi
    [ "$image_version" = "$expected" ] || registry_fail 'version artifact does not match pipeline identity'
}

registry_verify_config() {
    # Verify the actual registry manifest's image labels, not only CI artifacts.
    actual_ref=$1
    expected_version=$2
    expected_sha=$3
    crane config "$actual_ref" | jq -e --arg v "$expected_version" --arg sha "$expected_sha" \
        '(.config.Labels["org.opencontainers.image.version"] == $v) and (.config.Labels["org.opencontainers.image.revision"] == $sha)' >/dev/null || registry_fail "image labels mismatch for $actual_ref"
}
