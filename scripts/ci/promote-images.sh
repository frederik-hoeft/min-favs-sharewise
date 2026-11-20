#!/bin/sh
# Main-only, serialized, stale-pipeline-safe best-effort latest promotion.
set -eu
. scripts/ci/image-registry.sh
[ "${CI_PIPELINE_SOURCE:-}" = push ] && [ "${CI_COMMIT_BRANCH:-}" = main ] && [ "${CI_COMMIT_REF_PROTECTED:-}" = true ] && [ -z "${CI_COMMIT_TAG:-}" ] || registry_fail 'only protected main pushes may promote latest'
[ "${CI_PROJECT_PATH:-}" = min-favs/sharewise ] || registry_fail 'wrong project for promotion'
[ -s image-release/release.json ] || registry_fail 'missing release descriptor'
version=$(jq -er '.version' image-release/release.json)
[ "$version" = "0.1.${CI_PIPELINE_IID:-}" ] || registry_fail 'release version does not match main pipeline'
[ "$(jq -er '.commit' image-release/release.json)" = "${CI_COMMIT_SHA:-}" ] || registry_fail 'release commit mismatch'
[ "$(jq -er '.pipeline_id' image-release/release.json)" = "${CI_PIPELINE_ID:-}" ] || registry_fail 'release pipeline identity mismatch'
case "$CI_PIPELINE_IID" in *[!0-9]*|'') registry_fail 'invalid pipeline IID' ;; esac
registry_login

# Preflight both aliases before mutating either. A partial prior promotion may
# contain one target digest and one older digest; retry can repair it.
for role in backend frontend; do
    repo="$CI_REGISTRY_IMAGE/$role"
    ref=$(jq -er --arg role "$role" '.images[$role].ref' image-release/release.json)
    case "$ref" in "$repo@sha256:"*) ;; *) registry_fail "invalid $role release ref" ;; esac
    digest=${ref#"$repo@"}
    registry_assert_sha256 "$digest"
    [ "$(crane digest "$ref")" = "$digest" ] || registry_fail "$role release digest not available"
    registry_verify_config "$ref" "$version" "$CI_COMMIT_SHA"
    if old_digest=$(registry_lookup_digest "$repo:latest"); then
        registry_assert_sha256 "$old_digest"
        old_version=$(crane config "$repo:latest" | jq -er '.config.Labels["org.opencontainers.image.version"]') || registry_fail "cannot read $role latest version label"
        case "$old_version" in 0.1.*) ;; *) registry_fail "$role latest has unknown version $old_version" ;; esac
        old_iid=${old_version#0.1.}
        case "$old_iid" in ''|0*|*[!0-9]*) registry_fail "$role latest is not a stable version" ;; esac
        if [ "$old_iid" -gt "$CI_PIPELINE_IID" ]; then
            registry_fail "refusing stale promotion: $role latest $old_version is newer than $version"
        fi
        if [ "$old_iid" -eq "$CI_PIPELINE_IID" ] && [ "$old_digest" != "$digest" ]; then
            registry_fail "same-version $role latest digest mismatch"
        fi
    else
        status=$?
        [ "$status" -eq 1 ] || registry_fail "cannot inspect $role latest"
    fi
done

# A failure after the first update is deliberately visible and non-atomic.
# Subsequent retry repairs an alias that already points at this release.
for role in backend frontend; do
    repo="$CI_REGISTRY_IMAGE/$role"
    ref=$(jq -er --arg role "$role" '.images[$role].ref' image-release/release.json)
    digest=${ref#"$repo@"}
    crane tag "$ref" latest || registry_fail "$role latest promotion failed; other alias may have changed"
    [ "$(crane digest "$repo:latest")" = "$digest" ] || registry_fail "$role latest verification failed; alias promotion may be incomplete"
done
printf 'Both latest aliases point to release %s. Deployments must use the digest pair.\n' "$version"
