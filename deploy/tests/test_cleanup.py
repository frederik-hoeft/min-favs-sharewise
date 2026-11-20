"""Destructive cleanup is explicitly scoped; Docker operations use a fake CLI.

These tests verify shell decisions and local file effects, not Docker daemon behavior.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / "deploy"
MOCK_DOCKER = r'''#!/usr/bin/env python3
import os
import sys
args = sys.argv[1:]
with open(os.environ["DOCKER_CALLS"], "a", encoding="utf-8") as output:
    output.write(" ".join(args) + "\n")
if args[:2] == ["volume", "inspect"]:
    if "-f" in args:
        print("sharewise-p26ab-smoke2")
        sys.exit(0)
    sys.exit(0 if args[-1].endswith("_tls_certs") else 1)
if args[:2] == ["volume", "ls"]:
    print("sharewise-p26ab-smoke2_postgres_data")
if args[:2] == ["network", "ls"]:
    pass
if args[:2] == ["ps", "-a"]:
    print("sharewise-p26ab-smoke")
'''


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.deploy = self.root / "deploy"
        scripts = self.deploy / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/cleanup.sh", scripts / "cleanup.sh")
        (self.deploy / ".env").write_text(
            "COMPOSE_PROJECT_NAME=sharewise-p26ab-local\n"
            "SHAREWISE_PUBLIC_HOST=localhost\n"
        )
        self.local = self.deploy / ".local"
        (self.local / "tls").mkdir(parents=True)
        for name in ("fullchain.pem", "privkey.pem"):
            (self.local / "tls" / name).write_text("fake-certificate-data")
        (self.local / "secrets").mkdir()
        (self.local / "secrets" / "postgres_admin_password").write_text("private")
        self.docker = self.root / "docker"
        self.docker.write_text(MOCK_DOCKER)
        self.docker.chmod(0o755)
        self.calls = self.root / "docker-calls"
        self.calls.write_text("")
        self.env = os.environ | {
            "CONTAINER_CLI": str(self.docker),
            "DOCKER_CALLS": str(self.calls),
        }
        self.env.pop("COMPOSE_PROJECT_NAME", None)

    def invoke(self, *args):
        return subprocess.run(
            ["bash", str(self.deploy / "scripts/cleanup.sh"), *args],
            env=self.env, capture_output=True, text=True,
        )

    def test_cleanup_requires_confirmation_and_preserves_everything(self):
        result = self.invoke("local")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing non-interactive cleanup", result.stderr)
        self.assertEqual(self.calls.read_text(), "")
        self.assertTrue((self.deploy / ".env").exists())

    def test_tls_reset_preserves_db_credentials_and_local_configuration(self):
        result = self.invoke("tls", "--yes")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.local / "secrets/postgres_admin_password").exists())
        self.assertTrue((self.deploy / ".env").exists())
        self.assertFalse((self.local / "tls/fullchain.pem").exists())
        log = self.calls.read_text()
        self.assertIn("compose -p sharewise-p26ab-local down", log)
        self.assertIn("volume rm sharewise-p26ab-local_tls_certs", log)
        self.assertNotIn("--volumes", log)
        self.assertNotIn("postgres_data", log)

    def test_local_reset_requires_explicit_yes_and_deletes_local_configuration(self):
        result = self.invoke("local", "--yes")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("compose -p sharewise-p26ab-local down --volumes --remove-orphans", self.calls.read_text())
        self.assertFalse((self.deploy / ".env").exists())
        self.assertFalse(self.local.exists())

    def test_test_cleanup_rejects_normal_and_unrelated_project_names(self):
        for name in ("sharewise-p26ab-local", "some-other-app", "sharewise-p26ab-smoke;rm -rf /"):
            with self.subTest(name=name):
                self.assertNotEqual(self.invoke("test", name, "--yes").returncode, 0)
        self.assertEqual(self.calls.read_text(), "")

    def test_test_cleanup_deletes_only_explicit_disposable_project(self):
        result = self.invoke("test", "sharewise-p26ab-smoke2", "--yes")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.calls.read_text()
        self.assertIn("compose -p sharewise-p26ab-smoke2 down --volumes --remove-orphans", log)
        self.assertNotIn("compose -p sharewise-p26ab-local", log)
        self.assertTrue(self.local.exists())

    def test_bulk_cleanup_discovers_only_reserved_test_projects(self):
        result = self.invoke("tests", "--yes")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.calls.read_text()
        self.assertIn("compose -p sharewise-p26ab-smoke down --volumes --remove-orphans", log)
        self.assertIn("compose -p sharewise-p26ab-smoke2 down --volumes --remove-orphans", log)
        self.assertNotIn("compose -p sharewise-p26ab-local", log)
        self.assertTrue((self.deploy / ".env").exists())


if __name__ == "__main__":
    unittest.main()
