"""Load only the assistant's local, ignored configuration; never override process settings."""
import os
from pathlib import Path

ALLOWED_KEYS = {
    "GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_FALLBACK_MODEL", "GEMINI_ALLOW_STORE_CONTEXT",
    "DB_SERVER", "DB_NAME", "DB_USER", "DB_PASSWORD", "BASE_REFERENCE_DATE"
}


def load_settings() -> None:
    path = Path(__file__).resolve().with_name(".env")
    if not path.is_file():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key in ALLOWED_KEYS:
            os.environ.setdefault(key, value.strip().strip("'\""))
