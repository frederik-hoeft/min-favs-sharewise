#!/usr/bin/env python3
"""Disposable IAM and Group lifecycle smoke through the real HTTPS Compose edge.

Requires Python stdlib, OpenSSL and Docker/Compose; never prints credentials,
cookie values, access JWTs or invitation tokens. Called by test-compose.sh after the stack is ready.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import uuid


REFRESH_COOKIE = "__Host-sharewise-refresh"


def compose_exec(*args: str, input_body: str | None = None, service: str = "backend") -> str:
    cli = os.environ.get("CONTAINER_CLI", "docker")
    result = subprocess.run(
        [cli, "compose", "exec", "-T", service, *args],
        input=input_body, text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        # Do not propagate curl/HTTP debug output: it may contain secrets.
        raise RuntimeError(f"Compose smoke command failed in {service}")
    return result.stdout


def edge_options(host: str, port: str) -> list[str]:
    return ["curl", "-q", "--silent", "--show-error", "--insecure", "--max-time", "20",
            "--connect-to", f"{host}:{port}:frontend:443", "--write-out", "\\n%{http_code}"]


def curl(host: str, port: str, cookie_file: str, path: str,
         origin: str | None = None, payload: str | None = None,
         csrf: str | None = None) -> tuple[int, dict | None]:
    url = f"https://{host}:{port}/api/iam/v1/auth/{path}"
    options = edge_options(host, port) + ["--cookie", cookie_file, "--cookie-jar", cookie_file]
    if payload is not None:
        options += ["-X", "POST", "-H", "Content-Type: application/json",
                    "-H", f"Origin: {origin}", "-H", f"X-XSRF-TOKEN: {csrf}",
                    "--data-binary", "@-"]
    return json_request(options, url, payload)


def json_request(options: list[str], url: str, input_body: str | None) -> tuple[int, dict | None]:
    output = compose_exec(*options, url, input_body=input_body)
    body, separator, status = output.rpartition("\n")
    if not separator or not re.fullmatch(r"[1-5][0-9]{2}", status):
        raise RuntimeError("Smoke API returned an invalid HTTP status")
    if not body.strip():
        return int(status), None
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        raise RuntimeError("Smoke API returned an invalid JSON response") from None
    if not isinstance(parsed, dict):
        raise RuntimeError("Smoke API returned a non-object JSON response")
    return int(status), parsed


def csrf_token(host: str, port: str, cookie_file: str) -> str:
    status, response = curl(host, port, cookie_file, "csrf")
    if status != 200 or not isinstance(response, dict) or not isinstance(response.get("token"), str) or not response["token"]:
        raise RuntimeError("IAM CSRF bootstrap failed")
    return response["token"]


def mutate(host: str, port: str, cookie_file: str, operation: str,
           origin: str, body: dict) -> tuple[int, dict | None]:
    # Credential mutations clear the CSRF cookie, so bootstrap before each one.
    csrf = csrf_token(host, port, cookie_file)
    return curl(host, port, cookie_file, operation, origin=origin,
                payload=json.dumps(body), csrf=csrf)


def refresh_cookie(cookie_file: str) -> str | None:
    """Inspect curl's Netscape cookie jar in the backend; never log its secret."""
    matches = []
    for line in compose_exec("cat", cookie_file).splitlines():
        if line.startswith("#") and not line.startswith("#HttpOnly_"):
            continue
        fields = line.split("\t")
        if len(fields) != 7 or fields[5] != REFRESH_COOKIE:
            continue
        # __Host- must be secure, host-only, HttpOnly and scoped to /.
        if (not fields[0].startswith("#HttpOnly_") or fields[1] != "FALSE"
                or fields[2] != "/" or fields[3] != "TRUE"
                or not fields[6]):
            raise RuntimeError("IAM refresh cookie attributes are invalid")
        matches.append(fields[6])
    if len(matches) > 1:
        raise RuntimeError("IAM returned duplicate refresh cookies")
    return matches[0] if matches else None


def der_integer(integer: bytes) -> bytes:
    value = integer.lstrip(b"\x00") or b"\x00"
    if value[0] & 0x80:
        value = b"\x00" + value
    return b"\x02" + bytes([len(value)]) + value


