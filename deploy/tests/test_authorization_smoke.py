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

    def test_vitest_token_location_is_ephemeral_and_writable(self):
        dockerfile = (ROOT / 'src/frontend/Dockerfile.e2e').read_text()
        runner = dockerfile.split('FROM test-source AS e2e-runner', 1)[1]
        self.assertIn('ENV XDG_DATA_HOME=/tmp/sharewise-e2e-data', runner)
        override = (ROOT / 'deploy/compose.authorization-smoke.yaml').read_text()
        self.assertIn('target: e2e-runner', override)
        config = (ROOT / 'src/frontend/tests/vitest.e2e.config.mts').read_text()
        self.assertIn('cache: false', config)
        self.assertIn('fsModuleCache: false', config)
        self.assertIn('/tmp:rw,nosuid,nodev,size=32m,mode=1777', override)
        self.assertIn('read_only: true', override)

    def test_browser_fixture_is_opt_in_and_uses_actual_frontend(self):
        frontend = ROOT / 'src/frontend'
        browser = (ROOT / 'deploy/compose.authorization-browser.yaml').read_text()
        self.assertIn('target: authorization-browser', browser)
        self.assertIn('127.0.0.1:${SHAREWISE_HTTPS_PORT:', browser)
        self.assertNotIn('authorization-browser', (ROOT / 'deploy/compose.yaml').read_text())
        self.assertNotIn('authorization-browser', (frontend / 'Dockerfile').read_text())
        dockerfile = (frontend / 'Dockerfile.e2e').read_text()
        self.assertIn("cp tests/deployed/browser/page.tsx 'app/(app)/app/authorization-smoke/page.tsx'", dockerfile)
        self.assertIn('useAuthenticatedApis()', (frontend / 'tests/deployed/browser/page.tsx').read_text())
        self.assertFalse((frontend / 'app/(app)/app/authorization-smoke').exists())
