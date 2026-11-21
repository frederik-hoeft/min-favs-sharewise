#!/bin/sh
# Build/push one image using the Phase A verified daemonless Kaniko executor.
set -eu
export LC_ALL=C

fail() { printf 'Image publishing: %s\n' "$1" >&2; exit 1; }
[ "$#" -eq 1 ] || fail 'expected backend or frontend'
[ -n "${CI_PROJECT_DIR:-}" ] || fail 'missing CI_PROJECT_DIR'
case "$1" in
    backend) context="$CI_PROJECT_DIR/src/backend/sharewise" ;;
    frontend) context="$CI_PROJECT_DIR/src/frontend" ;;
    *) fail 'unknown image role' ;;
esac
role=$1
[ -n "${CI_REGISTRY:-}" ] || fail 'missing CI_REGISTRY'
[ -n "${CI_REGISTRY_IMAGE:-}" ] || fail 'missing CI_REGISTRY_IMAGE'
[ -n "${CI_REGISTRY_USER:-}" ] || fail 'missing CI_REGISTRY_USER'
[ -n "${CI_REGISTRY_PASSWORD:-}" ] || fail 'missing CI_REGISTRY_PASSWORD'
[ -n "${CI_COMMIT_SHA:-}" ] || fail 'missing CI_COMMIT_SHA'
# Share the artifact identity check with the registry verification jobs.
. scripts/ci/image-registry.sh
registry_version image-version/version.txt
version=$image_version
case "$CI_COMMIT_SHA" in *[!0123456789abcdef]*|'') fail 'invalid revision' ;; esac
[ "${#CI_COMMIT_SHA}" -eq 40 ] || fail 'revision must contain 40 hexadecimal digits'

out="image-evidence/$role"
config_dir=${KANIKO_DOCKER_CONFIG:-/kaniko/.docker}
executor=${KANIKO_EXECUTOR:-/kaniko/executor}
mkdir -p image-evidence "$config_dir"
rm -f "$out.digest" "$out.ref"
chmod 700 "$config_dir"
# The GitLab short-lived registry credentials are never passed as Dockerfile
# build arguments or written to an artifact or the image build context.
auth="$(printf '%s:%s' "$CI_REGISTRY_USER" "$CI_REGISTRY_PASSWORD" | base64 | tr -d '\r\n')"
printf '{"auths":{"%s":{"auth":"%s"}}}\n' "$CI_REGISTRY" "$auth" > "$config_dir/config.json"
chmod 600 "$config_dir/config.json"
unset auth

ref="$CI_REGISTRY_IMAGE/$role:$version"
# No docker socket, DinD, privileged mode or shared package-manager cache.
"$executor" \
    --context="dir://$context" \
    --dockerfile="$context/Dockerfile" \
    --destination="$ref" \
    --digest-file="$out.digest" \
    --image-name-with-digest-file="$out.ref" \
    --build-arg="BUILD_VERSION=$version" \
    --build-arg="VCS_REF=$CI_COMMIT_SHA" \
    --reproducible \
    --push-retry=2

[ -s "$out.digest" ] && [ -s "$out.ref" ] || fail 'Kaniko did not report a pushed digest and reference'
digest=$(cat "$out.digest")
registry_assert_sha256 "$digest"
[ "$(cat "$out.ref")" = "$CI_REGISTRY_IMAGE/$role@$digest" ] || fail 'Kaniko digest and name-with-digest disagree'
# This is build evidence; a separate release job re-queries the actual registry.
printf '%s published %s\n' "$role" "$(cat "$out.ref")"
