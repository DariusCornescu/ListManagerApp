#!/usr/bin/env python3
"""Fail when a commit adds a signing key, a credentials file, or something shaped like a token.

The repository is public: a secret that reaches a commit has to be rotated, not just deleted.
On a pull request it checks the files the pull request adds or changes; on a push, every
tracked file.

  check_committed_secrets.py [--range BASE...HEAD] [--allowlist .github/secret-allowlist.txt]

Allowlist lines: `<path glob>  # reason`. A reason is required. Standard library only.
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

# Files that hold secrets by their nature, whatever is inside.
FORBIDDEN_FILES = [
    ("*.jks", "Android signing keystore"),
    ("*.keystore", "Android signing keystore"),
    ("*.p12", "PKCS#12 key store"),
    ("*.pfx", "PKCS#12 key store"),
    ("google-services.json", "Firebase client config with API keys"),
    ("secrets.properties", "local Android secrets"),
    ("local.properties", "local Android SDK and secrets"),
    (".env", "environment file"),
    ("*.env", "environment file"),
    ("ApiConfig.kt", "git-ignored local API config"),
]
ALLOWED_NAMES = {".env.example", ".env.sample", "ApiConfig.kt.example"}

# Built from parts so this file does not match its own patterns.
_BEGIN = "-----BEGIN"
CONTENT_PATTERNS = [
    (re.compile(_BEGIN + r" (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY-----"), "private key"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"), "GitHub token"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{60,}\b"), "GitHub fine-grained token"),
    (re.compile(r"\brnd_[A-Za-z0-9]{24,}\b"), "Render API key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key id"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"), "Google API key"),
    (re.compile(r"\bsk-(?:ant-|proj-)?[A-Za-z0-9_\-]{32,}\b"), "AI provider API key"),
]
SERVICE_ACCOUNT = re.compile(r'"type"\s*:\s*"service_account"')
PRIVATE_KEY_FIELD = re.compile(r'"private_key"\s*:\s*"' + _BEGIN)
MAX_BYTES = 2_000_000


def read_allowlist(text: str) -> list[str]:
    globs = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        body, _, reason = line.partition("#")
        if not body.strip() or not reason.strip():
            raise ValueError(f"line {number}: expected '<path glob>  # reason', got {raw!r}")
        globs.append(body.strip())
    return globs


def path_problem(path: str) -> str | None:
    name = PurePosixPath(path).name
    if name in ALLOWED_NAMES:
        return None
    for pattern, what in FORBIDDEN_FILES:
        if fnmatch.fnmatchcase(name, pattern):
            return what
    return None


def content_problems(text: str) -> list[str]:
    found = [what for pattern, what in CONTENT_PATTERNS if pattern.search(text)]
    if SERVICE_ACCOUNT.search(text) and PRIVATE_KEY_FIELD.search(text):
        found.append("service account key")
    return found


def scan(paths: list[str], read, allowed: list[str]) -> list[str]:
    """Problems as 'path: what'. `read(path)` returns text, or None for binary/unreadable."""
    problems = []
    for path in paths:
        if any(fnmatch.fnmatchcase(path, pattern) for pattern in allowed):
            continue
        what = path_problem(path)
        if what:
            problems.append(f"{path}: {what} must not be committed")
            continue
        text = read(path)
        if text is not None:
            problems += [f"{path}: looks like a {what}" for what in content_problems(text)]
    return problems


def git_lines(*args: str) -> list[str]:
    out = subprocess.run(["git", *args], check=True, capture_output=True, text=True, encoding="utf-8").stdout
    return [line for line in out.splitlines() if line]


def read_file(path: str) -> str | None:
    file = Path(path)
    try:
        if not file.is_file() or file.stat().st_size > MAX_BYTES:
            return None
        data = file.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:8192]:
        return None  # binary
    return data.decode("utf-8", errors="replace")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--range", help="BASE...HEAD of a pull request; without it, every tracked file")
    parser.add_argument("--allowlist", default=".github/secret-allowlist.txt")
    args = parser.parse_args(argv)

    if args.range:
        paths = git_lines("diff", "--name-only", "--diff-filter=ACMR", args.range)
    else:
        paths = git_lines("ls-files")
    allow_file = Path(args.allowlist)
    try:
        allowed = read_allowlist(allow_file.read_text(encoding="utf-8")) if allow_file.exists() else []
    except ValueError as error:
        print(f"::error::{allow_file}: {error}")
        return 1

    problems = scan(paths, read_file, allowed)
    for problem in problems:
        # Only the path and the kind are printed, never the matched value: the log is public.
        print(f"::error::{problem}")
    if problems:
        print("If a secret was pushed, rotate it: removing it from the branch does not un-publish it.")
        return 1
    print(f"No secrets found in {len(paths)} file(s).")
    return 0


if __name__ == "__main__":
    # Results can hold non-ASCII (emoji, Romanian test names); a Windows console may not.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
