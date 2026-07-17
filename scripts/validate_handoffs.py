#!/usr/bin/env python3
"""Validate the permanent branch handoff structure."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

MAX_CHARACTERS = 42_000
INDEX_ROW = re.compile(
    r"^\|\s*`(?P<branch>[^`]+)`\s*\|.*"
    r"\[(?:handoff|main\.md)\]\((?P<handoff>[^)]+)\)\s*\|"
    r"\s*\[history\]\((?P<history>[^)]+)\)\s*\|$"
)
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*]\((?P<target>[^)]+)\)")


def run_git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def branch_paths(handoffs: Path, branch: str) -> tuple[Path, Path]:
    return handoffs / f"{branch}.md", handoffs / f"{branch}.history.md"


def read_text(path: Path, errors: list[str]) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        errors.append(f"{path}: cannot read as UTF-8: {exc}")
        return ""


def validate_sizes(markdown_files: list[Path], errors: list[str]) -> None:
    for path in markdown_files:
        text = read_text(path, errors)
        if len(text) >= MAX_CHARACTERS:
            errors.append(
                f"{path}: {len(text):,} characters; must be strictly below "
                f"{MAX_CHARACTERS:,}"
            )


def validate_links(markdown_files: list[Path], errors: list[str]) -> None:
    ignored_prefixes = ("http://", "https://", "mailto:", "#", "<")
    for path in markdown_files:
        text = read_text(path, errors)
        for match in MARKDOWN_LINK.finditer(text):
            raw_target = match.group("target").strip().split(maxsplit=1)[0]
            if "<" in raw_target or ">" in raw_target:
                continue
            target = raw_target.strip("<>")
            target = unquote(target.split("#", 1)[0])
            if not target or target.startswith(ignored_prefixes):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                errors.append(f"{path}: broken relative link: {target}")


def parse_index(handoffs: Path, errors: list[str]) -> dict[str, tuple[Path, Path]]:
    index = handoffs / "README.md"
    text = read_text(index, errors)
    entries: dict[str, tuple[Path, Path]] = {}
    for line in text.splitlines():
        match = INDEX_ROW.match(line)
        if not match:
            continue
        branch = match.group("branch")
        entries[branch] = (
            handoffs / match.group("handoff"),
            handoffs / match.group("history"),
        )
    if not entries:
        errors.append(f"{index}: no active branch rows found")
    return entries


def validate_active_pairs(
    root: Path,
    handoffs: Path,
    entries: dict[str, tuple[Path, Path]],
    errors: list[str],
) -> None:
    branches = run_git(root, "for-each-ref", "--format=%(refname:short)", "refs/heads")
    local_branches = set(branches.stdout.splitlines()) if branches.returncode == 0 else set()

    expected = set(entries) | local_branches
    for branch in sorted(expected):
        handoff, history = entries.get(branch, branch_paths(handoffs, branch))
        if not handoff.is_file():
            errors.append(f"{branch}: missing active handoff {handoff.relative_to(root)}")
        if not history.is_file():
            errors.append(f"{branch}: missing active history {history.relative_to(root)}")
        if branch not in entries:
            errors.append(f"{branch}: missing from docs/handoffs/README.md active index")

    for branch, (handoff, history) in entries.items():
        expected_handoff, expected_history = branch_paths(handoffs, branch)
        if handoff.resolve() != expected_handoff.resolve():
            errors.append(f"{branch}: index handoff does not mirror branch path")
        if history.resolve() != expected_history.resolve():
            errors.append(f"{branch}: index history does not mirror branch path")


def validate_archive_pairs(root: Path, handoffs: Path, errors: list[str]) -> None:
    archive = handoffs / "archive"
    if not archive.is_dir():
        errors.append(f"{archive.relative_to(root)}: archive directory missing")
        return

    files = {
        path.relative_to(archive).as_posix()
        for path in archive.rglob("*.md")
        if path.name != "README.md"
    }
    for relative in sorted(files):
        if relative.endswith(".history.md"):
            peer = relative.removesuffix(".history.md") + ".md"
        else:
            peer = relative.removesuffix(".md") + ".history.md"
        if peer not in files:
            errors.append(f"archive/{relative}: missing paired archive file {peer}")


def resolve_base_ref(root: Path, explicit: str | None) -> str | None:
    if explicit:
        return explicit
    github_base = os.environ.get("GITHUB_BASE_REF")
    if github_base:
        return f"origin/{github_base}"
    return None


def validate_append_only(
    root: Path, markdown_files: list[Path], base_ref: str | None, errors: list[str]
) -> None:
    if not base_ref:
        return
    if run_git(root, "rev-parse", "--verify", base_ref).returncode != 0:
        errors.append(f"base ref not found for append-only check: {base_ref}")
        return

    for path in markdown_files:
        relative = path.relative_to(root).as_posix()
        if path.name != "HISTORY.md" and not path.name.endswith(".history.md"):
            continue
        previous = run_git(root, "show", f"{base_ref}:{relative}")
        if previous.returncode != 0:
            continue
        current = read_text(path, errors)
        if not current.startswith(previous.stdout):
            errors.append(f"{relative}: append-only history changed or removed prior content")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-ref",
        help="Git ref used to verify append-only histories (default: origin/$GITHUB_BASE_REF)",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    handoffs = root / "docs" / "handoffs"
    errors: list[str] = []

    required = [
        root / "AGENTS.md",
        handoffs / "MASTER_HANDOFF.md",
        handoffs / "REPOSITORY.md",
        handoffs / "README.md",
        handoffs / "HISTORY.md",
        handoffs / "templates" / "BRANCH_HANDOFF.md",
        handoffs / "templates" / "BRANCH_HISTORY.md",
        handoffs / "templates" / "ARCHIVED_HANDOFF.md",
    ]
    for path in required:
        if not path.is_file():
            errors.append(f"required file missing: {path.relative_to(root)}")

    markdown_files = sorted(handoffs.rglob("*.md"))
    validate_sizes(markdown_files, errors)
    validate_links(markdown_files + [root / "AGENTS.md"], errors)
    entries = parse_index(handoffs, errors)
    validate_active_pairs(root, handoffs, entries, errors)
    validate_archive_pairs(root, handoffs, errors)
    validate_append_only(
        root, markdown_files, resolve_base_ref(root, args.base_ref), errors
    )

    if errors:
        print("Handoff validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        f"Handoff validation passed: {len(markdown_files)} Markdown files, "
        f"{len(entries)} active branches."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

