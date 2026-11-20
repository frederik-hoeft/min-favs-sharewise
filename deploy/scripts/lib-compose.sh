#!/usr/bin/env bash
# Shared Compose invocation; source from a script after setting deploy_dir.
# The Docker daemon may be remote, but the Compose configuration is local.
env_file="${SHAREWISE_COMPOSE_ENV_FILE:-$deploy_dir/.local/compose.env}"
[[ -f "$env_file" ]] || { echo "Missing Compose environment file: $env_file" >&2; exit 1; }
container_cli="${CONTAINER_CLI:-docker}"
command -v "$container_cli" >/dev/null || { echo "Missing container CLI: $container_cli" >&2; exit 1; }
compose=("$container_cli" compose --env-file "$env_file" -f "$deploy_dir/compose.yaml")
if [[ -n "${SHAREWISE_COMPOSE_PROJECT:-}" ]]; then
    compose+=(-p "$SHAREWISE_COMPOSE_PROJECT")
fi
