"""IAM HTTPS smoke orchestration tests without a Docker daemon."""

import contextlib
import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "iam-http-smoke.py"
SPEC = importlib.util.spec_from_file_location("iam_http_smoke", SCRIPT)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
SESSION_ID = "a7bce821-a603-40aa-9c38-f138e6d1c909"


class FakeIam:
    def __init__(self, *, rotate=True, deny_after_logout=True):
        self.cookie = None
        self.retained_cookie = None
        self.rotate = rotate
        self.deny_after_logout = deny_after_logout
        self.calls = []
        self.cleaned = []
        self.counter = 0

    def compose_exec(self, *args, input_body=None):
        if args[0] == "mktemp":
            return ("/tmp/fake-retained-cookie" if "retained" in args[1] else "/tmp/fake-smoke-cookie")
        if args[0] == "cp":
            self.retained_cookie = self.cookie
            return ""
        if args[0] == "cat":
            value = self.retained_cookie if args[1] == "/tmp/fake-retained-cookie" else self.cookie
            return (f"#HttpOnly_localhost\tFALSE\t/\tTRUE\t2000000000\t{smoke.REFRESH_COOKIE}\t{value}\n"
                    if value is not None else "# Netscape HTTP Cookie File\n")
        if args[:2] == ("rm", "-f"):
            self.cleaned.append(args[2])
            return ""
        raise AssertionError(f"Unexpected command: {args[0]}")

    def curl(self, host, port, cookie_file, path, origin=None, payload=None, csrf=None):
        self.calls.append(path)
        if path == "csrf":
            return 200, {"token": "csrf-smoke"}
        self.counter += 1
        if path in ("register", "login"):
            self.cookie = f"cookie-{self.counter}"
            return (201 if path == "register" else 200), self.auth(f"token-{self.counter}")
        if path == "refresh":
            if cookie_file == "/tmp/fake-retained-cookie":
                assert self.retained_cookie is not None and self.cookie is None
                assert payload == "{}"
                if self.deny_after_logout:
                    return 401, {"code": "refresh_unavailable"}
                return 200, self.auth("unexpected-token")
            if self.cookie is None:
                if self.deny_after_logout:
                    return 401, {"code": "refresh_unavailable"}
                return 200, self.auth("unexpected-token")
            self.assert_binding(payload, SESSION_ID)
            if self.rotate:
                self.cookie = f"cookie-{self.counter}"
            return 200, self.auth(f"token-{self.counter}")
        if path == "logout":
            self.assert_binding(payload, SESSION_ID)
            self.cookie = None
            return 204, None
        raise AssertionError(f"Unexpected IAM operation: {path}")

    @staticmethod
    def assert_binding(payload, expected):
        import json
        assert json.loads(payload) == {"expectedSessionId": expected}

    @staticmethod
    def auth(token):
        return {"accessToken": token, "tokenType": "Bearer", "sessionId": SESSION_ID}


class IamHttpSmokeTests(unittest.TestCase):
    def run_smoke(self, fake):
        with (patch.object(smoke, "compose_exec", side_effect=fake.compose_exec),
              patch.object(smoke, "curl", side_effect=fake.curl),
              patch.object(smoke, "verify_es256", return_value={"sid": SESSION_ID}),
              patch.object(smoke.subprocess, "run", return_value=SimpleNamespace(stdout="public.pem")),
              patch.object(sys, "argv", ["iam-http-smoke.py", "--host", "localhost", "--port", "18443",
                                         "--issuer", "https://localhost:18443", "--audience", "sharewise-api"])):
            output = io.StringIO()
            try:
                with contextlib.redirect_stdout(output):
                    smoke.main()
            finally:
                self.assertIn("/tmp/fake-smoke-cookie", fake.cleaned)
                if "logout" in fake.calls:
                    self.assertIn("/tmp/fake-retained-cookie", fake.cleaned)
            return output.getvalue()

    def test_registration_login_rotation_logout_and_denied_restoration(self):
        fake = FakeIam()
        output = self.run_smoke(fake)
        self.assertEqual(fake.calls, [
            "csrf", "register", "csrf", "login", "csrf", "refresh", "csrf", "logout", "csrf", "refresh",
        ])
        self.assertIn("refresh rotation, logout", output)
        self.assertNotIn("token-", output)
        self.assertNotIn("cookie-", output)

    def test_unchanged_cookie_after_refresh_fails(self):
        with self.assertRaisesRegex(RuntimeError, "did not rotate"):
            self.run_smoke(FakeIam(rotate=False))

    def test_refresh_after_logout_must_be_denied(self):
        with self.assertRaisesRegex(RuntimeError, "unexpected result after logout"):
            self.run_smoke(FakeIam(deny_after_logout=False))

    def test_empty_204_response_is_supported(self):
        with patch.object(smoke, "compose_exec", return_value="\n204"):
            self.assertEqual(smoke.curl("localhost", "18443", "/tmp/jar", "logout", payload="{}"), (204, None))

    def test_cookie_jar_requires_secure_httponly_host_only_cookie(self):
        jar = f"#HttpOnly_localhost\tFALSE\t/\tTRUE\t2000000000\t{smoke.REFRESH_COOKIE}\tsecret\n"
        with patch.object(smoke, "compose_exec", return_value=jar):
            self.assertEqual(smoke.refresh_cookie("/tmp/jar"), "secret")
        with patch.object(smoke, "compose_exec", return_value=jar.replace("\tTRUE\t", "\tFALSE\t")):
            with self.assertRaisesRegex(RuntimeError, "attributes"):
                smoke.refresh_cookie("/tmp/jar")
        with patch.object(smoke, "compose_exec", return_value=jar + jar):
            with self.assertRaisesRegex(RuntimeError, "duplicate"):
                smoke.refresh_cookie("/tmp/jar")


if __name__ == "__main__":
    unittest.main()
