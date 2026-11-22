#!/usr/bin/env python3
"""Disposable IAM lifecycle smoke through the real HTTPS Compose edge.

Requires Python stdlib, OpenSSL and Docker/Compose; never prints credentials,
cookie values or access JWTs. Called by test-compose.sh after the stack is ready.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid


REFRESH_COOKIE = "__Host-sharewise-refresh"


def compose_exec(*args: str, input_body: str | None = None) -> str:
    cli = os.environ.get("CONTAINER_CLI", "docker")
    result = subprocess.run(
        [cli, "compose", "exec", "-T", "backend", *args],
        input=input_body, text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        # Do not propagate curl/HTTP debug output: it may contain secrets.
        raise RuntimeError("IAM Compose HTTP request failed")
    return result.stdout


def curl(host: str, port: str, cookie_file: str, path: str,
         origin: str | None = None, payload: str | None = None,
         csrf: str | None = None) -> tuple[int, dict | None]:
    url = f"https://{host}:{port}/api/iam/v1/auth/{path}"
    options = [
        "curl", "--silent", "--show-error", "--insecure", "--max-time", "20",
        "--connect-to", f"{host}:{port}:frontend:443",
        "--cookie", cookie_file, "--cookie-jar", cookie_file,
        "--write-out", "\n%{http_code}",
    ]
    if payload is not None:
        options += ["-X", "POST", "-H", "Content-Type: application/json",
                    "-H", f"Origin: {origin}", "-H", f"X-XSRF-TOKEN: {csrf}",
                    "--data-binary", "@-"]
    output = compose_exec(*options, url, input_body=payload)
    body, status = output.rsplit("\n", 1)
    if not body.strip():
        return int(status), None
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        raise RuntimeError("IAM returned an invalid JSON response") from None
    if not isinstance(parsed, dict):
        raise RuntimeError("IAM returned a non-object JSON response")
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
                           issuer: str, audience: str) -> tuple[str, str]:
    if not isinstance(response, dict) or response.get("tokenType") != "Bearer":
        raise RuntimeError("IAM authentication response was unsuccessful")
    token = response.get("accessToken")
    session_id = response.get("sessionId")
    if not isinstance(token, str) or not isinstance(session_id, str):
        raise RuntimeError("IAM authentication response lacked credentials or session identity")
    try:
        if str(uuid.UUID(session_id)) != session_id:
            raise ValueError()
    except ValueError:
        raise RuntimeError("IAM returned an invalid session ID") from None
    claims = verify_es256(token, public_pem, issuer, audience)
    if claims.get("sid") != session_id:
        raise RuntimeError("IAM JWT session binding does not match the authentication response")
    return token, session_id


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--audience", required=True)
    args = parser.parse_args()
    cookie_file = compose_exec("mktemp", "/tmp/sharewise-iam-cookie.XXXXXXXX").strip()
    retained_cookie_file = None
    try:
        email = f"smoke-{secrets.token_hex(12)}@example.invalid"
        password = secrets.token_urlsafe(32)
        payload = {"email": email, "password": password}
        origin = f"https://{args.host}:{args.port}"
        public = subprocess.run([str(Path(__file__).resolve().parent / "iam-keys.sh"), "export"],
                                capture_output=True, text=True, check=True).stdout
        for operation, expected in (("register", 201), ("login", 200)):
            status, response = mutate(args.host, args.port, cookie_file, operation, origin, payload)
            if status != expected:
                raise RuntimeError("IAM registration/login response was unsuccessful")
            token, session_id = authenticated_response(response, public, args.issuer, args.audience)

        original_cookie = refresh_cookie(cookie_file)
        if not original_cookie:
            raise RuntimeError("IAM login did not issue a refresh cookie")
        status, refreshed = mutate(args.host, args.port, cookie_file, "refresh", origin,
                                   {"expectedSessionId": session_id})
        if status != 200:
            raise RuntimeError("IAM refresh was unsuccessful")
        renewed_token, renewed_session_id = authenticated_response(refreshed, public, args.issuer, args.audience)
        if renewed_session_id != session_id or renewed_token == token:
            raise RuntimeError("IAM refresh did not issue a new JWT for the same session")
        replacement_cookie = refresh_cookie(cookie_file)
        if not replacement_cookie or replacement_cookie == original_cookie:
            raise RuntimeError("IAM refresh did not rotate the cookie")

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
