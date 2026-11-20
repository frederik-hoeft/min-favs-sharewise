#!/usr/bin/env bash
# Acceptance probe against an explicitly DISPOSABLE production-style Compose stack.
# Does not remove Docker volumes. Use a unique project name for each fresh run.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cli="${CONTAINER_CLI:-docker}"
env_file="${SHAREWISE_COMPOSE_ENV_FILE:-$root/.local/production/compose.env}"
[[ -f "$env_file" ]] || { echo "Missing Compose environment file: $env_file" >&2; exit 1; }
# A separate project prevents this test from touching the normal local stack.
project="${SHAREWISE_COMPOSE_TEST_PROJECT:-sharewise-p26ab-smoke}"
[[ "$project" =~ ^[a-z][a-z0-9_-]+$ ]] || { echo 'Invalid test project name' >&2; exit 1; }
[[ "$project" != sharewise-p26ab-local ]] || { echo 'Refusing to test against the default local stack' >&2; exit 1; }
compose=("$cli" compose --env-file "$env_file" -f "$root/compose.production.yaml" -p "$project")

"$cli" version >/dev/null
"${compose[@]}" config -q
# The first-start test must not accidentally reuse a previously initialized
# database (which would skip the production init scripts entirely).
volume="${project}_postgres_data"
if "$cli" volume inspect "$volume" >/dev/null 2>&1; then
    printf 'Refusing to reuse populated test volume %s. Choose a fresh test project or explicitly remove the disposable stack and its volume.\n' "$volume" >&2
    exit 1
fi
"${compose[@]}" up --build --wait -d
"${compose[@]}" ps

for service in postgres backend; do
    id="$("${compose[@]}" ps -q "$service")"
    [[ -n "$id" ]] || { echo "Missing container: $service" >&2; exit 1; }
    # Exposed container ports are allowed, but published host bindings are not.
    [[ "$("$cli" inspect -f '{{json .HostConfig.PortBindings}}' "$id")" == '{}' ]] || {
        echo "Unexpected host-published port on $service" >&2
        exit 1
    }
done

# Execute HTTP through the backend container on the same edge network, avoiding
# the host/daemon localhost mismatch in Docker-outside-of-Docker environments.
# --connect-to preserves TLS SNI and HTTP Host while routing through Compose DNS.
host="$("${compose[@]}" exec -T frontend sh -ec 'printf %s "$SHAREWISE_PUBLIC_HOST"')"
http_response="$("${compose[@]}" exec -T backend curl --silent --show-error --fail \
    --max-time 10 --insecure --connect-to "$host:443:frontend:443" \
    "https://$host/api/system/v1/health")"
[[ "$http_response" == *'"status":"UP"'* ]] || {
    printf 'Unexpected HTTPS API readiness response: %s\n' "$http_response" >&2
    exit 1
}
"${compose[@]}" exec -T backend curl --silent --show-error --fail --insecure \
    --connect-to "$host:443:frontend:443" "https://$host/" >/dev/null

# Verify the public redirect/host trust boundary using the real Compose proxy.
https_port="$("${compose[@]}" exec -T frontend sh -ec 'printf %s "$SHAREWISE_HTTPS_PORT"')"
redirect_status="$("${compose[@]}" exec -T backend curl --silent --output /dev/null \
    --write-out '%{http_code} %{redirect_url}' --max-time 10 \
    --connect-to "$host:80:frontend:80" "http://$host/example")"
[[ "$redirect_status" == "308 https://$host:$https_port/example" ]] || {
    printf 'Unexpected HTTP redirect: %s\n' "$redirect_status" >&2
    exit 1
}
wrong_host_status="$("${compose[@]}" exec -T backend curl --silent --insecure \
    --output /dev/null --write-out '%{http_code}' --max-time 10 \
    --connect-to "$host:443:frontend:443" -H 'Host: forged.invalid' \
    "https://$host/")"
