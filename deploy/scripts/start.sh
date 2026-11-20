#!/usr/bin/env bash
# Start the local Compose deployment, importing files only if unchanged/absent.
set -euo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/scripts/lib-compose.sh"
"$deploy_dir/scripts/import-assets.sh"
"${compose[@]}" up --build --wait -d
"${compose[@]}" ps
