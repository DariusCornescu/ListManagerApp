#!/usr/bin/env python3
"""Fail a test suite that ran nothing, failed, or skipped a test nobody agreed to skip.

A green job is not proof that tests ran: a wrong filter matches zero tests, a missing
environment variable skips a whole file, and both still exit 0. This reads the results the
runner wrote and decides for itself.

Inputs (any mix, one suite per call):
  --junit GLOB          JUnit XML from pytest (--junitxml), Gradle or the emulator run
  --flutter-json FILE   output of `flutter test --reporter json`

Allowed skips live in .github/test-skips.txt, one per line:
  <suite> <pattern>  # reason
<pattern> is an fnmatch pattern over "<class or file>.<test name>". A line without a reason
is rejected, so every skip says why it is acceptable.

Writes a table to $GITHUB_STEP_SUMMARY when it is set. Standard library only.
"""
from __future__ import annotations

import argparse
import fnmatch
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Case:
    identity: str  # "<class or file>.<test name>"
    outcome: str  # "passed" | "failed" | "skipped"


@dataclass
class Report:
    suite: str
    cases: list[Case] = field(default_factory=list)
    allowed_skips: list[Case] = field(default_factory=list)
    unexpected_skips: list[Case] = field(default_factory=list)

    @property
    def failed(self) -> list[Case]:
        return [c for c in self.cases if c.outcome == "failed"]

    @property
    def passed(self) -> int:
        return sum(1 for c in self.cases if c.outcome == "passed")


class AllowlistError(ValueError):
    pass


def read_allowlist(text: str, suite: str) -> list[str]:
    """Patterns allowed to skip in `suite`. Lines: `<suite> <pattern>  # reason`."""
    patterns = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        body, _, reason = line.partition("#")
        parts = body.split()
        if len(parts) != 2 or not reason.strip():
            raise AllowlistError(f"line {number}: expected '<suite> <pattern>  # reason', got {raw!r}")
        if parts[0] == suite:
            patterns.append(parts[1])
    return patterns


def parse_junit(xml_text: str) -> list[Case]:
    root = ET.fromstring(xml_text)
    cases = []
    for case in root.iter("testcase"):
        classname = case.get("classname") or ""
        name = case.get("name") or ""
        identity = f"{classname}.{name}" if classname else name
        if case.find("failure") is not None or case.find("error") is not None:
            outcome = "failed"
        elif case.find("skipped") is not None:
            outcome = "skipped"
        else:
            outcome = "passed"
        cases.append(Case(identity, outcome))
    return cases


def parse_flutter_json(lines: list[str]) -> list[Case]:
    """`flutter test --reporter json`: one JSON event per line."""
    suites: dict[int, str] = {}
    tests: dict[int, tuple[str, str]] = {}
    cases = []
    for line in lines:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        kind = event.get("type")
        if kind == "suite":
            suite = event["suite"]
            suites[suite["id"]] = Path(suite.get("path") or "").name
        elif kind == "testStart":
            test = event["test"]
            tests[test["id"]] = (suites.get(test.get("suiteID"), ""), test.get("name", ""))
        elif kind == "testDone":
            if event.get("hidden"):
                continue  # the runner's own "loading" pseudo-tests
            file_name, name = tests.get(event["testID"], ("", str(event["testID"])))
            if event.get("skipped"):
                outcome = "skipped"
            elif event.get("result") == "success":
                outcome = "passed"
            else:
                outcome = "failed"
            cases.append(Case(f"{file_name}.{name}" if file_name else name, outcome))
    return cases


def evaluate(suite: str, cases: list[Case], allowed: list[str]) -> Report:
    report = Report(suite, cases)
    for case in cases:
        if case.outcome != "skipped":
            continue
        if any(fnmatch.fnmatchcase(case.identity, pattern) for pattern in allowed):
            report.allowed_skips.append(case)
        else:
            report.unexpected_skips.append(case)
    return report


def problems(report: Report, min_tests: int) -> list[str]:
    found = []
    if len(report.cases) < min_tests:
        found.append(f"{len(report.cases)} tests ran, at least {min_tests} expected")
    found += [f"failed: {c.identity}" for c in report.failed]
    found += [f"skipped without an entry in .github/test-skips.txt: {c.identity}" for c in report.unexpected_skips]
    return found


def summary_markdown(report: Report, issues: list[str], limit: int = 20) -> str:
    status = "✅" if not issues else "❌"
    lines = [
        f"### {status} {report.suite}",
        "",
        "| Tests | Passed | Failed | Skipped (allowed) | Skipped (unexpected) |",
        "|---:|---:|---:|---:|---:|",
        f"| {len(report.cases)} | {report.passed} | {len(report.failed)} | "
        f"{len(report.allowed_skips)} | {len(report.unexpected_skips)} |",
    ]
    if issues:
        lines += ["", *[f"- {issue}" for issue in issues[:limit]]]
        if len(issues) > limit:
            lines.append(f"- … and {len(issues) - limit} more")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--suite", required=True, help="name used in the summary and in test-skips.txt")
    parser.add_argument("--junit", action="append", default=[], help="glob of JUnit XML files (repeatable)")
    parser.add_argument("--flutter-json", help="file written by flutter test --reporter json")
    parser.add_argument("--skips", default=".github/test-skips.txt")
    parser.add_argument("--min-tests", type=int, default=1)
    args = parser.parse_args(argv)

    cases: list[Case] = []
    files = sorted({path for pattern in args.junit for path in glob.glob(pattern, recursive=True)})
    for path in files:
        cases += parse_junit(Path(path).read_text(encoding="utf-8"))
    if args.flutter_json:
        cases += parse_flutter_json(Path(args.flutter_json).read_text(encoding="utf-8").splitlines())
    if args.junit and not files:
        print(f"::error::{args.suite}: no JUnit files matched {args.junit}")

    skips_path = Path(args.skips)
    try:
        allowed = read_allowlist(skips_path.read_text(encoding="utf-8"), args.suite) if skips_path.exists() else []
    except AllowlistError as error:
        print(f"::error::{skips_path}: {error}")
        return 1

    report = evaluate(args.suite, cases, allowed)
    issues = problems(report, args.min_tests)
    markdown = summary_markdown(report, issues)
    print(markdown)
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(markdown)
    for issue in issues:
        print(f"::error::{args.suite}: {issue}")
    return 1 if issues else 0


if __name__ == "__main__":
    # Results can hold non-ASCII (emoji, Romanian test names); a Windows console may not.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
