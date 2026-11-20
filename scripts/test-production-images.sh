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
tls_fixture="$(mktemp -d)"
# Use a dedicated test hostname pinned to IPv4. `localhost` may resolve to
# ::1 first inside Alpine, while the production HTTPS listener is IPv4-only.
# Preserve hostname-based TLS/SNI and Host checks without changing production
# listeners or weakening the default TLS handshake rejection.
smoke_host="sharewise-smoke.test"
cleanup() {
    rm -rf "$tls_fixture"
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
    test -s /etc/nginx/templates/default.conf.template
    test -x /docker-entrypoint.d/15-configure-sharewise.envsh
'

# Probe from within the container: Docker-outside-of-Docker and remote Docker
# contexts do not necessarily expose host-published ports to this CLI process.
# Keep stopped containers until cleanup so their startup logs can be inspected.
# `docker cp` transfers files through the Docker API, so the self-signed test
# certificate also works with Docker-outside-of-Docker and remote daemons.
# The production image contains no certificate or private key.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$smoke_host" \
    -addext "subjectAltName=DNS:$smoke_host" \
    -keyout "$tls_fixture/privkey.pem" -out "$tls_fixture/fullchain.pem" >/dev/null 2>&1
container_id="$("$container_cli" create --add-host "$smoke_host:127.0.0.1" \
    -e "SHAREWISE_PUBLIC_HOST=$smoke_host" "$frontend_image")"
"$container_cli" cp "$tls_fixture/privkey.pem" "$container_id:/etc/nginx/tls/privkey.pem"
"$container_cli" cp "$tls_fixture/fullchain.pem" "$container_id:/etc/nginx/tls/fullchain.pem"
report_frontend_failure() {
    echo 'Frontend failed to start or validate; container state and logs:' >&2
    "$container_cli" inspect --format '{{.State.Status}} (exit code {{.State.ExitCode}}, OOM killed: {{.State.OOMKilled}})' "$container_id" >&2 || true
    "$container_cli" logs "$container_id" >&2 || true
}
if ! "$container_cli" start "$container_id" >/dev/null; then
    report_frontend_failure
    exit 1
fi
# `docker start` can return before Nginx opens its listening sockets. A
# connection refusal during this interval is expected, not a smoke-test error.
# A separate `nginx -t` may also succeed before PID 1 has finished startup,
# so check the running service first, then validate its rendered configuration.
ready=0
for attempt in $(seq 1 20); do
    if "$container_cli" exec "$container_id" wget -q -O /dev/null http://127.0.0.1:8081/health >/dev/null 2>&1; then
        ready=1
        break
    fi
    # Do not retry against a container whose Nginx process has already exited.
    if ! "$container_cli" inspect --format '{{.State.Running}}' "$container_id" | grep -qx true; then
        echo 'Frontend stopped before becoming ready' >&2
        report_frontend_failure
        exit 1
    fi
    sleep 1
done
if [[ "$ready" -ne 1 ]]; then
    echo 'Frontend did not become ready within 20 seconds' >&2
    report_frontend_failure
    exit 1
fi
if ! "$container_cli" exec "$container_id" nginx -t; then
    report_frontend_failure
    exit 1
fi
check_https_route() {
    local url="https://${smoke_host}$1"
    if ! "$container_cli" exec "$container_id" wget -q --no-check-certificate -O /dev/null "$url"; then
        printf 'Frontend HTTPS request failed: %s\n' "$url" >&2
        report_frontend_failure
        return 1
    fi
}
for path in / /app /app/dummy1 /app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview; do
    check_https_route "$path"
done
# BusyBox wget returns nonzero for 404. Check the response status as well,
# otherwise a connection or server error could masquerade as the expected 404.
if not_found_response="$("$container_cli" exec "$container_id" wget -S -O /dev/null --no-check-certificate "https://${smoke_host}/no-such-route" 2>&1)"; then
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
check_https_route "${asset#/usr/share/nginx/html}"
# API transport/trust behavior is tested separately with a controlled backend.
echo 'Production image composition and HTTPS static route smoke checks passed.'
