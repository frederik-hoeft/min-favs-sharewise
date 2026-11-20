#!/usr/bin/env bash
# Create a disposable production-style Compose fixture. Never overwrite secrets.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_dir="$deploy_dir/.local"
secrets="$local_dir/secrets"
tls="$local_dir/tls"
host="${SHAREWISE_LOCAL_HOST:-sharewise.test}"

if [[ ! "$host" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]]; then
    echo 'Invalid SHAREWISE_LOCAL_HOST' >&2
    exit 1
fi
if [[ -e "$local_dir/compose.env" || -e "$secrets/postgres_admin_password" || \
      -e "$secrets/sharewise.database.app-password" || \
      -e "$secrets/sharewise.database.migrator-password" || \
      -e "$tls/fullchain.pem" || -e "$tls/privkey.pem" ]]; then
    echo 'Local production fixture already exists; refusing to replace credentials or certificates.' >&2
    exit 1
fi
umask 077
mkdir -p "$secrets" "$tls"
chmod 700 "$local_dir" "$secrets" "$tls"
for name in postgres_admin_password sharewise.database.app-password sharewise.database.migrator-password; do
    # No trailing newline: Spring config-tree values are exact password strings.
    openssl rand -hex 32 | tr -d '\n' > "$secrets/$name"
    # Keep original local files private. The importer copies them into separate
    # Docker-managed named volumes with service-specific access.
    chmod 0600 "$secrets/$name"
done
openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
    -subj "/CN=$host" -addext "subjectAltName=DNS:$host" \
    -keyout "$tls/privkey.pem" -out "$tls/fullchain.pem" >/dev/null 2>&1
chmod 0600 "$tls/privkey.pem"
chmod 0644 "$tls/fullchain.pem"
# Keep the Compose configuration local, even though it has no passwords.
cat > "$local_dir/compose.env" <<ENV
COMPOSE_PROJECT_NAME=sharewise-p26ab-local
SHAREWISE_PUBLIC_HOST=$host
SHAREWISE_HTTP_BIND=127.0.0.1
SHAREWISE_HTTP_PORT=8080
SHAREWISE_HTTPS_BIND=127.0.0.1
SHAREWISE_HTTPS_PORT=8443
ENV
chmod 0600 "$local_dir/compose.env"
printf 'Generated disposable local files under %s\n' "$local_dir"
printf 'Certificate hostname: %s (self-signed; local acceptance only)\n' "$host"
