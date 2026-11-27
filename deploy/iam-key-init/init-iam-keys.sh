#!/bin/sh
# Compatibility entrypoint for the existing IAM volume/configuration contract.
set -eu
export JWT_KEY_AUTHORITY=iam
export JWT_PRIVATE_DIR="${IAM_PRIVATE_DIR:-/keys/private}"
export JWT_PUBLIC_DIR="${IAM_PUBLIC_DIR:-/keys/public}"
export JWT_KEY_ID="${IAM_KEY_ID:?IAM_KEY_ID must be set explicitly}"
export JWT_KEY_MODE="${IAM_KEY_MODE:-require-existing}"
exec sh "$(dirname "$0")/init-jwt-keys.sh"
