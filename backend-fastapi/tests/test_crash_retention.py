from datetime import datetime, timedelta

import pytest
from fastapi import status

from app import crash_retention, models
from app.config import settings


@pytest.fixture(autouse=True)
def fresh_purge_clock(monkeypatch):
    monkeypatch.setattr(crash_retention, "_last_purge", None)


def _report(db_session, age_days, text="trace"):
    report = models.CrashReport(
        stacktrace=text,
        signature="sig-" + text,
        created_at=datetime.utcnow() - timedelta(days=age_days),
    )
    db_session.add(report)
    db_session.commit()
    return report


def _ages(db_session):
    db_session.expire_all()
    return sorted(
        round((datetime.utcnow() - r.created_at).days)
        for r in db_session.query(models.CrashReport).all()
    )


def test_purge_removes_only_reports_past_retention(db_session):
    for age in (400, 181, 179, 1):
        _report(db_session, age, text=f"t{age}")

    assert crash_retention.purge_old(db_session) == 2
    db_session.commit()
    assert _ages(db_session) == [1, 179]


def test_zero_days_turns_the_purge_off(db_session, monkeypatch):
    monkeypatch.setattr(settings, "CRASH_REPORT_RETENTION_DAYS", 0)
    _report(db_session, 1000)

    assert crash_retention.purge_old(db_session) == 0
    db_session.commit()
    assert _ages(db_session) == [1000]


def test_a_new_report_purges_at_most_once_a_day(client, db_session, monkeypatch):
    _report(db_session, 365, text="old-1")
    calls = []
    real = crash_retention.purge_old
    monkeypatch.setattr(crash_retention, "purge_old", lambda db: calls.append(1) or real(db))

    first = client.post("/api/crashes", json={"stacktrace": "new one"})
    assert first.status_code == status.HTTP_200_OK
    assert set(first.json()) >= {"id", "signature", "created_at", "stacktrace"}
    assert _ages(db_session) == [0]

    _report(db_session, 365, text="old-2")
    client.post("/api/crashes", json={"stacktrace": "another"})
    assert len(calls) == 1
    assert _ages(db_session) == [0, 0, 365]  # not purged again the same day


def test_purged_reports_leave_groups_and_dashboard(client, db_session, admin_headers):
    _report(db_session, 365, text="ancient")
    _report(db_session, 2, text="recent")

    crash_retention.purge_old(db_session)
    db_session.commit()

    groups = client.get("/api/admin/crashes/groups", headers=admin_headers).json()
    assert [g["signature"] for g in groups] == ["sig-recent"]
    assert client.get("/api/admin/dashboard", headers=admin_headers).json()["crashes_count"] == 1


def test_startup_purge_uses_its_own_session(db_session, monkeypatch):
    from tests.conftest import TestingSessionLocal

    _report(db_session, 500)
    monkeypatch.setattr("app.database.SessionLocal", TestingSessionLocal)

    crash_retention.purge_at_startup()

    assert _ages(db_session) == []
