#!/bin/sh
# Executed by the official Nginx entrypoint before its envsubst template step.
set -eu

fail() {
    printf 'ShareWise Nginx configuration: %s\n' "$1" >&2
    exit 1
}

: "${SHAREWISE_PUBLIC_HOST:?SHAREWISE_PUBLIC_HOST is required}"
case "$SHAREWISE_PUBLIC_HOST" in
    *[!a-zA-Z0-9.-]* | .* | *..* | *.) fail 'invalid SHAREWISE_PUBLIC_HOST' ;;
esac
case "$SHAREWISE_PUBLIC_HOST" in
    -* | *.-* | *-. | *-) fail 'invalid SHAREWISE_PUBLIC_HOST' ;;
esac

# Nginx normalizes the HTTP Host header to lowercase before the comparison.
SHAREWISE_PUBLIC_HOST="$(printf '%s' "$SHAREWISE_PUBLIC_HOST" | tr '[:upper:]' '[:lower:]')"

SHAREWISE_HTTPS_PORT="${SHAREWISE_HTTPS_PORT:-443}"
case "$SHAREWISE_HTTPS_PORT" in
    '' | *[!0-9]*) fail 'SHAREWISE_HTTPS_PORT must be a decimal port number' ;;
esac
[ "${#SHAREWISE_HTTPS_PORT}" -le 5 ] || fail 'SHAREWISE_HTTPS_PORT is out of range'
[ "$SHAREWISE_HTTPS_PORT" -ge 1 ] && [ "$SHAREWISE_HTTPS_PORT" -le 65535 ] || fail 'SHAREWISE_HTTPS_PORT is out of range'

SHAREWISE_TLS_PORT_SUFFIX=""
if [ "$SHAREWISE_HTTPS_PORT" -ne 443 ]; then
    SHAREWISE_TLS_PORT_SUFFIX=":$SHAREWISE_HTTPS_PORT"
fi

for path in /etc/nginx/tls/fullchain.pem /etc/nginx/tls/privkey.pem; do
    [ -s "$path" ] && [ -r "$path" ] || fail "missing/unreadable TLS file: $path"
done

export SHAREWISE_PUBLIC_HOST SHAREWISE_HTTPS_PORT SHAREWISE_TLS_PORT_SUFFIX
