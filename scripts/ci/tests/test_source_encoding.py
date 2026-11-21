"""Contract tests for the Git-index and working-tree encoding checks."""

from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


CHECKER = Path(__file__).resolve().parents[1] / "check-source-encoding.py"


class SourceEncodingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Encoding Test")
        self.git("config", "user.email", "encoding-test@example.invalid")
        self.write(".gitattributes", b"* text=auto eol=lf\n*.pdf binary\n")
        self.write("sample.java", b"class Sample {}\n")
        self.git("add", ".")
        self.git("commit", "-qm", "baseline")

    def git(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["git", *args], cwd=self.root, check=True,
                              capture_output=True, text=True)

    def write(self, path: str, data: bytes) -> None:
        dest = self.root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)

    def check(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(CHECKER), *args], cwd=self.root,
                              capture_output=True, text=True)

    def test_clean_tracked_text_passes_and_untracked_file_is_ignored(self) -> None:
        self.write("untracked.xml", b"\xef\xbb\xbf<root />\n")
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_covers_bom_in_xml_java_shell_and_gitlab_configuration(self) -> None:
        for name in ("src/pom.xml", "Sample.java", "build.sh", ".gitlab-ci.yml"):
            with self.subTest(name=name):
                self.write(name, b"\xef\xbb\xbfexample\n")
                self.git("add", name)
                result = self.check("--staged")
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn(name, result.stderr)
                self.assertIn("UTF-8 BOM", result.stderr)
                self.git("reset", "-q", "HEAD", "--", name)
                (self.root / name).unlink()

    def test_staged_content_is_checked_even_if_worktree_was_fixed(self) -> None:
        self.write("sample.java", b"\xef\xbb\xbfclass Sample {}\n")
        self.git("add", "sample.java")
        self.write("sample.java", b"class Sample {}\n")
        self.assertEqual(1, self.check("--staged").returncode)
        self.assertEqual(0, self.check().returncode)

    def test_invalid_utf8_and_utf16_bom_are_rejected(self) -> None:
        self.write("config.yml", b"abc\xff\n")
        result = self.check()
        self.assertEqual(0, result.returncode, "untracked files should not be checked")
        self.git("add", "config.yml")
        result = self.check("--staged")
        self.assertEqual(1, result.returncode)
        self.assertIn("invalid UTF-8", result.stderr)
        self.write("config.yml", b"\xff\xfea\x00")
        result = self.check()
        self.assertEqual(1, result.returncode, "the working-tree scan must detect tracked edits")
        self.git("add", "config.yml")
        result = self.check("--staged")
        self.assertEqual(1, result.returncode)
        self.assertIn("UTF-16 LE BOM", result.stderr)

    def test_binary_files_and_deletions_are_not_mistaken_for_text(self) -> None:
        self.write("snapshot.pdf", b"%PDF-1.7\x80\x00\xff")
        self.git("add", "snapshot.pdf")
        self.assertEqual(0, self.check("--staged").returncode)
        self.git("commit", "-qm", "binary")
        (self.root / "snapshot.pdf").unlink()
        self.git("add", "-u")
        self.assertEqual(0, self.check("--staged").returncode)


if __name__ == "__main__":
    unittest.main()
