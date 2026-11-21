"""Acceptance-stack lifecycle tests with a fake Docker CLI, without Docker."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


DEPLOY = Path(__file__).resolve().parents[1]
FAKE_DOCKER = r'''#!/bin/sh
printf '%s\n' "$*" >> "$MOCK_DOCKER_CALLS"
case "$1 $2" in
    "volume inspect") exit 1 ;; # Fresh test project.
    "volume ls") exit 0 ;;
    "inspect -f") echo '{}'; exit 0 ;; # No published backend or DB ports.
esac
[ "${1:-}" = compose ] || exit 0
shift
if [ "${1:-}" = -p ]; then shift 2; fi
case "${1:-}" in
    down) [ "${MOCK_FAILURE:-}" != cleanup ] || exit 37; exit 0 ;;
    up) [ "${MOCK_FAILURE:-}" != startup ] || exit 23; exit 0 ;;
    ps) [ "${2:-}" != -q ] || echo "id-$3"; exit 0 ;;
    exec) ;;
    *) exit 0 ;;
esac
shift
[ "${1:-}" = -T ] && shift
service="${1:-}"
shift
case "$service" in
    frontend)
        case "$*" in
            *SHAREWISE_HTTPS_PORT*) echo 18443 ;;
            *) echo localhost ;;
        esac ;;
    backend)
        case "$*" in
            *--write-out*http://localhost/example*) echo '308 https://localhost:18443/example' ;;
            *--write-out*) echo 421 ;;
            *https://localhost/api/system/v1/health*)
                if [ "${MOCK_FAILURE:-}" = health ]; then echo '{"status":"DOWN"}';
                else echo '{"status":"UP"}'; fi ;;
        esac ;;
    postgres)
        case "$*" in
            *'SELECT installed_by FROM flyway.flyway_schema_history'*) echo sharewise_migrator ;;
            *'SELECT value FROM identity.p26ab_compose_smoke_marker'*) echo persisted ;;
            *p26ab_forbidden*|*'SELECT * FROM flyway.flyway_schema_history'*) exit 1 ;;
        esac ;;
esac
'''


class ComposeLifecycleTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.deploy = root / "deploy"
        scripts = self.deploy / "scripts"
        scripts.mkdir(parents=True)
        for name in ("test-compose.sh", "cleanup.sh", "lib-compose.sh"):
            shutil.copy2(DEPLOY / "scripts" / name, scripts / name)
        importer = scripts / "import-assets.sh"
        importer.write_text("#!/bin/sh\nexit 0\n")
        importer.chmod(0o755)
        (self.deploy / ".env").write_text("COMPOSE_PROJECT_NAME=sharewise-p26ab-local\n")
        self.cli = root / "docker"
        self.cli.write_text(FAKE_DOCKER)
        self.cli.chmod(0o755)
        self.call_log = root / "calls.jsonl"
        self.call_log.write_text("")
        self.env = os.environ | {
            "CONTAINER_CLI": str(self.cli),
            "MOCK_DOCKER_CALLS": str(self.call_log),
        }
        self.env.pop("COMPOSE_PROJECT_NAME", None)
        self.env.pop("SHAREWISE_COMPOSE_TEST_PROJECT", None)

    def run_smoke(self, *args, **env):
        return subprocess.run(
            ["bash", str(self.deploy / "scripts/test-compose.sh"), *args],
            env=self.env | env, text=True, capture_output=True,
        )

    def calls(self):
        return self.call_log.read_text().splitlines()

    def down_calls(self):
        return [args for args in self.calls() if " down " in f" {args} "]

    def test_success_removes_only_disposable_stack_and_volumes(self):
        result = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("and its volumes were removed", result.stdout)
        self.assertEqual(self.down_calls(), [
            "compose -p sharewise-p26ab-smoke down --volumes --remove-orphans",
        ])
        self.assertNotIn("sharewise-p26ab-local", " ".join(self.down_calls()))
        self.assertTrue((self.deploy / ".env").exists())

    def test_keep_retains_successful_stack(self):
        result = self.run_smoke("--keep")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("retained (--keep)", result.stdout)
        self.assertEqual(self.down_calls(), [])

    def test_failed_acceptance_preserves_diagnostics(self):
        result = self.run_smoke(MOCK_FAILURE="health")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("preserved for diagnosis", result.stderr)
        self.assertEqual(self.down_calls(), [])

    def test_startup_failure_does_not_attempt_teardown(self):
        result = self.run_smoke(MOCK_FAILURE="startup")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("preserved for diagnosis", result.stderr)
        self.assertEqual(self.down_calls(), [])

    def test_cleanup_failure_does_not_report_success(self):
        result = self.run_smoke(MOCK_FAILURE="cleanup")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cleanup failed", result.stderr)
        self.assertIn("cleanup was incomplete", result.stderr)
        self.assertNotIn("checks passed.", result.stdout)

    def test_custom_disposable_project_is_removed(self):
        result = self.run_smoke(SHAREWISE_COMPOSE_TEST_PROJECT="sharewise-p26ab-smoke2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("-p", self.down_calls()[0])
        self.assertIn("sharewise-p26ab-smoke2", self.down_calls()[0])

    def test_non_disposable_name_is_rejected_before_docker(self):
        result = self.run_smoke(SHAREWISE_COMPOSE_TEST_PROJECT="sharewise-p26ab-other")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reserved", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_invalid_arguments_do_not_touch_docker(self):
        result = self.run_smoke("--unexpected")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
