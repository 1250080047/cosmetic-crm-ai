"""Only the authenticated Spring server may access the AI service."""
import hmac
import os
from pathlib import Path
from fastapi import HTTPException, Request


def require_internal_key(request: Request) -> None:
    expected = os.getenv("AI_INTERNAL_KEY", "").strip()
    if not expected:
        path = Path(os.getenv("AI_INTERNAL_KEY_FILE", str(Path(__file__).resolve().parent.parent / ".runtime" / "ai-internal.key")))
        try:
            expected = path.read_text(encoding="utf-8").strip()
        except OSError:
            raise HTTPException(503, "Start the Spring application to initialize the internal AI key.")
    if len(expected) < 32:
        raise HTTPException(503, "The internal AI key is not configured correctly.")
    if not hmac.compare_digest(request.headers.get("X-Internal-Key", "").encode(), expected.encode()):
        raise HTTPException(401, "Access the AI service through the authenticated application.")
