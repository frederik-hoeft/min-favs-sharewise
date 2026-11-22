"""Static production packaging checks; real PostgreSQL acceptance is separate."""

import json
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

    def test_postgres_host_access_is_explicit_and_scoped_to_local_override(self):
        override = (ROOT / "deploy/compose.postgres-host.yaml").read_text()
        base = COMPOSE.read_text()
        example = (ROOT / "deploy/.env.example").read_text()
        devcontainer = (ROOT / ".devcontainer/docker-compose.yml").read_text()
        smoke = (ROOT / "deploy/scripts/test-compose.sh").read_text()
        cleanup = (ROOT / "deploy/scripts/cleanup.sh").read_text()

        # No default database host publishing; the optional override is never
        # selected by the checked-in .env template without explicit editing.
        postgres = base.split("\n  postgres:\n", 1)[1].split("\n  backend:\n", 1)[0]
        self.assertNotIn("    ports:", postgres)
        self.assertIn("# COMPOSE_FILE=compose.yaml:compose.postgres-host.yaml", example)
        self.assertIn("# SHAREWISE_POSTGRES_BIND=__HOST_GATEWAY_IPV4__", example)
        self.assertNotRegex(example, r"(?m)^COMPOSE_FILE=")
        self.assertIn("${SHAREWISE_POSTGRES_BIND:?", override)
        self.assertIn("${SHAREWISE_POSTGRES_PORT:-15432}:5432", override)
        self.assertIn("- postgres_host_access", override)
        self.assertIn("  postgres_host_access: {}", override)
        self.assertIn("    internal: true", base)
        self.assertIn("host.docker.internal:host-gateway", devcontainer)

        # Disposable test stacks must not inherit local host publication.
        self.assertIn("export COMPOSE_FILE=compose.yaml", smoke)
        self.assertIn("export COMPOSE_FILE=compose.yaml", cleanup)

    def test_local_deployment_ports_do_not_overlap_explicit_development_forwards(self):
        devcontainer = json.loads((ROOT / ".devcontainer/devcontainer.json").read_text())
        forwarded = set(devcontainer["forwardPorts"])
        self.assertEqual(forwarded, {3000, 8080})
        self.assertIs(
            devcontainer["customizations"]["vscode"]["settings"]["remote.autoForwardPorts"],
            False,
        )

        defaults = {
            "SHAREWISE_HTTP_PORT": 18080,
            "SHAREWISE_HTTPS_PORT": 8443,
        }
        example = (ROOT / "deploy/.env.example").read_text()
        compose = COMPOSE.read_text()
        for key, port in defaults.items():
            with self.subTest(key=key):
                self.assertNotIn(port, forwarded)
                self.assertIn(f"{key}={port}", example)
                self.assertIn(f"{key}={port}", example)
                self.assertIn("${" + key + f":-{port}" + "}", compose)

        acceptance_script = (ROOT / "deploy/scripts/test-compose.sh").read_text()
        self.assertIn("SHAREWISE_SMOKE_HTTP_PORT:-28080", acceptance_script)
        self.assertIn("SHAREWISE_SMOKE_HTTPS_PORT:-18443", acceptance_script)
        self.assertTrue({18080, 8443}.isdisjoint({28080, 18443}))
        self.assertTrue(forwarded.isdisjoint({28080, 18443}))

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
    def test_local_env_suggests_resolved_host_gateway_without_enabling_binding(self):
        cases = (
            ("172.19.0.1 STREAM host.docker.internal\n", "172.19.0.1"),
            ("300.19.0.1 STREAM host.docker.internal\n", None),
            ("127.0.0.1 STREAM host.docker.internal\n", None),
            ("", None),
        )
        for response, expected_address in cases:
            with self.subTest(response=response), tempfile.TemporaryDirectory() as dirname:
                root = Path(dirname)
                deploy = root / "deploy"
                scripts = deploy / "scripts"
                scripts.mkdir(parents=True)
                shutil.copy2(ROOT / "deploy/.env.example", deploy / ".env.example")
                shutil.copy2(ROOT / "deploy/scripts/prepare-local.sh", scripts / "prepare-local.sh")
                bin_dir = root / "bin"
                bin_dir.mkdir()
                getent = bin_dir / "getent"
                getent.write_text("""#!/bin/sh
[ "$1" = ahostsv4 ] && [ "$2" = host.docker.internal ] || exit 1
[ -n "$FAKE_HOST_GATEWAY_RESPONSE" ] || exit 2
printf '%s' "$FAKE_HOST_GATEWAY_RESPONSE"
""")
                getent.chmod(0o755)
                environment = os.environ | {
                    "PATH": f"{bin_dir}:{os.environ.get('PATH', '')}",
                    "FAKE_HOST_GATEWAY_RESPONSE": response,
                }
                command = ["bash", str(scripts / "prepare-local.sh")]
                first = subprocess.run(command, env=environment, capture_output=True, text=True)
                self.assertEqual(first.returncode, 0, first.stderr)
                rendered = (deploy / ".env").read_text()
                suggested = expected_address or "__HOST_GATEWAY_IPV4__"
                self.assertIn(f"# SHAREWISE_POSTGRES_BIND={suggested}\n", rendered)
                self.assertIn("# COMPOSE_FILE=compose.yaml:compose.postgres-host.yaml", rendered)
                self.assertNotRegex(rendered, r"(?m)^SHAREWISE_POSTGRES_BIND=")

                # Even when the detected address changes, bootstrap preserves
                # an already-created .env and its user-selected settings.
                environment["FAKE_HOST_GATEWAY_RESPONSE"] = "10.2.3.4 STREAM host.docker.internal\n"
                second = subprocess.run(command, env=environment, capture_output=True, text=True)
                self.assertEqual(second.returncode, 0, second.stderr)
                self.assertEqual((deploy / ".env").read_text(), rendered)

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required")
    def test_local_fixture_uses_distinct_untracked_secrets_without_newlines(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            scripts = root / "deploy/scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(ROOT / "deploy/.env.example", root / "deploy/.env.example")
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
            self.assertEqual((root / "deploy/.env").stat().st_mode & 0o777, 0o600)
            self.assertIn("SHAREWISE_HTTP_PORT=18080", (root / "deploy/.env").read_text())
            self.assertIn("SHAREWISE_PUBLIC_HOST=localhost", (root / "deploy/.env").read_text())
            cert = fixture / "tls/fullchain.pem"
            self.assertIn("DNS:localhost", subprocess.check_output(
                ["openssl", "x509", "-in", str(cert), "-noout", "-ext", "subjectAltName"],
                text=True))
            self.assertIn("IP Address:127.0.0.1", subprocess.check_output(
                ["openssl", "x509", "-in", str(cert), "-noout", "-ext", "subjectAltName"],
                text=True))
            self.assertEqual(subprocess.run(
                ["openssl", "x509", "-in", str(cert), "-checkend", str(300 * 86400), "-noout"],
                capture_output=True).returncode, 0)
            self.assertEqual((secret_dir / names[0]).stat().st_mode & 0o777, 0o600)
            second = subprocess.run(["bash", str(script)], capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual((secret_dir / names[0]).read_bytes(), values[0])

            # An old cert does not silently authorize a changed public hostname.
            new_env = root / "deploy/.env"
            new_env.write_text(new_env.read_text().replace(
                "SHAREWISE_PUBLIC_HOST=localhost", "SHAREWISE_PUBLIC_HOST=sharewise.test"))
            before = cert.read_bytes()
            mismatched = subprocess.run(
                ["bash", str(script)], capture_output=True, text=True)
            self.assertNotEqual(mismatched.returncode, 0)
            self.assertIn("certificate does not cover", mismatched.stderr)
            self.assertEqual(cert.read_bytes(), before)
            self.assertEqual((secret_dir / names[0]).read_bytes(), values[0])

            # A targeted TLS reset can issue a new cert without password rotation.
            (fixture / "tls/fullchain.pem").unlink()
            (fixture / "tls/privkey.pem").unlink()
            regenerated = subprocess.run(
                ["bash", str(script)], capture_output=True, text=True)
            self.assertEqual(regenerated.returncode, 0, regenerated.stderr)
            self.assertEqual((secret_dir / names[0]).read_bytes(), values[0])
            self.assertEqual(subprocess.run([
                "openssl", "x509", "-in", str(cert), "-checkhost", "sharewise.test", "-noout"
            ], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
