#!/bin/sh
# Create or validate one ES256 signing pair in independent Docker volumes.
# Never export private material or silently rotate an existing signing identity.
set -eu
umask 077

authority="${JWT_KEY_AUTHORITY:?JWT_KEY_AUTHORITY must be set}"
case "$authority" in
    iam) label=IAM ;;
    groups) label=Group ;;
    *) echo 'Unsupported key authority' >&2; exit 1 ;;
esac
private_dir="${JWT_PRIVATE_DIR:-/keys/private}"
public_dir="${JWT_PUBLIC_DIR:-/keys/public}"
key_id="${JWT_KEY_ID:?JWT_KEY_ID must be set explicitly}"
mode="${JWT_KEY_MODE:-require-existing}"

case "$key_id" in
    *[!a-zA-Z0-9._-]* | '' | [!a-zA-Z0-9]* | *..*)
        echo "Invalid ${label}_KEY_ID (use a stable alphanumeric identifier with ._- separators)" >&2
        exit 1 ;;
esac
[ "${#key_id}" -le 64 ] || { echo "${label}_KEY_ID exceeds 64 characters" >&2; exit 1; }
case "$mode" in
    generate-if-absent | require-existing) ;;
    *) echo "${label}_KEY_MODE must be generate-if-absent or require-existing" >&2; exit 1 ;;
esac
[ "$private_dir" != "$public_dir" ] || {
    echo "${label} private and public key paths must be different" >&2; exit 1;
}
for dir in "$private_dir" "$public_dir"; do
    [ -d "$dir" ] && [ ! -L "$dir" ] || {
        echo "Missing or unsafe ${label} key volume: $dir" >&2; exit 1;
    }
done
# A volume-local atomic lock prevents concurrent bootstrap/up invocations from
# both deciding that an empty volume needs a new signing identity.
lock_dir="$private_dir/.${authority}-lock"
[ ! -L "$lock_dir" ] && mkdir "$lock_dir" 2>/dev/null || {
    echo "${label} key initialization already running or interrupted; inspect the private volume" >&2
    exit 1
}
stage=""
public_stage=""
cleanup() {
    [ -z "$stage" ] || rm -rf -- "$stage"
    [ -z "$public_stage" ] || rm -rf -- "$public_stage"
    rmdir -- "$lock_dir"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

private_key="$private_dir/active.pem"
private_id="$private_dir/key-id"
public_key="$public_dir/active.pem"
public_id="$public_dir/key-id"

for path in "$private_key" "$private_id" "$public_key" "$public_id"; do
    [ ! -L "$path" ] || { echo "Refusing symlink at $path" >&2; exit 1; }
done

present=0
for path in "$private_key" "$private_id" "$public_key" "$public_id"; do
    if [ -e "$path" ]; then
        [ -f "$path" ] && [ -s "$path" ] || {
            echo "Invalid ${label} key fixture at $path" >&2; exit 1;
        }
        present=$((present + 1))
    fi
done

if [ "$present" -eq 0 ]; then
    # A killed provisioning job can leave private material in a staging dir.
    # Refuse a new identity until the operator has inspected those artifacts.
    for leftover in "$private_dir"/.${authority}-init.* "$public_dir"/.${authority}-public.*; do
        [ ! -e "$leftover" ] && [ ! -L "$leftover" ] || {
            echo "Interrupted ${label} key provisioning left staging artifacts; refusing regeneration" >&2
            exit 1
        }
    done
    [ "$mode" = generate-if-absent ] || {
        echo "${label} signing keys absent; generation not authorized (${label}_KEY_MODE=require-existing)" >&2
        exit 1
    }
    # Private material is generated and staged *only* on the private volume.
    # A crash during provisioning leaves an incomplete fixture that fails closed.
    stage="$(mktemp -d "$private_dir/.${authority}-init.XXXXXXXX")"
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 \
        -out "$stage/private.pem" >/dev/null 2>&1
    openssl pkey -in "$stage/private.pem" -pubout -out "$stage/public.pem" >/dev/null 2>&1
    printf '%s' "$key_id" > "$stage/key-id"
    # Stage the public files in a new private directory within the *public*
    # volume. Never overwrite predictable temporary paths or follow symlinks.
    public_stage="$(mktemp -d "$public_dir/.${authority}-public.XXXXXXXX")"
    cp "$stage/public.pem" "$public_stage/active.pem"
    cp "$stage/key-id" "$public_stage/key-id"
    mv "$stage/private.pem" "$private_key"
    mv "$stage/key-id" "$private_id"
    mv "$public_stage/active.pem" "$public_key"
    mv "$public_stage/key-id" "$public_id"
    rm -rf -- "$stage" "$public_stage"
    stage=""
    public_stage=""
    echo "Generated a new ES256 ${label} signing identity in Docker volumes." >&2
elif [ "$present" -ne 4 ]; then
    echo "Partial ${label} signing key fixture; restore the original volumes; refusing regeneration" >&2
    exit 1
fi

[ "$(cat "$private_id")" = "$key_id" ] && [ "$(cat "$public_id")" = "$key_id" ] || {
    echo "${label} key ID does not match persisted signing identity; refusing implicit rotation" >&2
    exit 1
}
# The JVM loader expects one unencrypted PKCS#8 PEM block. OpenSSL may
# silently accept the first block in a concatenated PEM that Java rejects.
LC_ALL=C awk '
    NR == 1 { if ($0 != "-----BEGIN PRIVATE KEY-----") exit 1; next }
    $0 == "-----END PRIVATE KEY-----" { if (closed) exit 1; closed = 1; next }
    closed || $0 !~ /^[A-Za-z0-9+\/=]+$/ { exit 1 }
    { body = 1 }
    END { if (!closed || !body) exit 1 }
' "$private_key" || {
    echo "${label} signing key must contain exactly one unencrypted PKCS#8 PRIVATE KEY PEM block" >&2
    exit 1
}
# Verification uses a pipe; derived public material is never copied out of the private volume
# except to the explicitly public-only volume at initial provisioning.
openssl pkey -in "$private_key" -noout -text 2>/dev/null | grep -q 'ASN1 OID: prime256v1' || {
    echo "${label} signing key must be an unencrypted EC P-256 private key" >&2; exit 1;
}
openssl pkey -pubin -in "$public_key" -noout >/dev/null 2>&1 || {
    echo "Invalid ${label} public key" >&2; exit 1;
}
openssl pkey -in "$private_key" -pubout 2>/dev/null | cmp -s - "$public_key" || {
    echo "${label} private and public keys do not match; refusing startup" >&2; exit 1;
}
# Backend image runs as UID/GID 10001. Root owns the volume and key files;
# backend gets only group read/traverse access, never write access.
chown 0:10001 "$private_dir" "$private_key" "$private_id"
chmod 0750 "$private_dir"
chmod 0440 "$private_key" "$private_id"
chown 0:0 "$public_dir" "$public_key" "$public_id"
chmod 0755 "$public_dir"
chmod 0444 "$public_key" "$public_id"
printf "${label} signing identity ready (kid=%s); private key retained in private volume.\n" "$key_id" >&2
