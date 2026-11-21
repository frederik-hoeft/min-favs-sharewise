"""Reject tracked shebang scripts without Git's executable bit.

Read the Git index, not filesystem permissions: Windows/NTFS and devcontainer
mounts can make non-executable tracked files appear executable locally.
"""

from pathlib import Path
import subprocess
import sys


def main(root: Path) -> int:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--stage", "-z"],
        check=True,
        capture_output=True,
    )
    failures = []
    count = 0
    for item in result.stdout.split(b"\0"):
        if not item:
            continue
        metadata, encoded_path = item.split(b"\t", 1)
        mode = metadata.split(b" ", 1)[0]
        if mode not in (b"100644", b"100755"):
            continue
        path = encoded_path.decode("utf-8", errors="surrogateescape")
        with (root / path).open("rb") as source:
            shebang = source.read(2) == b"#!"
        if not shebang:
            continue
        count += 1
        if mode != b"100755":
            failures.append(path)

    if failures:
        for path in failures:
            print(
                f"Missing Git executable bit: {path} "
                f"(fix: git update-index --chmod=+x -- '{path}')",
                file=sys.stderr,
            )
        return 1
    print(f"Verified executable Git modes for {count} tracked shebang scripts.")
    return 0


if __name__ == "__main__":
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    raise SystemExit(main(root))
