"""IAM key-init contract, independent of Docker and PostgreSQL.

The same POSIX entrypoint runs in the one-shot Compose service. These tests use
real OpenSSL and temporary volume directories, without exposing private keys.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
INIT = ROOT / "deploy/iam-key-init/init-iam-keys.sh"
COMPOSE = (ROOT / "deploy/compose.yaml").read_text()


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required")
class IamKeyInitializationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private = self.root / "private"
        self.public = self.root / "public"
        self.private.mkdir()
        self.public.mkdir()
        # Production init runs as root to grant backend GID 10001 read access.
        # This test also works under an unprivileged CI runner, where chown needs
        # to be simulated but chmod and real OpenSSL generation still execute.
        self.environment = os.environ.copy()
        if os.geteuid() != 0:
            mock_bin = self.root / "bin"
            mock_bin.mkdir()
            chown = mock_bin / "chown"
            chown.write_text("#!/bin/sh\nexit 0\n")
            chown.chmod(0o755)
            self.environment["PATH"] = f"{mock_bin}:{self.environment.get('PATH', '')}"

    def run_init(self, key_id: str = "iam-local-1", mode: str = "generate-if-absent") -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["sh", str(INIT)],
            env=self.environment | {
                "IAM_KEY_ID": key_id,
                "IAM_KEY_MODE": mode,
                "IAM_PRIVATE_DIR": str(self.private),
                "IAM_PUBLIC_DIR": str(self.public),
            },
            capture_output=True,
            text=True,
            check=False,
        )

    def test_new_key_is_p256_pkcs8_and_public_spki_with_separate_permissions(self) -> None:
        generated = self.run_init()
        self.assertEqual(generated.returncode, 0, generated.stderr)
        self.assertEqual(generated.stdout, "")
        self.assertFalse((self.private / ".iam-lock").exists())
        private = self.private / "active.pem"
        public = self.public / "active.pem"
        self.assertTrue(private.read_text().startswith("-----BEGIN PRIVATE KEY-----"))
        self.assertTrue(public.read_text().startswith("-----BEGIN PUBLIC KEY-----"))
        self.assertEqual((self.private / "key-id").read_text(), "iam-local-1")
        self.assertEqual((self.public / "key-id").read_text(), "iam-local-1")
        self.assertEqual(private.stat().st_mode & 0o777, 0o440)
        self.assertEqual(public.stat().st_mode & 0o777, 0o444)
        self.assertEqual(self.private.stat().st_mode & 0o777, 0o750)
        self.assertEqual(self.public.stat().st_mode & 0o777, 0o755)
        if os.geteuid() == 0:
            self.assertEqual(private.stat().st_gid, 10001)
        self.assertNotIn("PRIVATE KEY", public.read_text())
        self.assertFalse(any(self.public.glob("*private*")))
        self.assertIn("ASN1 OID: prime256v1", subprocess.check_output(
            ["openssl", "pkey", "-in", str(private), "-text", "-noout"], text=True))
        derived = subprocess.check_output(["openssl", "pkey", "-in", str(private), "-pubout"])
        self.assertEqual(derived, public.read_bytes())

    def test_valid_sec1_key_is_rejected_as_incompatible_with_jvm_loader(self) -> None:
        subprocess.run([
            "openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout",
            "-out", str(self.private / "active.pem"),
        ], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([
            "openssl", "pkey", "-in", str(self.private / "active.pem"),
            "-pubout", "-out", str(self.public / "active.pem"),
        ], check=True, stdout=subprocess.DEVNULL)
        (self.private / "key-id").write_text("iam-local-1")
        (self.public / "key-id").write_text("iam-local-1")
        before = (self.private / "active.pem").read_bytes()
        rejected = self.run_init(mode="require-existing")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("PKCS#8", rejected.stderr)
        self.assertEqual((self.private / "active.pem").read_bytes(), before)

    def test_concatenated_pkcs8_blocks_are_rejected(self) -> None:
        self.assertEqual(self.run_init().returncode, 0)
        private = self.private / "active.pem"
        original = private.read_bytes()
        private.write_bytes(original + original)
        rejected = self.run_init(mode="require-existing")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("exactly one", rejected.stderr)
        self.assertEqual(private.read_bytes(), original + original)

    def test_idempotence_and_require_existing_mode_do_not_rotate(self) -> None:
        self.assertNotEqual(self.run_init(mode="require-existing").returncode, 0)
        self.assertEqual(self.run_init().returncode, 0)
        private_before = (self.private / "active.pem").read_bytes()
        public_before = (self.public / "active.pem").read_bytes()
        again = self.run_init(mode="require-existing")
        self.assertEqual(again.returncode, 0, again.stderr)
        self.assertEqual((self.private / "active.pem").read_bytes(), private_before)
        self.assertEqual((self.public / "active.pem").read_bytes(), public_before)
        wrong_id = self.run_init(key_id="iam-local-2")
        self.assertNotEqual(wrong_id.returncode, 0)
        self.assertIn("refusing implicit rotation", wrong_id.stderr)
        self.assertEqual((self.private / "active.pem").read_bytes(), private_before)

    def test_partial_or_invalid_state_fails_closed(self) -> None:
        self.assertEqual(self.run_init().returncode, 0)
        (self.public / "active.pem").unlink()
        incomplete = self.run_init()
        self.assertNotEqual(incomplete.returncode, 0)
        self.assertIn("Partial IAM signing key fixture", incomplete.stderr)
        self.assertTrue((self.private / "active.pem").exists())

    def test_stale_private_staging_is_not_silently_discarded(self) -> None:
        stage = self.private / ".iam-init.interrupted"
        stage.mkdir()
        (stage / "private.pem").write_text("sensitive incomplete fixture")
        rejected = self.run_init()
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("staging artifacts", rejected.stderr)
        self.assertTrue((stage / "private.pem").exists())
        self.assertFalse((self.private / "active.pem").exists())

    def test_concurrent_or_interrupted_initializer_is_rejected(self) -> None:
        lock = self.private / ".iam-lock"
        lock.mkdir()
        blocked = self.run_init()
        self.assertNotEqual(blocked.returncode, 0)
        self.assertIn("already running or interrupted", blocked.stderr)
        self.assertFalse((self.private / "active.pem").exists())
        lock.rmdir()
        self.assertEqual(self.run_init().returncode, 0)
        self.assertFalse(lock.exists())

    def test_tampered_public_key_is_rejected_without_replacement(self) -> None:
        self.assertEqual(self.run_init().returncode, 0)
        private_before = (self.private / "active.pem").read_bytes()
        (self.public / "active.pem").write_text("-----BEGIN PUBLIC KEY-----\nbad\n-----END PUBLIC KEY-----\n")
        rejected = self.run_init()
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("Invalid IAM public key", rejected.stderr)
        self.assertEqual((self.private / "active.pem").read_bytes(), private_before)

    def test_symlink_and_invalid_id_are_rejected(self) -> None:
        outside = self.root / "outside"
        outside.write_text("do not touch")
        (self.private / "active.pem").symlink_to(outside)
        self.assertNotEqual(self.run_init().returncode, 0)
        self.assertEqual(outside.read_text(), "do not touch")
        (self.private / "active.pem").unlink()
        self.assertNotEqual(self.run_init(key_id="../../bad").returncode, 0)
        self.assertNotEqual(self.run_init(key_id="-bad").returncode, 0)
        self.assertNotEqual(self.run_init(key_id="a" * 65).returncode, 0)
        self.assertFalse((self.private / "active.pem").exists())

    def test_operator_cli_exports_only_the_public_pem(self) -> None:
        # A fake Docker CLI verifies that the wrapper dispatches the public-only
        # service and does not write secrets or helper text to stdout.
        deploy = self.root / "deploy"
        scripts = deploy / "scripts"
        scripts.mkdir(parents=True)
        (deploy / ".env").write_text("COMPOSE_PROJECT_NAME=sharewise-p26ab-local\n")
        shutil.copy2(ROOT / "deploy/scripts/lib-compose.sh", scripts / "lib-compose.sh")
        shutil.copy2(ROOT / "deploy/scripts/iam-keys.sh", scripts / "iam-keys.sh")
        docker = self.root / "mock-docker"
        docker.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$IAM_MOCK_LOG\"\n"
                          "case \"$*\" in *iam-public-export*) printf '%s\\n' PUBLIC_PEM;; esac\n")
        docker.chmod(0o755)
        calls = self.root / "calls"
        environment = self.environment | {
            "CONTAINER_CLI": str(docker),
            "IAM_MOCK_LOG": str(calls),
        }
        exported = subprocess.run(
            ["bash", str(scripts / "iam-keys.sh"), "export"],
            env=environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual(exported.returncode, 0, exported.stderr)
        self.assertEqual(exported.stdout, "PUBLIC_PEM\n")
        self.assertIn("compose --profile setup run --rm --no-deps -T iam-public-export", calls.read_text())
        self.assertNotIn("iam-signing", calls.read_text())
        self.assertNotIn("iam-key-init", calls.read_text())

    def test_public_exporter_cannot_mount_private_volume(self) -> None:
        self.assertIn("iam_signing_private:/keys/private", COMPOSE)
        self.assertIn("iam_verification_public:/keys/public", COMPOSE)
        self.assertIn("iam_signing_private:/run/iam/private:ro", COMPOSE)
        self.assertIn("iam_verification_public:/run/iam/public:ro", COMPOSE)
        exporter = COMPOSE.split("\n  iam-public-export:\n", 1)[1].split("\nnetworks:\n", 1)[0]
        self.assertIn("iam_verification_public:/keys/public:ro", exporter)
        self.assertNotIn("iam_signing_private", exporter)
        self.assertIn("network_mode: none", exporter)
        backend = COMPOSE.split("\n  backend:\n", 1)[1].split("\n  frontend:\n", 1)[0]
        self.assertIn("condition: service_completed_successfully", backend)
        self.assertIn('SHAREWISE_IAM_AUTH_ENABLED: "true"', backend)
        self.assertNotIn("    ports:", exporter)


if __name__ == "__main__":
    unittest.main()
