#!/usr/bin/env python3
"""Disposable IAM login/registration smoke through the real HTTPS Compose edge.

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
         csrf: str | None = None) -> tuple[int, dict]:
    url = f"https://{host}:{port}/api/iam/v1/auth/{path}"
    options = [
        "curl", "--silent", "--show-error", "--insecure", "--max-time", "20",
        "--connect-to", f"{host}:{port}:frontend:443",
        "--cookie", cookie_file, "--cookie-jar", cookie_file,
        "--write-out", "\\n%{http_code}",
    ]
    if payload is not None:
        options += ["-X", "POST", "-H", "Content-Type: application/json",
                    "-H", f"Origin: {origin}", "-H", f"X-XSRF-TOKEN: {csrf}",
                    "--data-binary", "@-"]
    output = compose_exec(*options, url, input_body=payload)
    body, status = output.rsplit("\n", 1)
    return int(status), json.loads(body)


def der_integer(integer: bytes) -> bytes:
    value = integer.lstrip(b"\x00") or b"\x00"
    if value[0] & 0x80:
        value = b"\x00" + value
    return b"\x02" + bytes([len(value)]) + value


def verify_es256(jwt: str, public_pem: str, issuer: str, audience: str) -> None:
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--audience", required=True)
    args = parser.parse_args()
    cookie_file = compose_exec("mktemp", "/tmp/sharewise-iam-cookie.XXXXXXXX").strip()
    try:
        email = f"smoke-{secrets.token_hex(12)}@example.invalid"
        password = secrets.token_urlsafe(32)
        payload = json.dumps({"email": email, "password": password})
        origin = f"https://{args.host}:{args.port}"
        public = subprocess.run([str(Path(__file__).resolve().parent / "iam-keys.sh"), "export"],
                                capture_output=True, text=True, check=True).stdout
        for operation, expected in (("register", 201), ("login", 200)):
            csrf_status, csrf = curl(args.host, args.port, cookie_file, "csrf")
            if csrf_status != 200 or not csrf.get("token"):
                raise RuntimeError("IAM CSRF bootstrap failed")
            status, response = curl(args.host, args.port, cookie_file, operation,
                                    origin=origin, payload=payload, csrf=csrf["token"])
            if status != expected or response.get("tokenType") != "Bearer":
                raise RuntimeError("IAM registration/login response was unsuccessful")
            verify_es256(response.get("accessToken", ""), public, args.issuer, args.audience)
    finally:
        compose_exec("rm", "-f", cookie_file)
    print("IAM HTTPS registration/login and exported ES256 verification passed.")


if __name__ == "__main__":
    main()