def verify_es256(jwt: str, public_pem: str, issuer: str, audience: str) -> dict:
    pieces = jwt.split(".")
    if len(pieces) != 3:
        raise RuntimeError("IAM returned a noncompact JWT")

    def decode(value: str) -> bytes:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))

    header = json.loads(decode(pieces[0]))
    claims = json.loads(decode(pieces[1]))
    signature = decode(pieces[2])
    if header.get("alg") != "ES256" or header.get("typ") != "sw-iam-access+jwt":
        raise RuntimeError("Unexpected IAM JWT algorithm/type")
    if claims.get("iss") != issuer or audience not in ([claims.get("aud")] if isinstance(claims.get("aud"), str)
                                               else claims.get("aud", [])):
        raise RuntimeError("IAM JWT issuer/audience mismatch")
    if not isinstance(claims.get("exp"), int) or claims["exp"] <= time.time():
        raise RuntimeError("IAM JWT is already expired")
    if len(signature) != 64:
        raise RuntimeError("Expected ES256 raw signature")
    signature_der = der_integer(signature[:32]) + der_integer(signature[32:])
    signature_der = b"\x30" + bytes([len(signature_der)]) + signature_der
    with tempfile.TemporaryDirectory(prefix="sharewise-iam-verify-") as dirname:
        directory = Path(dirname)
        (directory / "public.pem").write_text(public_pem)
        (directory / "signed").write_bytes(f"{pieces[0]}.{pieces[1]}".encode("ascii"))
        (directory / "signature").write_bytes(signature_der)
        result = subprocess.run([
            "openssl", "dgst", "-sha256", "-verify", str(directory / "public.pem"),
            "-signature", str(directory / "signature"), str(directory / "signed"),
        ], capture_output=True, check=False)
        if result.returncode != 0:
            raise RuntimeError("IAM JWT signature does not verify against exported public key")
    return claims


def authenticated_response(response: dict | None, public_pem: str,
                           issuer: str, audience: str) -> tuple[str, str, str]:
    if not isinstance(response, dict) or response.get("tokenType") != "Bearer":
        raise RuntimeError("IAM authentication response was unsuccessful")
    token = response.get("accessToken")
    session_id = response.get("sessionId")
    if not isinstance(token, str) or not isinstance(session_id, str):
        raise RuntimeError("IAM authentication response lacked credentials or session identity")
    canonical_uuid(session_id, "IAM session ID")
    claims = verify_es256(token, public_pem, issuer, audience)
    if claims.get("sid") != session_id:
        raise RuntimeError("IAM JWT session binding does not match the authentication response")
    return token, session_id, canonical_uuid(claims.get("sub"), "IAM user ID")


def canonical_uuid(value: object, label: str) -> str:
    if isinstance(value, str):
        try:
            if str(uuid.UUID(value)) == value:
                return value
        except ValueError:
            pass
    raise RuntimeError(f"Invalid {label} in smoke response")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def group_request(host: str, port: str, bearer: str | None, method: str, path: str,
                  payload: dict | None = None) -> tuple[int, dict | None]:
    # curl config on stdin keeps credentials and invitation bodies out of process argv.
    config = "" if bearer is None else f"header = {json.dumps('Authorization: Bearer ' + bearer)}\n"
    if payload is not None:
        config += 'header = "Content-Type: application/json"\n'
        config += f"data-binary = {json.dumps(json.dumps(payload))}\n"
    options = edge_options(host, port) + ["--request", method, "--config", "-"]
    return json_request(options, f"https://{host}:{port}/api/groups/v1{path}", config)


def provision_group_users(*user_ids: str) -> None:
    # Temporary fixture seam until IAM-to-Group provisioning is implemented.
    # Never seed groups, memberships or invitations directly; exercise their APIs.
    values = ", ".join(f"('{canonical_uuid(user_id, 'fixture user ID')}')" for user_id in user_ids)
    compose_exec("sh", "-ec", '''
        PGPASSWORD="$(cat /run/secrets/sharewise.database.app-password)"; export PGPASSWORD
        exec psql -X -h 127.0.0.1 -U sharewise_app -d sharewise -v ON_ERROR_STOP=1 -q
    ''',
                 input_body=f"INSERT INTO groups.users (id) VALUES {values} ON CONFLICT DO NOTHING;\n",
                 service="postgres")


