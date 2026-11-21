"""Git-mode checks must work even if host permissions mask the index."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


CHECKER = Path(__file__).resolve().parents[1] / "check-script-modes.py"


class ScriptModeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)

    def add(self, path: str, contents: str, executable: bool) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents)
        subprocess.run(["git", "-C", str(self.root), "add", path], check=True)
        subprocess.run(
            ["git", "-C", str(self.root), "update-index",
             "--chmod=+x" if executable else "--chmod=-x", "--", path],
            check=True,
        )

    def check(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), str(self.root)],
            capture_output=True, text=True, check=False,
        )

    def test_executable_shebang_passes(self) -> None:
        self.add("scripts/run.sh", "#!/bin/sh\necho ok\n", executable=True)
        self.assertEqual(self.check().returncode, 0)

    def test_nonexecutable_shebang_fails_even_if_filesystem_is_executable(self) -> None:
        self.add("scripts/run.sh", "#!/bin/sh\necho ok\n", executable=False)
        (self.root / "scripts/run.sh").chmod(0o755)
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("scripts/run.sh", result.stderr)

    def test_sourced_shell_fragment_without_shebang_is_allowed(self) -> None:
        self.add("scripts/lib.sh", "# Sourced only\nfoo=bar\n", executable=False)
        self.assertEqual(self.check().returncode, 0)

    def test_shebang_in_file_without_shell_extension_is_checked(self) -> None:
        self.add("mvnw", "#!/bin/sh\nexit 0\n", executable=False)
        self.assertEqual(self.check().returncode, 1)


if __name__ == "__main__":
    unittest.main()
