#!/usr/bin/env python3
"""Fail a pull request whose commits or description credit an AI assistant.

The repository's rule: no `Co-Authored-By:` trailer naming an AI tool, and no
"Generated with Claude Code" line, in commits or in pull request descriptions. Rewriting a
pushed commit leaves the old one in the pull request's timeline, so this runs before merge.

  check_ai_attribution.py --range BASE..HEAD [--event-path $GITHUB_EVENT_PATH]

Standard library only; reads commit messages with git.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

AI_NAMES = r"(claude|anthropic|copilot|chatgpt|openai|gpt-?\d|gemini|cursor\s*agent|devin)"
PATTERNS = [
    re.compile(rf"^\s*co-authored-by:.*\b{AI_NAMES}\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^\s*co-authored-by:.*noreply@anthropic\.com", re.IGNORECASE | re.MULTILINE),
    re.compile(r"generated\s+(with|by)\s+\[?claude", re.IGNORECASE),
    re.compile(r"\U0001F916\s*generated\s+(with|by)", re.IGNORECASE),
]


def findings(text: str) -> list[str]:
    """The offending lines in `text`, in order, without duplicates."""
    found: list[str] = []
    for pattern in PATTERNS:
        for match in pattern.finditer(text):
            start = text.rfind("\n", 0, match.start()) + 1
            end = text.find("\n", match.end())
            line = text[start : end if end != -1 else len(text)].strip()
            if line not in found:
                found.append(line)
    return found


def commits(revision_range: str) -> list[tuple[str, str]]:
    out = subprocess.run(
        ["git", "log", "--format=%H%x1f%B%x1e", revision_range],
        check=True, capture_output=True, text=True, encoding="utf-8",
    ).stdout
    result = []
    for record in out.split("\x1e"):
        record = record.strip("\n")
        if record:
            sha, _, message = record.partition("\x1f")
            result.append((sha, message))
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--range", required=True, help="commits to check, e.g. origin/development..HEAD")
    parser.add_argument("--event-path", help="GITHUB_EVENT_PATH; its pull_request.body is checked too")
    args = parser.parse_args(argv)

    problems = []
    for sha, message in commits(args.range):
        problems += [f"commit {sha[:7]}: {line}" for line in findings(message)]
    if args.event_path:
        with open(args.event_path, encoding="utf-8") as handle:
            body = (json.load(handle).get("pull_request") or {}).get("body") or ""
        problems += [f"pull request description: {line}" for line in findings(body)]

    for problem in problems:
        print(f"::error::AI attribution is not allowed in this repository. {problem}")
    if problems:
        print("Remove the line: amend or reword the commit, and edit the pull request description.")
        return 1
    print("No AI attribution in the commits or the pull request description.")
    return 0


if __name__ == "__main__":
    # Results can hold non-ASCII (emoji, Romanian test names); a Windows console may not.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