def group_lifecycle(host: str, port: str, owner: str, owner_id: str,
                    guest: str, guest_id: str) -> None:
    def expect(bearer, method, path, status, *, payload=None, code=None):
        actual, body = group_request(host, port, bearer, method, path, payload)
        require(actual == status, f"Group smoke {method} {path}: expected HTTP {status}, got {actual}")
        if code is not None:
            require(body is not None and body.get("code") == code,
                    f"Group smoke {method} {path}: unexpected problem code")
        if status == 204:
            require(body is None, f"Group smoke {method} {path}: expected empty response")
        elif body is None:
            raise RuntimeError(f"Group smoke {method} {path}: missing response body")
        return body

    name = "Compose smoke group"
    created = expect(owner, "POST", "/groups", 201, payload={"name": name})
    group_id = canonical_uuid(created.get("id"), "group ID")
    require(created.get("name") == name and created.get("callerRole") == "admin"
            and created.get("memberCount") == 1, "Group creation did not establish the owner membership")
    path = f"/groups/{group_id}"
    workspace_path = path + "/workspace"
    invitations_path = path + "/invitations"
    mine = expect(owner, "GET", "/groups", 200)
    require(any(item.get("id") == group_id and item.get("callerRole") == "admin"
                for item in mine.get("items", [])), "Created group missing from owner list")
    outsider = expect(guest, "GET", "/groups", 200)
    require(outsider.get("items") == [], "New invitee unexpectedly has groups")
    expect(None, "GET", workspace_path, 401, code="authentication_required")
    expect(guest, "GET", workspace_path, 404, code="group_not_found")

    issued = expect(owner, "POST", invitations_path, 201, payload={"maxUses": 1})
    invitation = issued.get("invitation") or {}
    invitation_id = canonical_uuid(invitation.get("id"), "invitation ID")
    token = issued.get("token")
    require(isinstance(token, str) and bool(token), "Invitation creation did not return a token")
    require(invitation.get("useCount") == 0 and invitation.get("maxUses") == 1,
            "Invitation creation did not establish the usage limit")
    preview = expect(guest, "POST", "/invitations/preview", 200, payload={"token": token})
    require(preview == {"groupName": name}, "Invitation preview returned unexpected group information")
    expect(guest, "GET", workspace_path, 404, code="group_not_found")
    redeemed = expect(guest, "POST", "/invitations/redeem", 200, payload={"token": token})
    require(redeemed == {"groupId": group_id, "outcome": "joined"}, "Invitation did not join the expected group")
    listing = expect(owner, "GET", invitations_path, 200)
    items = listing.get("items")
    require(token not in json.dumps(listing) and isinstance(items, list) and len(items) == 1 and isinstance(items[0], dict)
            and items[0].get("id") == invitation_id and items[0].get("useCount") == 1
            and items[0].get("maxUses") == 1
            and set(items[0]) <= {"id", "createdAt", "expiresAt", "maxUses", "useCount"},
            "Invitation listing did not reflect consumption or exposed unexpected fields")
    expect(guest, "POST", "/invitations/preview", 404, payload={"token": token}, code="group_invitation_unavailable")
    expect(guest, "GET", invitations_path, 403, code="group_admin_required")

    workspace = expect(guest, "GET", workspace_path, 200)
    members = workspace.get("members", [])
    require(workspace.get("group", {}).get("callerRole") == "member"
            and workspace.get("group", {}).get("memberCount") == 2
            and len(members) == 2
            and {member.get("userId"): member.get("role") for member in members} == {owner_id: "admin", guest_id: "member"},
            "Workspace did not expose the expected two memberships")
    guest_membership = canonical_uuid(workspace.get("callerMembershipId"), "invitee membership ID")
    require(any(member.get("id") == guest_membership and member.get("userId") == guest_id for member in members),
            "Workspace caller membership does not match invitee")
    expect(owner, "PUT", path + f"/members/{guest_membership}/role", 204, payload={"role": "admin"})
    # The previously issued guest JWT must immediately gain the new Group role.
    expect(guest, "DELETE", invitations_path + f"/{invitation_id}", 204)
    require(expect(guest, "GET", invitations_path, 200).get("items") == [], "Revoked invitation remains listed")
    owner_workspace = expect(owner, "GET", workspace_path, 200)
    owner_membership = canonical_uuid(owner_workspace.get("callerMembershipId"), "owner membership ID")
    expect(owner, "DELETE", path + f"/members/{owner_membership}", 204)
    expect(owner, "GET", workspace_path, 404, code="group_not_found")
    final = expect(guest, "GET", workspace_path, 200)
    require(final.get("group", {}).get("callerRole") == "admin" and final.get("group", {}).get("memberCount") == 1
            and [(member.get("userId"), member.get("role")) for member in final.get("members", [])] == [(guest_id, "admin")],
            "Group departure did not preserve the remaining administrator")


