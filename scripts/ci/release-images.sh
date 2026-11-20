#!/bin/sh
# Validate the two published registry manifests and write one release descriptor.
set -eu
. scripts/ci/image-registry.sh
registry_version image-version/version.txt
[ -n "${CI_REGISTRY_IMAGE:-}" ] && [ -n "${CI_COMMIT_SHA:-}" ] && [ -n "${CI_PIPELINE_ID:-}" ] && [ -n "${CI_PIPELINE_IID:-}" ] || registry_fail 'missing pipeline identity'
registry_login
for role in backend frontend; do
    file="image-evidence/$role.ref"
    [ -s "$file" ] || registry_fail "missing $role digest evidence"
    digest_ref=$(cat "$file")
    expected_prefix="$CI_REGISTRY_IMAGE/$role@"
    case "$digest_ref" in "$expected_prefix"*) ;; *) registry_fail "unrecognized $role image reference" ;; esac
    digest=${digest_ref#"$expected_prefix"}
    registry_assert_sha256 "$digest"
    versioned="$CI_REGISTRY_IMAGE/$role:$image_version"
    remote_digest=$(crane digest "$versioned") || registry_fail "cannot read pushed $role tag"
    [ "$remote_digest" = "$digest" ] || registry_fail "$role tag digest mismatches Kaniko evidence"
    remote_ref_digest=$(crane digest "$digest_ref") || registry_fail "cannot pull $role by digest"
    [ "$remote_ref_digest" = "$digest" ] || registry_fail "$role digest reference changed unexpectedly"
    registry_verify_config "$digest_ref" "$image_version" "$CI_COMMIT_SHA"
    case "$role" in backend) backend_ref=$digest_ref ;; frontend) frontend_ref=$digest_ref ;; esac
done
mkdir -p image-release
# Only write the complete descriptor after BOTH remote images were verified.
tmp=$(mktemp image-release/.release.XXXXXX)
jq -n --arg version "$image_version" --arg sha "$CI_COMMIT_SHA" \
    --arg id "$CI_PIPELINE_ID" --arg iid "$CI_PIPELINE_IID" \
    --arg backend "$backend_ref" --arg frontend "$frontend_ref" \
    '{version:$version,commit:$sha,pipeline_id:$id,pipeline_iid:$iid,images:{backend:{ref:$backend},frontend:{ref:$frontend}}}' > "$tmp"
mv "$tmp" image-release/release.json
printf 'Verified complete image pair: %s\n' "$image_version"
printf 'Backend: %s\nFrontend: %s\n' "$backend_ref" "$frontend_ref"
