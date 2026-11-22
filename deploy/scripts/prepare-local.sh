#!/usr/bin/env bash
# Idempotent, local-only provisioning. Never silently replace existing secrets.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_dir="$deploy_dir/.local"
secrets="$local_dir/secrets"
tls="$local_dir/tls"

command -v openssl >/dev/null || { echo 'Missing required tool: openssl' >&2; exit 1; }

# Native Compose loads deploy/.env automatically when invoked in deploy/.
# An existing local file (including legacy configurations) is never overwritten.
if [[ ! -e "$deploy_dir/.env" && ! -L "$deploy_dir/.env" ]]; then
    umask 077
    if [[ -f "$local_dir/compose.env" && ! -L "$local_dir/compose.env" ]]; then
        cp "$local_dir/compose.env" "$deploy_dir/.env"
        echo 'Imported legacy .local/compose.env into deploy/.env; previous file retained.'
    else
        cp "$deploy_dir/.env.example" "$deploy_dir/.env"
        if [[ -n "${SHAREWISE_LOCAL_HOST:-}" ]]; then
            [[ "$SHAREWISE_LOCAL_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ &&
               "$SHAREWISE_LOCAL_HOST" != *..* && "$SHAREWISE_LOCAL_HOST" != *.-* &&
               "$SHAREWISE_LOCAL_HOST" != *-. && "$SHAREWISE_LOCAL_HOST" != *. ]] || {
                echo 'Invalid SHAREWISE_LOCAL_HOST' >&2; exit 1;
            }
            sed -i "s/^SHAREWISE_PUBLIC_HOST=.*/SHAREWISE_PUBLIC_HOST=$SHAREWISE_LOCAL_HOST/" "$deploy_dir/.env"
        fi
        echo 'Created deploy/.env from .env.example.'
    fi
    chmod 0600 "$deploy_dir/.env"
fi
[[ -f "$deploy_dir/.env" && ! -L "$deploy_dir/.env" ]] || {
    echo 'deploy/.env must be a regular file (not a symlink)' >&2; exit 1;
}
host="$(sed -n 's/^SHAREWISE_PUBLIC_HOST=//p' "$deploy_dir/.env")"
[[ "$host" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ && "$host" != *..* && "$host" != *.-* && "$host" != *-. && "$host" != *. ]] || {
    echo 'Invalid or missing SHAREWISE_PUBLIC_HOST in deploy/.env' >&2; exit 1;
}
if [[ -n "${SHAREWISE_LOCAL_HOST:-}" && "${SHAREWISE_LOCAL_HOST}" != "$host" ]]; then
    echo 'SHAREWISE_LOCAL_HOST differs from deploy/.env; edit deploy/.env explicitly.' >&2
    exit 1
fi
if [[ -n "${SHAREWISE_PUBLIC_HOST:-}" && "${SHAREWISE_PUBLIC_HOST}" != "$host" ]]; then
    echo 'Exported SHAREWISE_PUBLIC_HOST differs from deploy/.env; unset it or align the configuration.' >&2
    exit 1
fi

umask 077
mkdir -p "$secrets" "$tls"
chmod 0700 "$local_dir" "$secrets" "$tls"
names=(postgres_admin_password sharewise.database.app-password sharewise.database.migrator-password)
secret_present=0
for name in "${names[@]}"; do
    [[ ! -e "$secrets/$name" && ! -L "$secrets/$name" ]] || ((secret_present+=1))
done
if (( secret_present != 0 && secret_present != ${#names[@]} )); then
    echo 'Partial local password fixture; restore the missing secrets or use cleanup.sh local to reset the entire disposable stack.' >&2
    exit 1
fi
if (( secret_present == 0 )); then
    for name in "${names[@]}"; do
        openssl rand -hex 32 | tr -d '\n' > "$secrets/$name"
        chmod 0600 "$secrets/$name"
    done
    echo 'Generated local database credentials.'
else
    echo 'Preserving existing local database credentials.'
fi
cert_count=0
for name in fullchain.pem privkey.pem; do
    [[ ! -e "$tls/$name" && ! -L "$tls/$name" ]] || ((cert_count+=1))
done
if (( cert_count == 1 )); then
    echo 'Partial local TLS fixture; restore both files or run cleanup.sh tls to replace only the certificate.' >&2
    exit 1
fi
if (( cert_count == 0 )); then
    sans="DNS:$host"
    if [[ "$host" == localhost ]]; then
        sans+=",IP:127.0.0.1"
    fi
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
        -subj "/CN=$host" -addext "subjectAltName=$sans" \
        -keyout "$tls/privkey.pem" -out "$tls/fullchain.pem" >/dev/null 2>&1
    chmod 0600 "$tls/privkey.pem"
    chmod 0644 "$tls/fullchain.pem"
    echo 'Generated 365-day local self-signed TLS certificate.'
else
    echo 'Preserving existing local certificate.'
fi
# Detect stale legacy hostnames *before* attempting Docker volume import.
if ! openssl x509 -in "$tls/fullchain.pem" -noout -checkhost "$host" >/dev/null 2>&1; then
    echo "TLS certificate does not cover SHAREWISE_PUBLIC_HOST=$host. Run deploy/scripts/cleanup.sh tls, then bootstrap again." >&2
    exit 1
fi
if ! openssl x509 -in "$tls/fullchain.pem" -checkend 0 -noout >/dev/null 2>&1; then
    echo 'TLS certificate has expired; use cleanup.sh tls to regenerate it without resetting the database.' >&2
    exit 1
fi
