"""Tests for the CI helper scripts. Run: python -m unittest discover -s .github/scripts/tests"""
from __future__ import annotations

import datetime as dt
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_ai_attribution as attribution  # noqa: E402
import check_committed_secrets as secrets  # noqa: E402
import check_test_report as report  # noqa: E402
import release_notes as notes  # noqa: E402
import render_rollback as rollback  # noqa: E402


JUNIT = """<?xml version="1.0"?>
<testsuites>
  <testsuite name="a" tests="4">
    <testcase classname="com.x.RankerTest" name="ranks"/>
    <testcase classname="com.x.RankerTest" name="fails"><failure message="boom"/></testcase>
    <testcase classname="com.x.LocalCatalogSegmentationBenchmarkTest" name="whole"><skipped/></testcase>
    <testcase classname="com.x.OtherTest" name="quiet"><skipped/></testcase>
  </testsuite>
</testsuites>"""


class TestReportTest(unittest.TestCase):
    def test_junit_outcomes(self):
        cases = report.parse_junit(JUNIT)
        self.assertEqual(
            [c.outcome for c in cases], ["passed", "failed", "skipped", "skipped"]
        )
        self.assertEqual(cases[0].identity, "com.x.RankerTest.ranks")

    def test_allowed_and_unexpected_skips(self):
        allowed = report.read_allowlist(
            "# comment\nandroid-unit *.LocalCatalogSegmentationBenchmarkTest.*  # needs a catalog\n"
            "flutter x.*  # other suite\n",
            "android-unit",
        )
        result = report.evaluate("android-unit", report.parse_junit(JUNIT), allowed)
        self.assertEqual([c.identity for c in result.allowed_skips], ["com.x.LocalCatalogSegmentationBenchmarkTest.whole"])
        self.assertEqual([c.identity for c in result.unexpected_skips], ["com.x.OtherTest.quiet"])
        issues = report.problems(result, min_tests=1)
        self.assertEqual(len(issues), 2)  # one failure, one unexpected skip

    def test_empty_suite_fails(self):
        result = report.evaluate("backend", [], [])
        self.assertEqual(report.problems(result, min_tests=1), ["0 tests ran, at least 1 expected"])

    def test_min_tests(self):
        result = report.evaluate("e2e", [report.Case("a.b", "passed")], [])
        self.assertTrue(report.problems(result, min_tests=3))

    def test_allowlist_needs_a_reason(self):
        with self.assertRaises(report.AllowlistError):
            report.read_allowlist("android-unit *.Foo.*\n", "android-unit")

    def test_flutter_json(self):
        events = [
            {"type": "suite", "suite": {"id": 0, "path": "/w/flutter_ui/test/backend_contract_test.dart"}},
            {"type": "testStart", "test": {"id": 1, "name": "loading x", "suiteID": 0}},
            {"type": "testDone", "testID": 1, "result": "success", "hidden": True, "skipped": False},
            {"type": "testStart", "test": {"id": 2, "name": "logs in", "suiteID": 0}},
            {"type": "testDone", "testID": 2, "result": "success", "hidden": False, "skipped": True},
            {"type": "suite", "suite": {"id": 3, "path": "test/api_client_test.dart"}},
            {"type": "testStart", "test": {"id": 4, "name": "parses", "suiteID": 3}},
            {"type": "testDone", "testID": 4, "result": "success", "hidden": False, "skipped": False},
            {"type": "testStart", "test": {"id": 5, "name": "breaks", "suiteID": 3}},
            {"type": "testDone", "testID": 5, "result": "error", "hidden": False, "skipped": False},
        ]
        cases = report.parse_flutter_json(["noise"] + [json.dumps(e) for e in events])
        self.assertEqual(
            cases,
            [
                report.Case("backend_contract_test.dart.logs in", "skipped"),
                report.Case("api_client_test.dart.parses", "passed"),
                report.Case("api_client_test.dart.breaks", "failed"),
            ],
        )

    def test_summary_mentions_counts(self):
        result = report.evaluate("backend", [report.Case("a.b", "passed")], [])
        self.assertIn("| 1 | 1 | 0 | 0 | 0 |", report.summary_markdown(result, []))


class AttributionTest(unittest.TestCase):
    def test_flags_ai_coauthor_and_generated_line(self):
        message = (
            "feat: add x\n\nBody.\n\n"
            "Co-Authored-By: Claude Opus <noreply@anthropic.com>\n"
            "\U0001F916 Generated with [Claude Code](https://claude.com/claude-code)\n"
        )
        found = attribution.findings(message)
        self.assertEqual(len(found), 2)
        self.assertTrue(found[0].startswith("Co-Authored-By"))

    def test_human_coauthor_and_plain_mentions_pass(self):
        message = (
            "fix: tune the ranker\n\nThe Claude API docs were not used here.\n"
            "Co-Authored-By: Ana Pop <ana@example.com>\n"
        )
        self.assertEqual(attribution.findings(message), [])

    def test_other_tools(self):
        self.assertTrue(attribution.findings("Co-authored-by: GitHub Copilot <copilot@github.com>"))
        self.assertTrue(attribution.findings("co-authored-by: ChatGPT <x@openai.com>"))


