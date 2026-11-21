"""Reject BOMs and non-UTF-8 content in tracked source text.

CI checks the working tree; the pre-commit hook checks the Git index, not
potentially different unstaged working-tree contents. Only files explicitly
marked binary in .gitattributes are exempted from UTF-8 decoding; text=auto
must not silently permit BOM-less UTF-16 or unknown binary formats.
"""

import argparse
import os
from pathlib import Path
import subprocess
import sys

BOMS = (
    (b"\x00\x00\xfe\xff", "UTF-32 BE BOM"),
    (b"\xff\xfe\x00\x00", "UTF-32 LE BOM"),
    (b"\xef\xbb\xbf", "UTF-8 BOM"),
    (b"\xfe\xff", "UTF-16 BE BOM"),
    (b"\xff\xfe", "UTF-16 LE BOM"),
)


def git(*args: str, data: bytes | None = None) -> bytes:
    return subprocess.run(
        ["git", *args], input=data, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True,
    ).stdout


def candidate_paths(staged: bool) -> list[bytes]:
    if staged:
        listing = git("diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR")
    else:
        listing = git("ls-files", "--cached", "-z")
    return [name for name in listing.split(b"\x00") if name]


def text_attributes(paths: list[bytes]) -> dict[bytes, bytes]:
    if not paths:
        return {}
    listing = git("check-attr", "--cached", "-z", "--stdin", "text",
                  data=b"\x00".join(paths) + b"\x00")
    entries = listing.split(b"\x00")
    if entries[-1] != b"":
        raise ValueError("unexpected git check-attr output")
    fields = entries[:-1]
    if len(fields) % 3:
        raise ValueError("unexpected git check-attr response format")
    return {fields[i]: fields[i + 2] for i in range(0, len(fields), 3)}


def source_bytes(path: bytes, staged: bool) -> bytes:
    if staged:
        return git("show", ":" + os.fsdecode(path))
    source = Path(os.fsdecode(path))
    if source.is_symlink():
        return os.fsencode(os.readlink(source))
    return source.read_bytes()


def check(path: bytes, content: bytes, attribute: bytes) -> list[str]:
    issues = []
    for marker, description in BOMS:
        if content.startswith(marker):
            issues.append(description)
            break

    if attribute == b"unset":
        return issues  # Explicitly binary in .gitattributes.
    # Git text=auto is content-sensitive: it can misclassify BOM-less UTF-16
    # source files as binary. Only an explicit -text/binary attribute exempts
    # a tracked file from this check.
    if b"\x00" in content:
        issues.append("NUL byte in text file")

    try:
        content.decode("utf-8")
    except UnicodeDecodeError as exc:
        issues.append(f"invalid UTF-8 at byte {exc.start}")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true",
                        help="check staged additions/modifications rather than all tracked working-tree files")
    args = parser.parse_args()
    try:
        paths = candidate_paths(args.staged)
        attributes = text_attributes(paths)
        failures = 0
        for path in paths:
            issues = check(path, source_bytes(path, args.staged), attributes.get(path, b"auto"))
            if issues:
                print(f"{os.fsdecode(path)}: {', '.join(issues)}", file=sys.stderr)
                failures += 1
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"source encoding check could not run: {exc}", file=sys.stderr)
        return 2

    if failures:
        print(f"Rejected {failures} file(s): tracked text must be UTF-8 without a BOM.",
              file=sys.stderr)
        return 1
    print(f"Source encoding verified ({len(paths)} {'staged' if args.staged else 'tracked'} files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
