"""Production logging: startup must not silence the server's logs, and tokens must not reach them.

Each test starts a fresh interpreter the way uvicorn does: logging is configured first, then the
app is imported, which runs the migrations.
"""
import os
import subprocess
import sys
import textwrap
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]

SECRET = "abc.def.ghi"


def _run(tmp_path, body: str) -> str:
    script = textwrap.dedent(
        """
        import logging, logging.config
        import uvicorn.config
        logging.config.dictConfig(uvicorn.config.LOGGING_CONFIG)
        import app.main
        """
    ) + textwrap.dedent(body)
    env = {
        **os.environ,
        "SECRET_KEY": "test-secret-key-not-for-prod",
        "RATE_LIMIT_ENABLED": "false",
        "DATABASE_URL": f"sqlite:///{(tmp_path / 'logging.db').as_posix()}",
    }
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=BACKEND_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    return result.stdout + result.stderr


def test_startup_keeps_server_and_app_logs(tmp_path):
    output = _run(
        tmp_path,
        """
        logging.getLogger("uvicorn.error").info("server line still visible")
        logging.getLogger("app.routers.admin").info("app info line")
        try:
            1 / 0
        except ZeroDivisionError:
            logging.getLogger("app.main").exception("request failed")
        """,
    )
    assert "server line still visible" in output
    assert "app info line" in output
    assert "request failed" in output
    assert "ZeroDivisionError" in output


def test_tokens_are_redacted_from_websocket_and_access_lines(tmp_path):
    output = _run(
        tmp_path,
        f"""
        logging.getLogger("uvicorn.error").info(
            '%s - "WebSocket %s" [accepted]', "1.2.3.4:5", "/ws?token={SECRET}"
        )
        logging.getLogger("uvicorn.access").info(
            '%s - "%s %s HTTP/%s" %d', "1.2.3.4:5", "GET", "/x?token={SECRET}&page=2", "1.1", 200
        )
        logging.getLogger("app.main").warning("bad url /ws?token={SECRET}")
        """,
    )
    assert SECRET not in output
    assert '"WebSocket /ws?token=REDACTED" [accepted]' in output
    assert "/x?token=REDACTED&page=2" in output
    assert "bad url /ws?token=REDACTED" in output