class SecretsTest(unittest.TestCase):
    def test_forbidden_files(self):
        self.assertIsNotNone(secrets.path_problem("android-native/release.jks"))
        self.assertIsNotNone(secrets.path_problem("android-native/app/google-services.json"))
        self.assertIsNotNone(secrets.path_problem("backend-fastapi/.env"))
        self.assertIsNotNone(
            secrets.path_problem("android-native/app/src/main/java/com/darius/listmanager/network/ApiConfig.kt")
        )
        self.assertIsNone(secrets.path_problem("backend-fastapi/.env.example"))
        self.assertIsNone(secrets.path_problem("android-native/app/src/main/java/x/ApiConfig.kt.example"))

    def test_token_shapes(self):
        github = "gh" + "p_" + "a" * 36
        render = "rn" + "d_" + "B" * 30
        google = "AI" + "za" + "C" * 35
        pem = "-----BEGIN" + " RSA PRIVATE KEY-----"
        for text in (github, render, google, pem):
            self.assertTrue(secrets.content_problems(f"x = '{text}'"), text[:6])
        self.assertEqual(secrets.content_problems("token = os.environ['GITHUB_TOKEN']"), [])

    def test_service_account(self):
        body = '{"type": "service_account", "private_key": "' + "-----BEGIN" + ' PRIVATE KEY-----\\n"}'
        self.assertIn("service account key", secrets.content_problems(body))

    def test_scan_respects_allowlist(self):
        files = {"docs/a.md": "fine", "tests/fixture.txt": "AI" + "za" + "D" * 35}
        found = secrets.scan(list(files), files.get, [])
        self.assertEqual(found, ["tests/fixture.txt: looks like a Google API key"])
        self.assertEqual(secrets.scan(list(files), files.get, ["tests/*"]), [])

    def test_allowlist_needs_reason(self):
        with self.assertRaises(ValueError):
            secrets.read_allowlist("tests/*\n")


class ReleaseNotesTest(unittest.TestCase):
    def test_next_tag(self):
        day = dt.date(2026, 11, 16)
        self.assertEqual(notes.next_tag(day, set()), "v2026.11.16")
        self.assertEqual(notes.next_tag(day, {"v2026.11.16"}), "v2026.11.16.2")
        self.assertEqual(notes.next_tag(day, {"v2026.11.16", "v2026.11.16.2"}), "v2026.11.16.3")

    def test_parse_and_group(self):
        log = (
            "Merge pull request #58 from DariusCornescu/claude/qa\x1fFix PDF names and ranker recall\x1e"
            "Merge pull request #57 from DariusCornescu/fix/x\x1ffix: log each update status once\n\nmore\x1e"
            "Merge branch 'development' into feat/y\x1f\x1e"
            "Merge pull request #56 from DariusCornescu/ci\x1fci: add mobile test gates\x1e"
            "Merge pull request #55 from DariusCornescu/feat\x1ffeat(android): swipe to delete\x1e"
        )
        pulls = notes.parse_merges(log)
        self.assertEqual([p.number for p in pulls], [58, 57, 56, 55])
        grouped = notes.group(pulls)
        self.assertEqual(list(grouped), ["Features", "Fixes", "CI and build", "Other"])
        text = notes.render(pulls, "v2026.11.01", "v2026.11.16", "DariusCornescu/ListManagerApp")
        self.assertIn("- Swipe to delete (#55)", text)
        self.assertIn("- Log each update status once (#57)", text)
        self.assertIn("compare/v2026.11.01...v2026.11.16", text)

    def test_no_previous_tag(self):
        self.assertIn("First tagged release.", notes.render([], None, "v1", "o/r"))


class RollbackTest(unittest.TestCase):
    DEPLOYS = [
        {"deploy": {"id": "dep-new", "status": "live", "commit": {"id": "bad"}}, "cursor": "1"},
        {"deploy": {"id": "dep-redeploy", "status": "deactivated", "commit": {"id": "bad"}}, "cursor": "2"},
        {"deploy": {"id": "dep-failed", "status": "build_failed", "commit": {"id": "older"}}, "cursor": "3"},
        {"deploy": {"id": "dep-good", "status": "deactivated", "commit": {"id": "good"}}, "cursor": "4"},
    ]

    def test_selects_previous_live_deploy(self):
        self.assertEqual(rollback.select_target(self.DEPLOYS, "bad"), rollback.Target("dep-good", "good"))

    def test_ignores_deploys_newer_than_the_failing_one(self):
        queued = [{"deploy": {"id": "q", "status": "deactivated", "commit": {"id": "newer"}}}] + self.DEPLOYS
        self.assertEqual(rollback.select_target(queued, "bad").deploy_id, "dep-good")

    def test_no_target_when_failing_deploy_is_unknown(self):
        self.assertIsNone(rollback.select_target(self.DEPLOYS, "missing"))

    def test_decide(self):
        target = rollback.Target("dep-good", "good")
        self.assertEqual(rollback.decide(target, ["android-native/x.kt"], False)[0], "rolled_back")
        action, reason = rollback.decide(
            target, ["backend-fastapi/alembic/versions/0009_add_x.py"], False
        )
        self.assertEqual(action, "escalate")
        self.assertIn("schema", reason)
        self.assertEqual(rollback.decide(target, [], True)[0], "escalate")
        self.assertEqual(rollback.decide(None, [], False)[0], "escalate")


if __name__ == "__main__":
    unittest.main()
