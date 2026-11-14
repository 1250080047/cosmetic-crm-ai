"""Apply database/migrations/*.sql to the configured database (backend-pos/.env), in file-name order.

Every migration is written to be safe to run again, so this script can be run any number of times.
It never drops or recreates the database (unlike build_database.py --recreate). A database built from scratch
with build_database.py already gets every migration.
"""
import sys

from build_database import PROTECTED, apply_migrations, connect, settings


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    config = settings()
    if config["name"].lower() in PROTECTED:
        print(f"Từ chối: {config['name']} là database được bảo vệ.", file=sys.stderr)
        return 1
    apply_migrations(connect(config, config["name"]).cursor())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
