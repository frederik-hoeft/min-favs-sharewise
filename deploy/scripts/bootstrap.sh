#!/usr/bin/env bash
# Local fixture generation, volume import, image build, and IAM key initialization.
# Safe to repeat: only a temporary key-init job runs; application services stay stopped.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$deploy_dir/scripts/prepare-local.sh"
"$deploy_dir/scripts/import-assets.sh"
source "$deploy_dir/scripts/lib-compose.sh"
"${compose[@]}" build
"$deploy_dir/scripts/iam-keys.sh" init
printf 'Local deployment prepared. Run `cd deploy && docker compose up -d`.\n'
