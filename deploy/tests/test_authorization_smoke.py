"""Disposable frontend E2E packaging/selection contract (not deployed test evidence)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AuthorizationSmokeContractTests(unittest.TestCase):
    def test_fixture_artifact_is_separate_and_default_backend_stays_production(self):
        dockerfile = (ROOT / 'src/backend/sharewise/Dockerfile').read_text()
        self.assertIn('FROM runtime AS authorization-smoke', dockerfile)
        self.assertIn('target/test-classes sharewise/deploymentfixture', dockerfile)
        self.assertIn('PropertiesLauncher', dockerfile)
        self.assertTrue(dockerfile.rstrip().endswith('FROM runtime AS production'))
        self.assertNotIn('authorization-smoke', (ROOT / 'deploy/compose.yaml').read_text())
        self.assertIn('sharewise-p26ab-smoke', (ROOT / 'src/backend/sharewise/src/test/java/sharewise/deploymentfixture/AuthorizationSmokeApplication.java').read_text())

    def test_runner_uses_actual_test_sources_but_production_build_excludes_them(self):
        frontend = ROOT / 'src/frontend'
        self.assertIn('tests', (frontend / '.dockerignore').read_text().splitlines())
        self.assertNotIn('tests', (frontend / 'Dockerfile.e2e.dockerignore').read_text().splitlines())
        runner = (frontend / 'Dockerfile.e2e').read_text()
        for required in ('npm ci', 'COPY . .', 'USER node', 'test:e2e', 'numPassedTests < 1'):
            self.assertIn(required, runner)
        self.assertIn('tests/deployed/**/*.e2e.ts', (frontend / 'tests/vitest.e2e.config.mts').read_text())
        self.assertIn('tests/**/*.test.{ts,tsx}', (frontend / 'tests/vitest.config.mts').read_text())

    def test_runner_has_only_edge_and_public_ca_input(self):
        override = (ROOT / 'deploy/compose.authorization-smoke.yaml').read_text()
        runner = override.split('  frontend-e2e:', 1)[1]
        self.assertIn('ports: !reset []', override)
        self.assertIn('target: authorization-smoke', override)
        self.assertIn('networks: [edge]', runner)
        for forbidden in ('volumes:', 'secrets:', 'docker.sock', 'networks: [data]', 'privileged: true', 'ports:'):
            self.assertNotIn(forbidden, runner)
        self.assertIn('read_only: true', runner)
        transport = (ROOT / 'src/frontend/tests/deployed/https-transport.ts').read_text()
        self.assertIn('rejectUnauthorized: true', transport)
        self.assertIn('checkServerIdentity(url.hostname, certificate)', transport)
        self.assertNotIn('NODE_TLS_REJECT_UNAUTHORIZED', transport)
