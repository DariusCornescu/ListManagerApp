#!/usr/bin/env python3
"""Release tags and notes for ListManager.

  release_notes.py next-tag [--date YYYY-MM-DD]
      Prints the next CalVer tag: vYYYY.MM.DD, or vYYYY.MM.DD.N when that day already has one.
  release_notes.py notes --to REF [--from TAG] [--repo owner/name]
      Prints Markdown notes for the pull requests merged into development between the two.

Pull requests are grouped by the conventional prefix of their title (CLAUDE.md requires
`feat:`, `fix:`, `sec:`, ...). Reads git only, so it works without an API token.
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from dataclasses import dataclass

GROUPS = [
    ("feat", "Features"),
    ("fix", "Fixes"),
    ("sec", "Security"),
    ("perf", "Performance"),
    ("refactor", "Refactoring"),
    ("test", "Tests"),
    ("docs", "Documentation"),
    ("ci", "CI and build"),
    ("build", "CI and build"),
    ("chore", "CI and build"),
]
OTHER = "Other"
PREFIX = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?!?:\s*", re.IGNORECASE)
MERGE_SUBJECT = re.compile(r"^Merge pull request #(?P<number>\d+) from ")


@dataclass(frozen=True)
class PullRequest:
    number: int
    title: str


def next_tag(day: dt.date, existing: set[str]) -> str:
    base = f"v{day:%Y.%m.%d}"
    if base not in existing:
        return base
    n = 2
    while f"{base}.{n}" in existing:
        n += 1
    return f"{base}.{n}"


def parse_merges(log: str) -> list[PullRequest]:
    """`git log --first-parent --merges --format=%s%x1f%b%x1e` output -> merged pull requests."""
    found = []
    for record in log.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        subject, _, body = record.partition("\x1f")
        match = MERGE_SUBJECT.match(subject.strip())
        if not match:
            continue  # "Merge branch 'development' into ..." and other non-PR merges
        title = next((line.strip() for line in body.splitlines() if line.strip()), subject.strip())
        found.append(PullRequest(int(match["number"]), title))
    return found


def group(pulls: list[PullRequest]) -> dict[str, list[PullRequest]]:
    names = dict(GROUPS)
    order = list(dict.fromkeys(name for _, name in GROUPS)) + [OTHER]
    grouped: dict[str, list[PullRequest]] = {name: [] for name in order}
    for pull in pulls:
        match = PREFIX.match(pull.title)
        grouped[names.get(match["type"].lower(), OTHER) if match else OTHER].append(pull)
    return {name: items for name, items in grouped.items() if items}


def render(pulls: list[PullRequest], previous: str | None, current: str, repo: str | None) -> str:
    lines = []
    if not pulls:
        lines.append("No pull requests were merged since the previous release.")
    for name, items in group(pulls).items():
        lines += [f"### {name}", ""]
        for pull in items:
            title = PREFIX.sub("", pull.title, count=1) or pull.title
            lines.append(f"- {title[:1].upper()}{title[1:]} (#{pull.number})")
        lines.append("")
    if repo and previous:
        lines.append(f"**Full diff:** https://github.com/{repo}/compare/{previous}...{current}")
    elif not previous:
        lines.append("First tagged release.")
    return "\n".join(lines).rstrip() + "\n"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    tag_cmd = sub.add_parser("next-tag")
    tag_cmd.add_argument("--date", type=dt.date.fromisoformat, default=None)
    notes_cmd = sub.add_parser("notes")
    notes_cmd.add_argument("--to", required=True)
    notes_cmd.add_argument("--from", dest="previous")
    notes_cmd.add_argument("--repo")
    args = parser.parse_args(argv)

    if args.command == "next-tag":
        day = args.date or dt.datetime.now(dt.timezone.utc).date()
        existing = set(git("tag", "--list", "v*").split())
        print(next_tag(day, existing))
        return 0

    revision = f"{args.previous}..{args.to}" if args.previous else args.to
    log = git("log", "--first-parent", "--merges", "--format=%s%x1f%b%x1e", revision)
    print(render(parse_merges(log), args.previous, args.to, args.repo), end="")
    return 0


if __name__ == "__main__":
    # Results can hold non-ASCII (emoji, Romanian test names); a Windows console may not.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