def exercise_groups(args: argparse.Namespace, origin: str, public: str, owner: str, owner_id: str) -> None:
    cookie_file = compose_exec("mktemp", "/tmp/sharewise-group-cookie.XXXXXXXX").strip()
    try:
        status, response = mutate(args.host, args.port, cookie_file, "register", origin,
                                  {"email": f"smoke-{secrets.token_hex(12)}@example.invalid",
                                   "password": secrets.token_urlsafe(32)})
        require(status == 201, "Group invitee registration failed")
        guest, session_id, guest_id = authenticated_response(response, public, args.issuer, args.audience)
        require(guest_id != owner_id, "Group smoke requires distinct accounts")
        provision_group_users(owner_id, guest_id)
        group_lifecycle(args.host, args.port, owner, owner_id, guest, guest_id)
        status, body = mutate(args.host, args.port, cookie_file, "logout", origin, {"expectedSessionId": session_id})
        require(status == 204 and body is None and refresh_cookie(cookie_file) is None, "Group invitee logout failed")
    finally:
        compose_exec("rm", "-f", cookie_file)
    print("Group HTTPS creation, access control, invitation, membership and departure checks passed.")

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--audience", required=True)
    args = parser.parse_args()
    # Match test-compose.sh's reserved project and override contract before any writes.
    if (not re.fullmatch(r"sharewise-p26ab-smoke[a-z0-9_-]*", os.environ.get("COMPOSE_PROJECT_NAME", ""))
            or os.environ.get("COMPOSE_FILE") != "compose.yaml"):
        raise RuntimeError("Run the API smoke through test-compose.sh on a disposable Compose project")
    cookie_file = compose_exec("mktemp", "/tmp/sharewise-iam-cookie.XXXXXXXX").strip()
    retained_cookie_file = None
    try:
        email = f"smoke-{secrets.token_hex(12)}@example.invalid"
        password = secrets.token_urlsafe(32)
        payload = {"email": email, "password": password}
        origin = f"https://{args.host}:{args.port}"
        public = subprocess.run([str(Path(__file__).resolve().parent / "iam-keys.sh"), "export"],
                                capture_output=True, text=True, check=True).stdout
        registered_user_id = None
        for operation, expected in (("register", 201), ("login", 200)):
            status, response = mutate(args.host, args.port, cookie_file, operation, origin, payload)
            if status != expected:
                raise RuntimeError("IAM registration/login response was unsuccessful")
            token, session_id, user_id = authenticated_response(response, public, args.issuer, args.audience)
            if registered_user_id is not None and user_id != registered_user_id:
                raise RuntimeError("IAM login returned a different account")
            registered_user_id = user_id

        original_cookie = refresh_cookie(cookie_file)
        if not original_cookie:
            raise RuntimeError("IAM login did not issue a refresh cookie")
        status, refreshed = mutate(args.host, args.port, cookie_file, "refresh", origin,
                                   {"expectedSessionId": session_id})
        if status != 200:
            raise RuntimeError("IAM refresh was unsuccessful")
        renewed_token, renewed_session_id, renewed_user_id = authenticated_response(refreshed, public, args.issuer, args.audience)
        if renewed_session_id != session_id or renewed_user_id != user_id or renewed_token == token:
            raise RuntimeError("IAM refresh did not issue a new JWT for the same session")
        replacement_cookie = refresh_cookie(cookie_file)
        if not replacement_cookie or replacement_cookie == original_cookie:
            raise RuntimeError("IAM refresh did not rotate the cookie")

        # A normal production artifact must not expose the disposable fixture, even to an authenticated caller.
        fixture_status, _ = json_request(edge_options(args.host, args.port) + ["--config", "-"],
            origin + "/api/smoke/v1/effects", f"header = {json.dumps('Authorization: Bearer ' + renewed_token)}\n")
        require(fixture_status == 404, "Disposable fixture route exposed by the production artifact")
        exercise_groups(args, origin, public, renewed_token, user_id)

        # Retain the current credential in a separate private jar so the test
        # verifies server-side revocation, not merely browser cookie removal.
        retained_cookie_file = compose_exec("mktemp", "/tmp/sharewise-iam-retained.XXXXXXXX").strip()
        compose_exec("cp", cookie_file, retained_cookie_file)
        status, body = mutate(args.host, args.port, cookie_file, "logout", origin,
                              {"expectedSessionId": session_id})
        if status != 204 or body is not None or refresh_cookie(cookie_file) is not None:
            raise RuntimeError("IAM logout did not clear the refresh cookie")
        status, rejected = mutate(args.host, args.port, retained_cookie_file, "refresh", origin, {})
        if status != 401 or not isinstance(rejected, dict) or rejected.get("code") != "refresh_unavailable":
            raise RuntimeError("IAM refresh succeeded or returned an unexpected result after logout")
        if refresh_cookie(cookie_file) is not None:
            raise RuntimeError("IAM refresh unexpectedly restored a cookie after logout")
    finally:
        if retained_cookie_file is not None:
            compose_exec("rm", "-f", retained_cookie_file)
        compose_exec("rm", "-f", cookie_file)
    print("IAM HTTPS registration, login, refresh rotation, logout and ES256 verification passed.")


if __name__ == "__main__":
    main()
