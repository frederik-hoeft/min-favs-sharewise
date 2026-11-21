#!/usr/bin/env bash
# Import local TLS and database credentials over the Docker API, without bind mounts.
# Reimporting identical files is safe; changing populated volumes is deliberately rejected.
set -euo pipefail

deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$deploy_dir/scripts/lib-compose.sh"
local_dir="${SHAREWISE_LOCAL_INPUT_DIR:-$deploy_dir/.local}"

for tool in tar openssl; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
for name in postgres_admin_password sharewise.database.app-password sharewise.database.migrator-password; do
    file="$local_dir/secrets/$name"
    [[ -f "$file" && -s "$file" && ! -L "$file" ]] || { echo "Missing or invalid secret: $file" >&2; exit 1; }
    [[ "$(wc -c < "$file")" -eq 64 ]] && LC_ALL=C grep -Eq '^[0123456789abcdef]{64}$' "$file" || {
        echo "Invalid secret format: $name (expected exactly 64 lowercase hex bytes)" >&2; exit 1;
    }
done
[[ "$(cat "$local_dir/secrets/sharewise.database.app-password")" != "$(cat "$local_dir/secrets/sharewise.database.migrator-password")" ]] || {
    echo 'Application and migration credentials must differ' >&2; exit 1;
}
for name in fullchain.pem privkey.pem; do
    file="$local_dir/tls/$name"
    [[ -f "$file" && -s "$file" && ! -L "$file" ]] || { echo "Missing TLS file: $file" >&2; exit 1; }
done
openssl x509 -in "$local_dir/tls/fullchain.pem" -noout >/dev/null
openssl pkey -in "$local_dir/tls/privkey.pem" -noout -passin pass: >/dev/null
[[ "$(openssl x509 -in "$local_dir/tls/fullchain.pem" -pubkey -noout)" == \
   "$(openssl pkey -in "$local_dir/tls/privkey.pem" -pubout -passin pass:)" ]] || {
    echo 'TLS certificate and private key do not match' >&2; exit 1;
}

"${compose[@]}" config -q
# The client creates the archive and streams it via Docker stdin. All paths on
# the daemon side are named volumes, even with a remote/DooD Docker daemon.
# Never put credential bytes into shell arguments, image layers, or job logs.
(
    cd "$local_dir"
    tar -cf - secrets/postgres_admin_password \
        secrets/sharewise.database.app-password \
        secrets/sharewise.database.migrator-password \
        tls/fullchain.pem tls/privkey.pem
) | "${compose[@]}" --profile setup run --rm --no-deps -T --user 0:0 \
    --entrypoint sh asset-loader -ec '
        set -eu
        umask 077
        tar -xof - -C /tmp
        # Check all destinations first: no partial credential rotation.
        compare_or_empty() {
            source="$1" destination="$2"
            if [ -e "$destination" ]; then
                cmp -s "$source" "$destination" || {
                    echo "Existing deployment asset differs; refusing implicit rotation: $destination" >&2
                    exit 1
                }
            fi
        }
        for name in postgres_admin_password sharewise.database.app-password sharewise.database.migrator-password; do
            compare_or_empty "/tmp/secrets/$name" "/assets/postgres/$name"
        done
        for name in sharewise.database.app-password sharewise.database.migrator-password; do
            compare_or_empty "/tmp/secrets/$name" "/assets/backend/$name"
        done
        for name in fullchain.pem privkey.pem; do
            compare_or_empty "/tmp/tls/$name" "/assets/tls/$name"
        done
        # The volumes are only mounted into their respective trusted services.
        # Backend UID 10001 and PostgreSQL UID postgres need read access, so the
        # credential files are 0444 *inside Docker-private named volumes only*.
        for name in postgres_admin_password sharewise.database.app-password sharewise.database.migrator-password; do
            install -m 0444 "/tmp/secrets/$name" "/assets/postgres/$name"
        done
        for name in sharewise.database.app-password sharewise.database.migrator-password; do
            install -m 0444 "/tmp/secrets/$name" "/assets/backend/$name"
        done
        install -m 0644 /tmp/tls/fullchain.pem /assets/tls/fullchain.pem
        install -m 0600 /tmp/tls/privkey.pem /assets/tls/privkey.pem
    '
printf 'Imported local TLS and secrets into Docker-managed volumes.\n'
