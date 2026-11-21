#!/bin/sh
# Fail early when retrying a pipeline that already published version tags.
set -eu
. scripts/ci/image-registry.sh
registry_version image-version/version.txt
[ -n "${CI_REGISTRY_IMAGE:-}" ] || registry_fail 'missing registry image root'
registry_login
for role in backend frontend; do
    ref="$CI_REGISTRY_IMAGE/$role:$image_version"
    if digest=$(registry_lookup_digest "$ref"); then
        registry_fail "version tag already exists: $ref ($digest); version tags must not be overwritten"
    else
        status=$?
        [ "$status" -eq 1 ] || registry_fail "unable to verify absence of $ref"
    fi
done
printf 'Both version tags are unused: %s\n' "$image_version"
