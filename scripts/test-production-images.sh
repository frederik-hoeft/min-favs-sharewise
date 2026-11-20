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

container_id="$("$container_cli" run --rm -d -p 127.0.0.1::80 "$frontend_image")"
port="$("$container_cli" port "$container_id" 80/tcp | sed -n 's/.*://p' | head -n 1)"
[[ "$port" =~ ^[0-9]+$ ]]
base="http://127.0.0.1:$port"
ready=0
for attempt in $(seq 1 20); do
    if curl --fail --silent --output /dev/null "$base/"; then
        ready=1
        break
    fi
    sleep 1
done
[[ "$ready" -eq 1 ]] || { echo 'Frontend did not become ready' >&2; exit 1; }
for path in / /app /app/dummy1 /app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview; do
    curl --fail --silent --output /dev/null "$base$path"
done
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$base/no-such-route")" == 404 ]]
asset="$("$container_cli" run --rm --entrypoint sh "$frontend_image" -c \
    'find /usr/share/nginx/html/_next/static -type f -name "*.js" -print -quit')"
[[ "$asset" == /usr/share/nginx/html/_next/static/* ]]
curl --fail --silent --output /dev/null "$base${asset#/usr/share/nginx/html}"
# Phase C will add the /api reverse proxy and TLS; Phase B tests static routes only.
echo 'Phase B image composition and static route smoke checks passed.'
