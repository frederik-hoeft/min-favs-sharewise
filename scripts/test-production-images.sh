#!/usr/bin/env bash
# Run on a container-capable host to verify Phase B image composition and routes.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
container_cli="${CONTAINER_CLI:-docker}"
"$container_cli" version >/dev/null
revision="$(git -C "$repo_root" rev-parse HEAD)"
version="p26ab-b-smoke"
backend_image="sharewise-backend:${version}"
frontend_image="sharewise-frontend:${version}"
container_id=""
cleanup() {
    if [[ -n "$container_id" ]]; then
        "$container_cli" rm -f "$container_id" >/dev/null || true
    fi
}
trap cleanup EXIT

"$container_cli" build --no-cache --build-arg "VCS_REF=$revision" --build-arg "BUILD_VERSION=$version" \
    --tag "$backend_image" "$repo_root/src/backend/sharewise"
"$container_cli" build --no-cache --build-arg "VCS_REF=$revision" --build-arg "BUILD_VERSION=$version" \
    --tag "$frontend_image" "$repo_root/src/frontend"

for image in "$backend_image" "$frontend_image"; do
    [[ "$("$container_cli" image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$image")" == "$version" ]]
    [[ "$("$container_cli" image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" == "$revision" ]]
done

# Never require local artifacts, Maven, a JDK, Node or source in the runtime images.
"$container_cli" run --rm --entrypoint sh "$backend_image" -ec '
    test "$(id -u)" = 10001
    test -s /app/app.jar
    command -v java
    command -v curl
    . /etc/os-release
    test "$ID" = debian
    test "$VERSION_ID" = 13
    dpkg --compare-versions "$(dpkg-query -W -f=\${Version} openjdk-25-jre-headless)" ge 25.0.5.1+2-1~deb13u1
    ! command -v javac
    ! command -v mvn
    ! test -d /build
'
"$container_cli" run --rm --entrypoint sh "$frontend_image" -ec '
    test -s /usr/share/nginx/html/index.html
    test -s /usr/share/nginx/html/app.html
    command -v nginx
    ! command -v node
    ! command -v npm
    ! test -d /app/node_modules
    ! test -e /app/server.js
    ! test -d /app/.next
    nginx -t
'

# Probe from within the container: Docker-outside-of-Docker and remote Docker
# contexts do not necessarily expose host-published ports to this CLI process.
# Keep stopped containers until cleanup so their startup logs can be inspected.
container_id="$("$container_cli" run -d "$frontend_image")"
ready=0
for attempt in $(seq 1 20); do
    if "$container_cli" exec "$container_id" wget -q -O /dev/null http://127.0.0.1/; then
        ready=1
        break
    fi
    sleep 1
done
if [[ "$ready" -ne 1 ]]; then
    echo 'Frontend did not become ready; container state and logs:' >&2
    "$container_cli" inspect --format '{{.State.Status}} (exit code {{.State.ExitCode}})' "$container_id" >&2 || true
    "$container_cli" logs "$container_id" >&2 || true
    exit 1
fi
for path in / /app /app/dummy1 /app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview; do
    "$container_cli" exec "$container_id" wget -q -O /dev/null "http://127.0.0.1$path"
done
# BusyBox wget returns nonzero for 404. Check the response status as well,
# otherwise a connection or server error could masquerade as the expected 404.
if not_found_response="$("$container_cli" exec "$container_id" wget -S -O /dev/null http://127.0.0.1/no-such-route 2>&1)"; then
    echo 'Expected HTTP 404 for /no-such-route, but the request succeeded' >&2
    exit 1
fi
if ! grep -Eq 'HTTP/[0-9.]+ 404([[:space:]]|$)' <<<"$not_found_response"; then
    printf 'Expected HTTP 404 for /no-such-route; received:\n%s\n' "$not_found_response" >&2
    exit 1
fi
asset="$("$container_cli" run --rm --entrypoint sh "$frontend_image" -c \
    'find /usr/share/nginx/html/_next/static -type f -name "*.js" -print -quit')"
[[ "$asset" == /usr/share/nginx/html/_next/static/* ]]
"$container_cli" exec "$container_id" wget -q -O /dev/null "http://127.0.0.1${asset#/usr/share/nginx/html}"
# Phase C will add the /api reverse proxy and TLS; Phase B tests static routes only.
echo 'Phase B image composition and static route smoke checks passed.'
