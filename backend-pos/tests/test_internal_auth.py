import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi import HTTPException
from starlette.requests import Request
from internal_auth import require_internal_key


class InternalAuthTest(unittest.TestCase):
    def request(self, key=""):
        return Request({"type": "http", "headers": [(b"x-internal-key", key.encode())]})

    def test_direct_request_is_rejected(self):
        with patch.dict(os.environ, {"AI_INTERNAL_KEY": "a" * 43}):
            with self.assertRaises(HTTPException) as result:
                require_internal_key(self.request())
            self.assertEqual(result.exception.status_code, 401)

    def test_correct_server_key_is_accepted(self):
        with patch.dict(os.environ, {"AI_INTERNAL_KEY": "a" * 43}):
            require_internal_key(self.request("a" * 43))

    def test_shared_file_is_read_when_env_key_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "internal.key"
            path.write_text("b" * 43, encoding="utf-8")
            with patch.dict(os.environ, {"AI_INTERNAL_KEY": "", "AI_INTERNAL_KEY_FILE": str(path)}):
                require_internal_key(self.request("b" * 43))

    def test_missing_key_fails_closed(self):
        with patch.dict(os.environ, {"AI_INTERNAL_KEY": "", "AI_INTERNAL_KEY_FILE": "not-present-internal.key"}):
            with self.assertRaises(HTTPException) as result:
                require_internal_key(self.request())
            self.assertEqual(result.exception.status_code, 503)
