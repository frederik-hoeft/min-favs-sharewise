"""Static production packaging checks; real PostgreSQL acceptance is separate."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy/compose.yaml"
PRODUCTION = (
    ROOT / "src/backend/sharewise/src/main/resources/application-production.properties"
)
BOOTSTRAP = ROOT / "deploy/postgres/10-create-roles.sh"


class ProductionComposeContractTests(unittest.TestCase):
    def test_backend_production_properties_require_both_secret_files(self):
        properties = PRODUCTION.read_text()
        self.assertIn("spring.config.import=configtree:/run/secrets/", properties)
        self.assertIn("spring.datasource.password=${sharewise.database.app-password}", properties)
        self.assertIn("spring.flyway.password=${sharewise.database.migrator-password}", properties)
        self.assertNotIn("_dev", properties)
        self.assertIn(
            "ENV SPRING_PROFILES_ACTIVE=production",
            (ROOT / "src/backend/sharewise/Dockerfile").read_text(),
        )

    def test_compose_does_not_publish_backend_or_database_ports(self):
        # Structural checks are intentionally independent of a Python YAML
        # dependency. Docker Compose config -q provides the authoritative check.
        yaml = COMPOSE.read_text()
        for name in ("postgres", "backend", "frontend"):
            self.assertRegex(yaml, rf"(?m)^  {name}:$")
        postgres = yaml.split("\n  postgres:\n", 1)[1].split("\n  backend:\n", 1)[0]
        backend = yaml.split("\n  backend:\n", 1)[1].split("\n  frontend:\n", 1)[0]
        frontend = yaml.split("\n  frontend:\n", 1)[1].split("\n  asset-loader:\n", 1)[0]
        self.assertNotIn("    ports:", postgres)
        self.assertNotIn("    ports:", backend)
        self.assertIn("    ports:", frontend)
        self.assertIn("condition: service_healthy", backend)
        self.assertIn("condition: service_healthy", frontend)
        self.assertIn("    internal: true", yaml)
        self.assertIn("/var/lib/postgresql", postgres)
        self.assertNotIn("sharewise_app_dev", yaml)
        self.assertNotIn("sharewise_migrator_dev", yaml)
        self.assertNotIn("postgres_dev", yaml)

    def test_named_volumes_replace_daemon_side_bind_mounts(self):
        yaml = COMPOSE.read_text()
        self.assertNotIn("\nsecrets:", yaml)
        self.assertNotIn("SHAREWISE_SECRETS_DIR", yaml)
        self.assertNotIn("SHAREWISE_TLS_DIR", yaml)
        self.assertIn("postgres_secrets:/run/secrets:ro", yaml)
        self.assertIn("backend_secrets:/run/secrets:ro", yaml)
        self.assertIn("tls_certs:/etc/nginx/tls:ro", yaml)
        self.assertIn("postgres_secrets:/assets/postgres", yaml)
        self.assertIn("backend_secrets:/assets/backend", yaml)
        self.assertIn("tls_certs:/assets/tls", yaml)
        self.assertIn("network_mode: none", yaml)
        self.assertNotIn("/docker-entrypoint-initdb.d/10-create-roles.sh:ro", yaml)
        self.assertIn("COPY deploy/postgres/10-create-roles.sh", (ROOT / "deploy/postgres/Dockerfile").read_text())
        dockerignore = (ROOT / "deploy/postgres/Dockerfile.dockerignore").read_text()
        self.assertIn("\n**\n", dockerignore)
        self.assertIn("!deploy/postgres/10-create-roles.sh", dockerignore)
        self.assertNotIn("!deploy/.local", dockerignore)
        script = BOOTSTRAP.read_text()
        self.assertIn('--dbname "$POSTGRES_DB"', script)
        self.assertIn("CREATE ROLE sharewise_migrator", script)
        self.assertIn("CREATE ROLE sharewise_app", script)
        self.assertIn("GRANT CONNECT, CREATE", script)
        self.assertNotIn("_dev", script)
        self.assertIn("*[!0123456789abcdef]*", script)

    def test_role_bootstrap_rejects_invalid_secrets_without_calling_psql(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            secret_dir = root / "secrets"
            secret_dir.mkdir()
            script = root / "init.sh"
            script.write_text(BOOTSTRAP.read_text().replace("/run/secrets/", f"{secret_dir}/"))
            binary = root / "bin"
            binary.mkdir()
            called = root / "psql-called"
            psql = binary / "psql"
            psql.write_text("#!/bin/sh\nprintf invoked > \"$PSQL_CALLED\"\ncat >/dev/null\n")
            psql.chmod(0o755)
            env = dict(os.environ, PATH=f"{binary}:{os.environ.get('PATH', '')}",
                       POSTGRES_USER="postgres", POSTGRES_DB="sharewise", PSQL_CALLED=str(called))
            app = secret_dir / "sharewise.database.app-password"
            mig = secret_dir / "sharewise.database.migrator-password"

            def invoke():
                called.unlink(missing_ok=True)
                return subprocess.run(["sh", str(script)], env=env, capture_output=True, text=True)

            app.write_text("a" * 64)
            mig.write_text("b" * 64)
            completed = invoke()
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(completed.stdout, "")
            self.assertTrue(called.exists())

            for invalid in ("b" * 64 + "\n", "password", "a" * 64):
                mig.write_text(invalid)
                completed = invoke()
                self.assertNotEqual(completed.returncode, 0)
                self.assertFalse(called.exists())
                self.assertNotIn("a" * 64, completed.stderr)
                self.assertNotIn(invalid, completed.stderr)

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required")
    def test_local_fixture_uses_distinct_untracked_secrets_without_newlines(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            scripts = root / "deploy/scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(ROOT / "deploy/scripts/prepare-local.sh", scripts / "prepare-local.sh")
            script = scripts / "prepare-local.sh"
            completed = subprocess.run(["bash", str(script)], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            fixture = root / "deploy/.local"
            secret_dir = fixture / "secrets"
            names = (
                "postgres_admin_password",
                "sharewise.database.app-password",
                "sharewise.database.migrator-password",
            )
            values = [(secret_dir / name).read_bytes() for name in names]
            for value in values:
                self.assertRegex(value.decode("ascii"), r"^[0-9a-f]{64}$")
            self.assertEqual(len(set(values)), len(values))
            self.assertEqual(secret_dir.stat().st_mode & 0o777, 0o700)
            self.assertEqual((fixture / "tls/privkey.pem").stat().st_mode & 0o777, 0o600)
            self.assertEqual((fixture / "compose.env").stat().st_mode & 0o777, 0o600)
            self.assertEqual((secret_dir / names[0]).stat().st_mode & 0o777, 0o600)
            second = subprocess.run(["bash", str(script)], capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual((secret_dir / names[0]).read_bytes(), values[0])


if __name__ == "__main__":
    unittest.main()
