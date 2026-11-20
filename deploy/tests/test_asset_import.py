"""Client-to-Docker volume asset-transfer contract, with a fake container CLI.

The fake runs exactly the loader shell inside a temporary directory, but cannot
prove compatibility with a real Docker daemon or Compose volume semantics.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "deploy"

MOCK_DOCKER = '''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
args = sys.argv[1:]
if "config" in args:
    sys.exit(0)
if "run" not in args or args[-2] != "-ec":
    print("Unexpected Docker invocation:", args, file=sys.stderr)
    sys.exit(2)
root = Path(os.environ["MOCK_DOCKER_ASSETS"])
for name in ("postgres", "backend", "tls"):
    (root / name).mkdir(exist_ok=True, parents=True)
script = args[-1].replace("/assets/", str(root) + "/")
os.execv("/bin/sh", ["/bin/sh", "-ec", script])
'''


@unittest.skipUnless(shutil.which("openssl") and shutil.which("tar"),
                     "OpenSSL and tar required")
class AssetImportTests(unittest.TestCase):
    def test_streamed_files_are_scoped_idempotent_and_non_rotatable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            deploy = root / "deploy"
            (deploy / "scripts").mkdir(parents=True)
            for name in ("lib-compose.sh", "import-assets.sh", "prepare-local.sh"):
                shutil.copy2(DEPLOY / "scripts" / name, deploy / "scripts" / name)
            docker = root / "mock-docker"
            docker.write_text(MOCK_DOCKER)
            docker.chmod(0o700)
            volumes = root / "volumes"
            env = os.environ | {
                "CONTAINER_CLI": str(docker),
                "MOCK_DOCKER_ASSETS": str(volumes),
            }
            prep = subprocess.run(["bash", str(deploy / "scripts/prepare-local.sh")],
                                  env=env, text=True, capture_output=True)
            self.assertEqual(prep.returncode, 0, prep.stderr)

            def run_import():
                return subprocess.run(["bash", str(deploy / "scripts/import-assets.sh")],
                                      env=env, capture_output=True, text=True)

            imported = run_import()
            self.assertEqual(imported.returncode, 0, imported.stderr)
            secret_names = ("sharewise.database.app-password",
                            "sharewise.database.migrator-password")
            for name in secret_names:
                original = (deploy / ".local/secrets" / name).read_bytes()
                self.assertEqual((volumes / "postgres" / name).read_bytes(), original)
                self.assertEqual((volumes / "backend" / name).read_bytes(), original)
            self.assertFalse((volumes / "backend/postgres_admin_password").exists())
            self.assertTrue((volumes / "postgres/postgres_admin_password").is_file())
            self.assertEqual((volumes / "backend" / secret_names[0]).stat().st_mode & 0o777, 0o444)
            self.assertEqual((volumes / "tls/privkey.pem").stat().st_mode & 0o777, 0o600)
            self.assertEqual(run_import().returncode, 0)

            # Rotation is a coordinated DB operation; a changed source file
            # must not silently alter existing volume files.
            (deploy / ".local/secrets" / secret_names[0]).write_text("c" * 64)
            previous = (volumes / "backend" / secret_names[0]).read_bytes()
            rejected = run_import()
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("refusing implicit rotation", rejected.stderr)
            self.assertEqual((volumes / "backend" / secret_names[0]).read_bytes(), previous)

    def test_local_deployment_does_not_require_daemon_host_paths(self):
        compose = (DEPLOY / "compose.yaml").read_text()
        self.assertNotIn(".local/", compose)
        self.assertNotIn("secrets:\n    file:", compose)
        self.assertIn("context: ..", compose)
        self.assertIn("context: ../src/frontend", compose)
        self.assertIn("context: ../src/backend/sharewise", compose)


if __name__ == "__main__":
    unittest.main()
