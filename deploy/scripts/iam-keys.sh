#!/usr/bin/env bash
# Explicit Compose job for key provisioning and public-only export.
# Both commands operate on Docker volumes, also with a remote Docker daemon.
set -euo pipefail

usage() {
    printf 'Usage: deploy/scripts/iam-keys.sh <init|export>\n' >&2
    exit 2
}
[[ $# -eq 1 ]] || usage
mode="$1"
[[ "$mode" == init || "$mode" == export ]] || usage

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/scripts/lib-compose.sh"
case "$mode" in
    init)
        "${compose[@]}" run --rm --no-deps -T iam-key-init
        ;;
    export)
        # The export container mounts ONLY the public-key volume.
        # stdout contains just the PEM; redirect it to a file on the host.
        "${compose[@]}" --profile setup run --rm --no-deps -T iam-public-export
        ;;
esac