[[ "$wrong_host_status" == 421 ]] || {
    printf 'Expected HTTP 421 for mismatched SNI/Host; got %s\n' "$wrong_host_status" >&2
    exit 1
}

# A psql process reads the password within its own container; no credentials
# are passed through CLI arguments or printed into the CI/developer terminal.
query() {
    local role="$1" secret="$2" sql="$3"
    "${compose[@]}" exec -T postgres sh -ec '
        PGPASSWORD="$(cat "/run/secrets/$1")"; export PGPASSWORD
        exec psql -X -h 127.0.0.1 -U "$2" -d sharewise -v ON_ERROR_STOP=1 -Atc "$3"
    ' sh "$secret" "$role" "$sql"
}
app_secret='sharewise.database.app-password'
migrator_secret='sharewise.database.migrator-password'

# Flyway must have applied the schema baseline as the migrator.
installed_by="$("${compose[@]}" exec -T postgres psql -X -U postgres -d sharewise \
    -Atc "SELECT installed_by FROM flyway.flyway_schema_history WHERE success AND version IS NOT NULL LIMIT 1")"
[[ "$installed_by" == 'sharewise_migrator' ]] || {
    echo "Flyway did not run with the migration role (observed: $installed_by)" >&2
    exit 1
}

# The migrator owns schema evolution, and future tables inherit application DML
# privileges. A smoke marker persists across a PostgreSQL restart, then is
# dropped again by the migrator so the test leaves no business objects behind.
marker='identity.p26ab_compose_smoke_marker'
cleanup_marker() {
    query sharewise_migrator "$migrator_secret" "DROP TABLE IF EXISTS $marker" >/dev/null 2>&1 || true
}
trap cleanup_marker EXIT
query sharewise_migrator "$migrator_secret" \
    "CREATE TABLE IF NOT EXISTS $marker (id INTEGER PRIMARY KEY, value TEXT NOT NULL)" >/dev/null
query sharewise_app "$app_secret" \
    "INSERT INTO $marker (id, value) VALUES (1, 'persisted') ON CONFLICT (id) DO UPDATE SET value=EXCLUDED.value" >/dev/null

# Expected failures must be real SQL permission denials, not connection errors.
query sharewise_app "$app_secret" 'CREATE TABLE identity.p26ab_forbidden (id INTEGER)' >/dev/null 2>&1 && {
    echo 'The application role unexpectedly has schema DDL permission' >&2
    exit 1
}
query sharewise_app "$app_secret" 'SELECT * FROM flyway.flyway_schema_history' >/dev/null 2>&1 && {
    echo 'The application role unexpectedly has Flyway-history access' >&2
    exit 1
}

# Restart only PostgreSQL, preserving the volume and all application data.
"${compose[@]}" restart postgres >/dev/null
for attempt in $(seq 1 30); do
    if [[ "$(query sharewise_app "$app_secret" "SELECT value FROM $marker WHERE id=1" 2>/dev/null || true)" == persisted ]]; then
        break
    fi
    if [[ "$attempt" == 30 ]]; then
        echo 'Data did not survive the PostgreSQL restart' >&2
        exit 1
    fi
    sleep 2
done
# Explicitly verify the same-origin API recovered after the DB restart.
for attempt in $(seq 1 30); do
    if "${compose[@]}" exec -T backend curl --fail --silent --max-time 5 \
        http://127.0.0.1:8080/api/system/v1/health >/dev/null 2>&1; then
        break
    fi
    if [[ "$attempt" == 30 ]]; then
        echo 'Backend did not become ready after the PostgreSQL restart' >&2
        exit 1
    fi
    sleep 2
done

cleanup_marker
trap - EXIT
printf 'Production Compose bootstrap, permissions, HTTPS API, and persistent restart checks passed.\n'
printf 'Test project %s remains running with its persistent volume; clean it up explicitly when finished.\n' "$project"
