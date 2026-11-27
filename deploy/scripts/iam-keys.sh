#!/usr/bin/env bash
# Compatibility entrypoint for the IAM authority.
set -euo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/jwt-keys.sh" iam "$@"
