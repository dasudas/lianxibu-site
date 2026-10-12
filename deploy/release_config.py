"""Read a controlled backend EnvironmentFile; never expose or execute its values."""
from __future__ import annotations
import hashlib
import re
from pathlib import Path

MAX_BYTES = 1024 * 1024
REQUIRED_FLAGS = (
    "SITE_BROWSER_ENABLED", "SITE_CONTENT_ENABLED", "SITE_ARTICLES_ENABLED",
    "SITE_SOURCES_ENABLED", "SITE_EXECUTION_ENABLED", "SITE_COLLECTOR_ENABLED",
    "SITE_SOURCES_DAY_ROSTER_ENABLED", "SITE_EXECUTION_PREPARATION_ENABLED",
    "SITE_EXECUTION_WORKER_ENABLED",
    "SITE_CONTENT_AVAILABILITY_ENABLED", "SITE_CONTENT_PROPAGATION_ENABLED", "SITE_EXECUTION_CATALOG_PREPARATION_ENABLED", "SITE_EXECUTION_REPORT_PREPARATION_ENABLED", "SITE_EXECUTION_REPORT_RUNTIME_ENABLED", "SITE_EXECUTION_AD_HOC_ENABLED", "SITE_LEARNING_ENABLED", "SITE_SCHEDULER_ENABLED",
)


def verify(path):
    if path is None:
        return {"verified": False, "sha256": None, "errors": ["missing_backend_runtime_configuration"]}
    try:
        path = Path(path)
        if path.is_symlink() or not path.is_file():
            raise ValueError()
        with path.open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError()
        content = raw.decode("utf-8").replace("\r\n", "\n")
        if any((ord(char) < 32 and char not in "\n\t") or char == "\ufeff" for char in content):
            raise ValueError()
        values = {}
        for line in content.split("\n"):
            line = line.strip(" \t")
            if not line or line.startswith(("#", ";")):
                continue
            assignment = re.fullmatch(r"([A-Z][A-Z0-9_]*)\s*=\s*(.*)", line)
            if assignment is None or assignment[1] in values:
                raise ValueError()
            value = assignment[2].strip(" \t")
            if value.startswith(("'", '"')):
                quote = value[0]
                if len(value) < 2 or value[-1] != quote or quote in value[1:-1] or "\\" in value:
                    raise ValueError()
                value = value[1:-1]
            elif any(char in value for char in "\\'\""):
                # Deliberately require a simple single-line literal deployment file.
                raise ValueError()
            values[assignment[1]] = value
        errors = ["required_runtime_flag_disabled:" + key for key in REQUIRED_FLAGS if values.get(key) != "true"]
        return {"verified": not errors, "sha256": hashlib.sha256(raw).hexdigest(), "errors": errors}
    except (ValueError, OSError, TypeError, UnicodeError):
        return {"verified": False, "sha256": None, "errors": ["invalid_backend_runtime_configuration"]}
