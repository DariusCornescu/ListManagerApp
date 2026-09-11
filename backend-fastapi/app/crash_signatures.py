"""Stable crash signatures derived from normalized Android stack frames."""

import hashlib
import re


_LINE_NUMBER = re.compile(r":\d+(?=\))")
_ANONYMOUS_CLASS = re.compile(r"\$\d+(?=[.$])")
_MAX_FRAMES = 8


def normalize_stacktrace(stacktrace: str) -> str:
    """Return the exception type and stable top frames used for grouping.

    Exception messages and source line numbers are deliberately excluded because
    they often contain request-specific values or move between builds.
    """

    lines = [line.strip() for line in stacktrace.splitlines() if line.strip()]
    if not lines:
        return "unknown"

    exception_type = lines[0].split(":", 1)[0].strip()
    frames = []
    for line in lines[1:]:
        if not line.startswith("at "):
            continue
        normalized = _LINE_NUMBER.sub(":#", line)
        normalized = _ANONYMOUS_CLASS.sub("$#", normalized)
        frames.append(normalized)
        if len(frames) == _MAX_FRAMES:
            break

    return "\n".join([exception_type, *frames])


def crash_signature(stacktrace: str) -> str:
    canonical = normalize_stacktrace(stacktrace)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
