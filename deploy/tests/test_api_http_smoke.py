"""HTTPS smoke orchestration tests without a Docker daemon."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "api-http-smoke.py"
SPEC = importlib.util.spec_from_file_location("api_http_smoke", SCRIPT)
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
              patch.object(smoke, "verify_es256", return_value={"sid": SESSION_ID, "sub": SESSION_ID}),
              patch.object(smoke, "exercise_groups") as groups,
              patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "sharewise-p26ab-smoke", "COMPOSE_FILE": "compose.yaml"}),
              patch.object(smoke.subprocess, "run", return_value=SimpleNamespace(stdout="public.pem")),
              patch.object(sys, "argv", ["api-http-smoke.py", "--host", "localhost", "--port", "18443",
                                         "--issuer", "https://localhost:18443", "--audience", "sw-api-v1"])):
            output = io.StringIO()
            try:
                with contextlib.redirect_stdout(output):
                    smoke.main()
            finally:
                self.assertIn("/tmp/fake-smoke-cookie", fake.cleaned)
                if "logout" in fake.calls:
                    self.assertIn("/tmp/fake-retained-cookie", fake.cleaned)
            groups.assert_called_once()
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

    def test_standalone_execution_refuses_non_disposable_project(self):
        with (patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "sharewise-p26ab-local", "COMPOSE_FILE": "compose.yaml"}),
              patch.object(sys, "argv", ["api-http-smoke.py", "--host", "localhost", "--port", "18443",
                                         "--issuer", "https://localhost:18443", "--audience", "sw-api-v1"]),
              patch.object(smoke, "compose_exec") as compose):
            with self.assertRaisesRegex(RuntimeError, "disposable Compose project"):
                smoke.main()
            compose.assert_not_called()

    def test_empty_204_response_is_supported(self):
        with patch.object(smoke, "compose_exec", return_value="\n204") as invoke:
            self.assertEqual(smoke.curl("localhost", "18443", "/tmp/jar", "logout", payload="{}"), (204, None))
            argv = invoke.call_args.args
            self.assertEqual(argv[argv.index("--write-out") + 1], r"\n%{http_code}")

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


OWNER_ID = "a7bce821-a603-40aa-9c38-f138e6d1c901"
GUEST_ID = "a7bce821-a603-40aa-9c38-f138e6d1c902"
GROUP_ID = "a7bce821-a603-40aa-9c38-f138e6d1c903"
INVITATION_ID = "a7bce821-a603-40aa-9c38-f138e6d1c904"
OWNER_MEMBER = "a7bce821-a603-40aa-9c38-f138e6d1c905"
GUEST_MEMBER = "a7bce821-a603-40aa-9c38-f138e6d1c906"
INVITATION_TOKEN = INVITATION_ID + ".fixture-secret"


class GroupHttpSmokeTests(unittest.TestCase):
    def scenario(self):
        name = "Compose smoke group"
        path = f"/groups/{GROUP_ID}"
        workspace = path + "/workspace"
        invitations = path + "/invitations"
        owner = {"id": OWNER_MEMBER, "userId": OWNER_ID, "role": "admin"}
        guest = {"id": GUEST_MEMBER, "userId": GUEST_ID, "role": "member"}
        promoted = guest | {"role": "admin"}
        group = {"id": GROUP_ID, "name": name, "callerRole": "admin", "memberCount": 1}
        issued = {"id": INVITATION_ID, "useCount": 0, "maxUses": 1,
                  "createdAt": "2026-11-25T00:00:00Z", "expiresAt": None}
        hidden = {"code": "group_not_found"}
        # Explicit expectations at the HTTP boundary, not a second implementation of Group policy.
        return [
            ("owner", "POST", "/groups", {"name": name}, 201, group),
            ("owner", "GET", "/groups", None, 200, {"items": [group]}),
            ("guest", "GET", "/groups", None, 200, {"items": []}),
            (None, "GET", workspace, None, 401, {"code": "authentication_required"}),
            ("guest", "GET", workspace, None, 404, hidden),
            ("owner", "POST", invitations, {"maxUses": 1}, 201, {"invitation": issued, "token": INVITATION_TOKEN}),
            ("guest", "POST", "/invitations/preview", {"token": INVITATION_TOKEN}, 200, {"groupName": name}),
            ("guest", "GET", workspace, None, 404, hidden),
            ("guest", "POST", "/invitations/redeem", {"token": INVITATION_TOKEN}, 200, {"groupId": GROUP_ID, "outcome": "joined"}),
            ("owner", "GET", invitations, None, 200, {"items": [issued | {"useCount": 1}]}),
            ("guest", "POST", "/invitations/preview", {"token": INVITATION_TOKEN}, 404, {"code": "group_invitation_unavailable"}),
            ("guest", "GET", invitations, None, 403, {"code": "group_admin_required"}),
            ("guest", "GET", workspace, None, 200, {"group": group | {"callerRole": "member", "memberCount": 2},
                "callerMembershipId": GUEST_MEMBER, "members": [owner, guest]}),
            ("owner", "PUT", path + f"/members/{GUEST_MEMBER}/role", {"role": "admin"}, 204, None),
            ("guest", "DELETE", invitations + f"/{INVITATION_ID}", None, 204, None),
            ("guest", "GET", invitations, None, 200, {"items": []}),
            ("owner", "GET", workspace, None, 200, {"group": group | {"memberCount": 2},
                "callerMembershipId": OWNER_MEMBER, "members": [owner, promoted]}),
            ("owner", "DELETE", path + f"/members/{OWNER_MEMBER}", None, 204, None),
            ("owner", "GET", workspace, None, 404, hidden),
            ("guest", "GET", workspace, None, 200, {"group": group, "callerMembershipId": GUEST_MEMBER,
                "members": [promoted]}),
        ]

    def run_scenario(self, steps):
        calls = iter(steps)

        def request(host, port, bearer, method, path, payload=None):
            self.assertEqual((host, port), ("localhost", "18443"))
            actor, verb, route, body, status, response = next(calls)
            self.assertEqual((bearer, method, path, payload), (actor, verb, route, body))
            return status, response

        with patch.object(smoke, "group_request", side_effect=request):
            smoke.group_lifecycle("localhost", "18443", "owner", OWNER_ID, "guest", GUEST_ID)
        self.assertIsNone(next(calls, None))

    def test_two_account_lifecycle_uses_live_roles_and_explicit_redemption(self):
        self.run_scenario(self.scenario())

    def test_preview_must_not_grant_membership(self):
        steps = self.scenario()
        steps[7] = (*steps[7][:4], 200, {})
        with self.assertRaisesRegex(RuntimeError, "expected HTTP 404, got 200"):
            self.run_scenario(steps)

    def test_listing_must_not_disclose_invitation_secret(self):
        steps = self.scenario()
        steps[9][5]["items"][0]["token"] = INVITATION_TOKEN
        with self.assertRaisesRegex(RuntimeError, "unexpected fields") as failure:
            self.run_scenario(steps)
        self.assertNotIn(INVITATION_TOKEN, str(failure.exception))

    def test_member_must_not_list_invitations_before_promotion(self):
        steps = self.scenario()
        steps[11] = (*steps[11][:4], 200, {"items": []})
        with self.assertRaisesRegex(RuntimeError, "expected HTTP 403, got 200"):
            self.run_scenario(steps)

    def test_group_request_uses_same_https_edge_without_secrets_in_argv(self):
        with patch.object(smoke, "compose_exec", return_value='{"groupName":"Trip"}\n200') as invoke:
            self.assertEqual(smoke.group_request("localhost", "18443", "jwt-fixture", "POST",
                "/invitations/preview", {"token": INVITATION_TOKEN}), (200, {"groupName": "Trip"}))
        args = invoke.call_args.args
        config = invoke.call_args.kwargs["input_body"]
        self.assertIn("localhost:18443:frontend:443", args)
        self.assertEqual(args[-1], "https://localhost:18443/api/groups/v1/invitations/preview")
        self.assertEqual(args[args.index("--config") + 1], "-")
        self.assertNotIn("jwt-fixture", " ".join(args))
        self.assertNotIn(INVITATION_TOKEN, " ".join(args))
        self.assertIn('header = "Authorization: Bearer jwt-fixture"', config)
        import json
        encoded_body = next(line.split(" = ", 1)[1] for line in config.splitlines() if line.startswith("data-binary"))
        self.assertEqual(json.loads(json.loads(encoded_body)), {"token": INVITATION_TOKEN})

    def test_malformed_status_diagnostic_does_not_include_response(self):
        with patch.object(smoke, "compose_exec", return_value="\n" + INVITATION_TOKEN):
            with self.assertRaisesRegex(RuntimeError, "invalid HTTP status") as failure:
                smoke.json_request([], "fixture", None)
            self.assertNotIn(INVITATION_TOKEN, str(failure.exception))

    def test_provisioning_uses_application_role_and_only_validated_user_ids(self):
        with patch.object(smoke, "compose_exec") as invoke:
            smoke.provision_group_users(OWNER_ID, GUEST_ID)
            self.assertEqual(invoke.call_args.kwargs["service"], "postgres")
            self.assertIn("-U sharewise_app", invoke.call_args.args[2])
            self.assertEqual(invoke.call_args.kwargs["input_body"],
                f"INSERT INTO groups.users (id) VALUES ('{OWNER_ID}'), ('{GUEST_ID}') ON CONFLICT DO NOTHING;\n")
            invoke.reset_mock()
            with self.assertRaisesRegex(RuntimeError, "Invalid fixture user ID"):
                smoke.provision_group_users("not-a-uuid")
            invoke.assert_not_called()

    def test_second_account_uses_separate_cookie_jar_and_cleans_up_on_failure(self):
        args = SimpleNamespace(host="localhost", port="18443", issuer="https://localhost:18443", audience="sw-api-v1")
        with (patch.object(smoke, "compose_exec", return_value="/tmp/guest-jar") as compose,
              patch.object(smoke, "mutate", side_effect=[(201, {}), (204, None)]) as mutate,
              patch.object(smoke, "authenticated_response", return_value=("guest", SESSION_ID, GUEST_ID)),
              patch.object(smoke, "provision_group_users") as provision,
              patch.object(smoke, "refresh_cookie", return_value=None),
              patch.object(smoke, "group_lifecycle") as lifecycle,
              contextlib.redirect_stdout(io.StringIO()) as output):
            smoke.exercise_groups(args, args.issuer, "public.pem", "owner", OWNER_ID)
            provision.assert_called_once_with(OWNER_ID, GUEST_ID)
            lifecycle.assert_called_once_with("localhost", "18443", "owner", OWNER_ID, "guest", GUEST_ID)
            self.assertEqual(mutate.call_args_list[1].args[2:5], ("/tmp/guest-jar", "logout", args.issuer))
            self.assertEqual(compose.call_args_list[-1].args, ("rm", "-f", "/tmp/guest-jar"))
            self.assertNotIn("guest-jar", output.getvalue())
            lifecycle.side_effect = RuntimeError("fixture failure")
            mutate.side_effect = [(201, {})]
            with self.assertRaisesRegex(RuntimeError, "fixture failure"):
                smoke.exercise_groups(args, args.issuer, "public.pem", "owner", OWNER_ID)
            self.assertEqual(compose.call_args_list[-1].args, ("rm", "-f", "/tmp/guest-jar"))


if __name__ == "__main__":
    unittest.main()
