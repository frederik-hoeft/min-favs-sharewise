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
SMOKE = ROOT / "scripts/test-production-images.sh"

MOCK_DOCKER = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
    version|build|rm) ;;
    image)
        if [[ "$*" == *'org.opencontainers.image.version'* ]]; then
            printf '%s\n' p26ab-b-smoke
        elif [[ "$*" == *'org.opencontainers.image.revision'* ]]; then
            printf '%s\n' "$FAKE_REVISION"
        else
            exit 2
        fi
        ;;
    run)
        if [[ "$*" == *' -d sharewise-frontend:'* || "$*" == 'run -d sharewise-frontend:'* ]]; then
            printf '%s\n' smoke-frontend
        elif [[ "$*" == *'find /usr/share/nginx/html/_next/static'* ]]; then
            printf '%s\n' /usr/share/nginx/html/_next/static/chunk.js
        fi
        ;;
    exec)
        if [[ "$*" == *'/no-such-route'* ]]; then
            if [[ "$FAKE_SCENARIO" == bad-404 ]]; then
                printf '  HTTP/1.1 503 Service Unavailable\n' >&2
            else
                printf '  HTTP/1.1 404 Not Found\n' >&2
            fi
            exit 1
        fi
        if [[ "$FAKE_SCENARIO" == not-ready && "$*" == *'http://127.0.0.1/' ]]; then
            printf 'wget: cannot connect to remote host\n' >&2
            exit 1
        fi
        ;;
    inspect)
        printf 'exited (exit code 1)\n'
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
        self.assertIn("run -d sharewise-frontend:", calls)
        self.assertIn("exec smoke-frontend wget -q -O /dev/null http://127.0.0.1/", calls)
        self.assertIn("exec smoke-frontend wget -S -O /dev/null http://127.0.0.1/no-such-route", calls)
        self.assertIn("exec smoke-frontend wget -q -O /dev/null http://127.0.0.1/_next/static/chunk.js", calls)
        self.assertNotIn(" -p ", calls)
        self.assertNotIn("port smoke-frontend", calls)
        self.assertIn("rm -f smoke-frontend", calls)

    def test_readiness_failure_prints_container_logs_and_cleans_up(self) -> None:
        result, calls = self.run_smoke("not-ready")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Frontend did not become ready", result.stderr)
        self.assertIn("nginx startup failed: fixture diagnostic", result.stderr)
        self.assertIn("inspect --format", calls)
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
