#!/usr/bin/env bash
# Shared invocation for deployment scripts. Plain `docker compose` works inside deploy/.
[[ -f "$deploy_dir/.env" ]] || {
    printf 'Missing %s/.env; run ./deploy/scripts/bootstrap.sh first.\n' "$deploy_dir" >&2
    exit 1
}
container_cli="${CONTAINER_CLI:-docker}"
command -v "$container_cli" >/dev/null || { echo "Missing container CLI: $container_cli" >&2; exit 1; }
cd "$deploy_dir"
compose=("$container_cli" compose)
