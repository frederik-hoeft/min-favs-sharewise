"""Regression tests for the Docker CLI boundary of the image smoke test.

These mocks do not build images or establish Nginx runtime correctness. They verify
that HTTP requests target the running container, including with a remote daemon.
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SMOKE = ROOT / "deploy/scripts/test-images.sh"

MOCK_DOCKER = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
    version|build|rm|cp) ;;
    start)
        if [[ "$FAKE_SCENARIO" == start-error ]]; then
            printf 'unable to start frontend container\n' >&2
            exit 125
        fi
        ;;
    image)
        if [[ "$*" == *'org.opencontainers.image.version'* ]]; then
            printf '%s\n' p26ab-b-smoke
        elif [[ "$*" == *'org.opencontainers.image.revision'* ]]; then
            printf '%s\n' "$FAKE_REVISION"
        else
            exit 2
        fi
        ;;
    create)
        printf '%s\n' smoke-frontend
        ;;
    run)
        if [[ "$*" == *'find /usr/share/nginx/html/_next/static'* ]]; then
            printf '%s\n' /usr/share/nginx/html/_next/static/chunk.js
        fi
        ;;
    exec)
        if [[ "$FAKE_SCENARIO" == stopped-on-start && "$*" == *'/health'* ]]; then
            printf 'cannot exec in a stopped state\n' >&2
            exit 128
        fi
        if [[ "$FAKE_SCENARIO" == transient-not-ready && "$*" == *'/health'* && ! -e "$FAKE_READY_MARKER" ]]; then
            touch "$FAKE_READY_MARKER"
            printf 'wget: cannot connect to remote host\n' >&2
            exit 1
        fi
        if [[ "$*" == *'/no-such-route'* ]]; then
            if [[ "$FAKE_SCENARIO" == bad-404 ]]; then
                printf '  HTTP/1.1 503 Service Unavailable\n' >&2
            else
                printf '  HTTP/1.1 404 Not Found\n' >&2
            fi
            exit 1
        fi
        if [[ "$FAKE_SCENARIO" == bad-https && "$*" == *'https://sharewise-smoke.test/app'* ]]; then
            printf 'wget: cannot connect to remote host\n' >&2
            exit 1
        fi
        if [[ "$FAKE_SCENARIO" == not-ready && "$*" == *'http://127.0.0.1:8081/health' ]]; then
            printf 'wget: cannot connect to remote host\n' >&2
            exit 1
        fi
        ;;
    inspect)
        if [[ "$*" == *'.State.Running'* ]]; then
            if [[ "$FAKE_SCENARIO" == stopped-on-start ]]; then
                printf 'false\n'
            else
                printf 'true\n'
            fi
        else
            printf 'exited (exit code 1)\n'
        fi
        ;;
    logs)
        printf 'nginx startup failed: fixture diagnostic\n' >&2
        ;;
    *)
        printf 'unexpected container command: %s\n' "$*" >&2
        exit 2
        ;;
esac
"""


class ProductionImageSmokeTests(unittest.TestCase):
    def run_smoke(self, scenario: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            docker = root / "mock-docker"
            docker.write_text(MOCK_DOCKER)
            docker.chmod(0o755)
            # Keep the error-path test fast without changing the production timeout.
            sleep = root / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o755)
            calls = root / "calls.txt"
            revision = subprocess.check_output(
                ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True
            ).strip()
            env = os.environ | {
                "CONTAINER_CLI": str(docker),
                "FAKE_DOCKER_LOG": str(calls),
                "FAKE_SCENARIO": scenario,
                "FAKE_REVISION": revision,
                "FAKE_READY_MARKER": str(root / "ready-on-next-attempt"),
                "PATH": f"{root}:{os.environ['PATH']}",
            }
            result = subprocess.run(
                ["bash", str(SMOKE)], cwd=ROOT, env=env,
                capture_output=True, text=True, check=False,
            )
            return result, calls.read_text()

    def test_http_checks_run_in_container_without_published_ports(self) -> None:
        result, calls = self.run_smoke("ok")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            "create --add-host sharewise-smoke.test:127.0.0.1 "
            "-e SHAREWISE_PUBLIC_HOST=sharewise-smoke.test sharewise-frontend:", calls
        )
        self.assertIn("start smoke-frontend", calls)
        self.assertIn("cp ", calls)
        self.assertIn("exec smoke-frontend wget -q -O /dev/null http://127.0.0.1:8081/health", calls)
        self.assertIn("exec smoke-frontend wget -S -O /dev/null --no-check-certificate https://sharewise-smoke.test/no-such-route", calls)
        self.assertIn("exec smoke-frontend wget -q --no-check-certificate -O /dev/null https://sharewise-smoke.test/_next/static/chunk.js", calls)
        self.assertNotIn(" -p ", calls)
        self.assertNotIn("port smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)
        self.assertLess(calls.index("/health"), calls.index("nginx -t"))

    def test_transient_connection_refusal_is_retried_silently(self) -> None:
        result, calls = self.run_smoke("transient-not-ready")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("wget: cannot connect", result.stderr)
        self.assertEqual(calls.count("http://127.0.0.1:8081/health"), 2)
        self.assertIn("inspect --format {{.State.Running}} smoke-frontend", calls)
        self.assertIn("nginx -t", calls)

    def test_readiness_failure_prints_container_logs_and_cleans_up(self) -> None:
        result, calls = self.run_smoke("not-ready")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Frontend did not become ready", result.stderr)
        self.assertIn("nginx startup failed: fixture diagnostic", result.stderr)
        self.assertIn("inspect --format", calls)
        self.assertIn("logs smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)

    def test_stopped_container_reports_startup_logs(self) -> None:
        result, calls = self.run_smoke("stopped-on-start")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Frontend stopped before becoming ready", result.stderr)
        self.assertIn("Frontend failed to start or validate", result.stderr)
        self.assertIn("nginx startup failed: fixture diagnostic", result.stderr)
        self.assertIn("inspect --format", calls)
        self.assertIn("logs smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)

    def test_docker_start_failure_reports_container_logs(self) -> None:
        result, calls = self.run_smoke("start-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unable to start frontend container", result.stderr)
        self.assertIn("nginx startup failed: fixture diagnostic", result.stderr)
        self.assertIn("logs smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)

    def test_https_route_failure_reports_target_and_container_diagnostics(self) -> None:
        result, calls = self.run_smoke("bad-https")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Frontend HTTPS request failed: https://sharewise-smoke.test/app", result.stderr)
        self.assertIn("nginx startup failed: fixture diagnostic", result.stderr)
        self.assertIn("logs smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)

    def test_unexpected_error_is_not_mistaken_for_404(self) -> None:
        result, calls = self.run_smoke("bad-404")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected HTTP 404", result.stderr)
        self.assertIn("503 Service Unavailable", result.stderr)
        self.assertIn("rm -f smoke-frontend", calls)


if __name__ == "__main__":
    unittest.main()
