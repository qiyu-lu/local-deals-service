#!/usr/bin/env python3
"""Validate tracked Markdown links/fences and tracked CSV column counts."""

import argparse
import csv
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parent.parent
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")


def tracked_files(pattern):
    output = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", pattern],
        cwd=str(ROOT),
    )
    return [ROOT / item.decode("utf-8") for item in output.split(b"\0") if item]


def link_target(raw):
    value = raw.strip()
    if value.startswith("<") and ">" in value:
        value = value[1:value.index(">")]
    else:
        value = value.split(maxsplit=1)[0]
    return unquote(value.split("#", 1)[0])


def check_markdown(path):
    errors = []
    active_fence = None
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        fence = FENCE_RE.match(line)
        if fence:
            marker = fence.group(1)
            if active_fence is None:
                active_fence = marker[0]
            elif marker[0] == active_fence:
                active_fence = None
            continue
        if active_fence is not None:
            continue
        for match in LINK_RE.finditer(line):
            target = link_target(match.group(1))
            if not target or target.startswith(("http://", "https://", "mailto:", "/")):
                continue
            resolved = (path.parent / target).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                errors.append(f"{path.relative_to(ROOT)}:{number}: link escapes repository: {target}")
                continue
            if not resolved.exists():
                errors.append(f"{path.relative_to(ROOT)}:{number}: missing link target: {target}")
    if active_fence is not None:
        errors.append(f"{path.relative_to(ROOT)}: unclosed Markdown fence")
    return errors


def check_csv(path):
    errors = []
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = [(number, row) for number, row in enumerate(csv.reader(handle), 1) if row]
    if not rows:
        return errors
    expected = len(rows[0][1])
    for number, row in rows[1:]:
        if len(row) != expected:
            errors.append(
                f"{path.relative_to(ROOT)}:{number}: {len(row)} columns; expected {expected}"
            )
    return errors


def main():
    parser = argparse.ArgumentParser(
        description="Check tracked Markdown links/fences and CSV column counts."
    )
    parser.parse_args()

    errors = []
    markdown = tracked_files("*.md")
    csv_files = tracked_files("*.csv")
    for path in markdown:
        errors.extend(check_markdown(path))
    for path in csv_files:
        errors.extend(check_csv(path))

    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"docs check passed: {len(markdown)} Markdown files, {len(csv_files)} CSV files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
