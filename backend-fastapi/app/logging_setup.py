"""Server logging: INFO from the app, and no tokens in any line."""
import logging
import os
import re

# The live socket carries its JWT in the URL, and uvicorn logs paths with their query string.
_TOKEN_IN_TEXT = re.compile(r"(token=)[^&\s\"']+")


def _redact(value):
    return _TOKEN_IN_TEXT.sub(r"\1REDACTED", value) if isinstance(value, str) else value


class RedactTokens(logging.Filter):
    """Hides token values. Redacts the arguments in place, because uvicorn's access formatter
    reads them by position."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = _redact(record.msg)
        if isinstance(record.args, tuple):
            record.args = tuple(_redact(arg) for arg in record.args)
        elif isinstance(record.args, dict):
            record.args = {key: _redact(arg) for key, arg in record.args.items()}
        return True


def configure_logging() -> None:
    """Show INFO from the app, and redact tokens on every handler that prints server lines."""
    root = logging.getLogger()
    if not root.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)s [%(name)s] %(message)s"))
        root.addHandler(handler)
    root.setLevel(os.environ.get("LOG_LEVEL", "INFO").upper())
    for name in ("", "uvicorn", "uvicorn.access"):
        for handler in logging.getLogger(name).handlers:
            if not any(isinstance(f, RedactTokens) for f in handler.filters):
                handler.addFilter(RedactTokens())
