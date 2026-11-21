#!/usr/bin/env bash
# One-time local fixture generation, Docker volume import and image build.
# Safe to repeat: this never starts services, deletes volumes or rotates secrets.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$deploy_dir/scripts/prepare-local.sh"
"$deploy_dir/scripts/import-assets.sh"
source "$deploy_dir/scripts/lib-compose.sh"
"${compose[@]}" build
printf 'Local deployment prepared. Run `cd deploy && docker compose up -d`.\n'
