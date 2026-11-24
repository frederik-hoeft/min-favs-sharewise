#!/usr/bin/env bash
# Explicit, scoped cleanup for disposable local deployments. Never a normal shutdown.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
container_cli="${CONTAINER_CLI:-docker}"
usage() {
    cat <<'USAGE'
Usage: deploy/scripts/cleanup.sh <tls|test|tests|local> [test-project] [--yes]

  tls            Stop the local stack and remove only its imported TLS volume
                 and generated local certificate files. Preserve DB + passwords.
  test [name]    Destroy one disposable acceptance project and its volumes.
                 Default: sharewise-p26ab-smoke.
  tests          Destroy ALL discovered sharewise-p26ab-smoke* projects/volumes.
  local          Destroy the local stack, all its volumes, and deploy/.env +
                 deploy/.local. Deletes local PostgreSQL data and credentials!

All operations require interactive confirmation or --yes.
Ordinary shutdown: cd deploy && docker compose down (preserves volumes).
USAGE
}
[[ $# -ge 1 ]] || { usage >&2; exit 2; }
mode="$1"; shift
project_arg=""
yes=false
for arg in "$@"; do
    if [[ "$arg" == --yes ]]; then
        yes=true
    elif [[ -z "$project_arg" ]]; then
        project_arg="$arg"
    else
        usage >&2; exit 2
    fi
done
case "$mode" in tls|local|test|tests) ;; *) usage >&2; exit 2 ;; esac
if [[ "$mode" != test && -n "$project_arg" ]]; then
    echo 'A project name is accepted only in test mode.' >&2; exit 2
fi
command -v "$container_cli" >/dev/null || { echo "Missing container CLI: $container_cli" >&2; exit 1; }
[[ -f "$deploy_dir/.env" && ! -L "$deploy_dir/.env" ]] || {
    echo 'Missing deploy/.env; use bootstrap.sh to restore it before cleanup.' >&2; exit 1;
}
local_project="$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$deploy_dir/.env")"
[[ "$local_project" =~ ^sharewise-[a-z0-9_-]+$ ]] || {
    echo 'Invalid local Compose project name in deploy/.env.' >&2; exit 1;
}
if [[ "$mode" == local || "$mode" == tls ]] &&
   [[ -n "${COMPOSE_PROJECT_NAME:-}" && "$COMPOSE_PROJECT_NAME" != "$local_project" ]]; then
    echo 'Exported COMPOSE_PROJECT_NAME conflicts with deploy/.env; unset it before cleanup.' >&2
    exit 1
fi
cd "$deploy_dir"
# Disposable test projects use the base topology even when the local stack opts
# into host-published PostgreSQL through a Compose override.
if [[ "$mode" == test || "$mode" == tests ]]; then
    export COMPOSE_FILE=compose.yaml
fi
compose=("$container_cli" compose)
confirm() {
    local label="$1" answer
    printf 'Destructive operation: %s\n' "$label" >&2
    if [[ "$yes" == true ]]; then
        return 0
    fi
    if [[ ! -t 0 ]]; then
        echo 'Refusing non-interactive cleanup without --yes.' >&2
        exit 2
    fi
    read -r -p 'Type DELETE to proceed: ' answer
    [[ "$answer" == DELETE ]] || { echo 'Cancelled.' >&2; exit 2; }
}

is_test_project() {
    [[ "$1" =~ ^sharewise-p26ab-smoke[a-z0-9_-]*$ && "$1" != "$local_project" ]]
}

cleanup_test() {
    local project="$1"
    is_test_project "$project" || { echo "Refusing non-test project: $project" >&2; exit 2; }
    printf 'Removing disposable Compose project: %s\n' "$project"
    "${compose[@]}" -p "$project" down --volumes --remove-orphans
    # Some old projects may have orphaned volumes no longer declared in Compose.
    # Only remove volumes explicitly labeled as belonging to this exact test project.
    local volume
    while IFS= read -r volume; do
        [[ -n "$volume" ]] || continue
        "$container_cli" volume rm "$volume"
    done < <("$container_cli" volume ls -q --filter "label=com.docker.compose.project=$project")
}

case "$mode" in
    tls)
        confirm "local project $local_project: stop services, delete TLS volume and local certificate files; keep database and passwords"
        "${compose[@]}" -p "$local_project" down
        volume="${local_project}_tls_certs"
        if "$container_cli" volume inspect "$volume" >/dev/null 2>&1; then
            "$container_cli" volume rm "$volume"
        fi
        rm -f -- "$deploy_dir/.local/tls/fullchain.pem" "$deploy_dir/.local/tls/privkey.pem"
        echo 'TLS reset. Set SHAREWISE_PUBLIC_HOST in deploy/.env, then run ./scripts/bootstrap.sh and docker compose up -d.'
        ;;
    local)
        confirm "local project $local_project: DELETE ALL database and asset volumes, credentials, certificate and local configuration"
        "${compose[@]}" -p "$local_project" down --volumes --remove-orphans
        rm -rf -- "$deploy_dir/.local"
        rm -f -- "$deploy_dir/.env"
        echo 'Local deployment reset. Run ./scripts/bootstrap.sh to create a new empty stack.'
        ;;
    test)
        project="${project_arg:-sharewise-p26ab-smoke}"
        is_test_project "$project" || { echo "Refusing non-test project: $project" >&2; exit 2; }
        confirm "test project $project: DELETE its containers, networks, and data/asset volumes"
        cleanup_test "$project"
        ;;
    tests)
        projects=()
        # Find orphaned test projects from every Docker-owned object class.
        # Filter to the reserved test prefix and never touch normal local state.
        while IFS= read -r candidate; do
            is_test_project "$candidate" && projects+=("$candidate")
        done < <(
            {
                "$container_cli" ps -a --format '{{.Label "com.docker.compose.project"}}'
                for type in volume network; do
                    while IFS= read -r object; do
                        [[ -n "$object" ]] || continue
                        "$container_cli" "$type" inspect -f '{{ index .Labels "com.docker.compose.project" }}' "$object"
                    done < <("$container_cli" "$type" ls -q --filter label=com.docker.compose.project)
                done
            } | sort -u
        )
        if (( ${#projects[@]} == 0 )); then
            echo 'No disposable ShareWise test projects found.'
            exit 0
        fi
        confirm "${#projects[@]} disposable test projects: ${projects[*]} (including their data volumes)"
        for project in "${projects[@]}"; do
            cleanup_test "$project"
        done
        ;;
esac
