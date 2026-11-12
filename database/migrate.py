"""Apply database/migrations/*.sql to the configured database (backend-pos/.env), in file-name order.

Every migration is written to be safe to run again, so this script can be run any number of times.
It never drops or recreates the database (unlike build_database.py --recreate).
"""
import sys
from pathlib import Path

from build_database import PROTECTED, connect, run_batches, settings

MIGRATIONS = Path(__file__).resolve().parent / "migrations"


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    config = settings()
    if config["name"].lower() in PROTECTED:
        print(f"Từ chối: {config['name']} là database được bảo vệ.", file=sys.stderr)
        return 1
    cursor = connect(config, config["name"]).cursor()
    for file in sorted(MIGRATIONS.glob("*.sql")):
        run_batches(cursor, file.read_text(encoding="utf-8-sig"))
        print(f"Đã áp dụng {file.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
