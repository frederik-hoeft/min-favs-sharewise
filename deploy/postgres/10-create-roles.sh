#!/bin/sh
# Executed once by the official PostgreSQL entrypoint on an EMPTY data volume.
# Never reuse the development bootstrap SQL or print credentials to logs.
set -eu

read_hex_secret() {
    secret_path="$1"
    [ -f "$secret_path" ] && [ -r "$secret_path" ] || {
        printf 'PostgreSQL initialization: required role secret is missing or unreadable: %s\n' "$secret_path" >&2
        exit 1
    }
    # Check the raw byte length before shell command substitution, which would
    # otherwise silently remove trailing newlines. PostgreSQL and Spring must
    # interpret exactly the same file contents as the role password.
    byte_count="$(wc -c < "$secret_path")"
    [ "$byte_count" -ge 64 ] && [ "$byte_count" -le 128 ] || {
        echo 'PostgreSQL initialization: role secrets must be 64 to 128 bytes' >&2
        exit 1
    }
    secret="$(cat "$secret_path")"
    [ "${#secret}" -eq "$byte_count" ] || {
        echo 'PostgreSQL initialization: role secrets must not contain newlines or binary data' >&2
        exit 1
    }
    # A restricted alphabet prevents SQL injection and allows safe SQL literal
    # interpolation without passing passwords as process arguments or printing
    # their contents. Local fixture generation uses 32 random bytes as hex.
    case "$secret" in
        ''|*[!0123456789abcdef]*)
            echo 'PostgreSQL initialization: role secrets must be lowercase hexadecimal' >&2
            exit 1 ;;
    esac
    [ "${#secret}" -ge 64 ] && [ "${#secret}" -le 128 ] || {
        echo 'PostgreSQL initialization: role secrets must contain 64 to 128 hexadecimal characters' >&2
        exit 1
    }
    printf '%s' "$secret"
}

app_password="$(read_hex_secret /run/secrets/sharewise.database.app-password)"
migrator_password="$(read_hex_secret /run/secrets/sharewise.database.migrator-password)"
[ "$app_password" != "$migrator_password" ] || {
    echo 'PostgreSQL initialization: application and migrator credentials must differ' >&2
    exit 1
}

# Both roles are explicitly non-superusers, and only the migrator may create
# schemas/objects. Migrations grant the runtime role only context DML.
# ON_ERROR_STOP makes a failed role/grant statement fail the init container.
psql -X -q -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;
CREATE ROLE sharewise_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$migrator_password';
CREATE ROLE sharewise_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$app_password';
GRANT CONNECT, CREATE ON DATABASE "$POSTGRES_DB" TO sharewise_migrator;
GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO sharewise_app;
SQL
unset app_password migrator_password
