"""Create the cosmetic_crm_db database and load data/processed/*.csv (OnlineSales) into it.

Usage:  python database/build_database.py            (creates the database if it does not exist)
        python database/build_database.py --recreate (drops and rebuilds ONLY the configured new database)

Connection settings come from backend-pos/.env (DB_SERVER, DB_NAME, DB_USER, DB_PASSWORD).
The databases of the earlier projects (cosmetic_pos_db, cosmetic_pos_ai) are never touched.
The clustering model is NOT trained here: the admin trains the base model v1 in the web page.
"""
import argparse
import os
import sys
import time
from pathlib import Path

import pandas as pd
import pyodbc

ROOT = Path(__file__).resolve().parents[1]
PROCESSED = ROOT / "data" / "processed"
PROTECTED = {"cosmetic_pos_db", "cosmetic_pos_ai", "master", "model", "msdb", "tempdb"}


def settings() -> dict:
    env = ROOT / "backend-pos" / ".env"
    values = {}
    if env.is_file():
        for line in env.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("'\"")
    pick = lambda key, default: os.getenv(key) or values.get(key) or default
    return {"server": pick("DB_SERVER", "localhost"), "name": pick("DB_NAME", "cosmetic_crm_db"),
            "user": pick("DB_USER", "sa"), "password": pick("DB_PASSWORD", "")}


def connect(config: dict, database: str) -> pyodbc.Connection:
    return pyodbc.connect(
        f"DRIVER={{ODBC Driver 17 for SQL Server}};SERVER={config['server']};DATABASE={database};"
        f"UID={config['user']};PWD={config['password']};TrustServerCertificate=yes;", autocommit=True)


def run_batches(cursor, sql: str) -> None:
    batch = []
    for line in sql.splitlines():
        if line.strip().upper() == "GO":
            if "".join(batch).strip():
                cursor.execute("\n".join(batch))
            batch = []
        else:
            batch.append(line)
    if "".join(batch).strip():
        cursor.execute("\n".join(batch))


def apply_migrations(cursor) -> None:
    """database/migrations/*.sql in file-name order; each one is safe to run again."""
    for file in sorted((ROOT / "database" / "migrations").glob("*.sql")):
        run_batches(cursor, file.read_text(encoding="utf-8-sig"))
        print(f"Đã áp dụng {file.name}")


def insert(cursor, table: str, frame: pd.DataFrame, identity: bool = False) -> None:
    frame = frame.astype(object).where(pd.notna(frame), None)
    columns = ", ".join(frame.columns)
    marks = ", ".join("?" for _ in frame.columns)
    cursor.fast_executemany = True
    if identity:
        cursor.execute(f"SET IDENTITY_INSERT {table} ON")
    rows = [tuple(row) for row in frame.itertuples(index=False, name=None)]
    for start in range(0, len(rows), 20000):
        cursor.executemany(f"INSERT INTO {table} ({columns}) VALUES ({marks})", rows[start:start + 20000])
    if identity:
        cursor.execute(f"SET IDENTITY_INSERT {table} OFF")
    print(f"  {table:<20} {len(rows):>8,} dòng")


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--recreate", action="store_true", help="xóa và tạo lại database mới (không áp dụng cho database của project cũ)")
    args = parser.parse_args()
    config = settings()
    name = config["name"]
    if name.lower() in PROTECTED:
        print(f"Từ chối: {name} là database được bảo vệ.", file=sys.stderr)
        return 1
    if not (PROCESSED / "orders.csv").exists():
        print("Chưa có data/processed. Chạy python data/prepare_dataset.py trước.", file=sys.stderr)
        return 1
    master = connect(config, "master").cursor()
    exists = master.execute("SELECT DB_ID(?)", name).fetchone()[0] is not None
    if exists and not args.recreate:
        print(f"Database {name} đã tồn tại. Dùng --recreate để tạo lại.", file=sys.stderr)
        return 1
    if exists:
        master.execute(f"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE")
        master.execute(f"DROP DATABASE [{name}]")
    master.execute(f"CREATE DATABASE [{name}] COLLATE Vietnamese_CI_AS")
    print(f"Đã tạo database {name}")

    started = time.time()
    cursor = connect(config, name).cursor()
    run_batches(cursor, (ROOT / "database" / "schema.sql").read_text(encoding="utf-8-sig"))
    read = lambda file, **kw: pd.read_csv(PROCESSED / file, **kw)
    insert(cursor, "categories", read("categories.csv"), identity=True)
    products = read("products.csv", dtype={"barcode": str})
    insert(cursor, "products", products, identity=True)
    customers = read("customers.csv", dtype={"phone": str}, parse_dates=["first_purchase", "last_purchase"])
    insert(cursor, "customers", customers)
    insert(cursor, "orders", read("orders.csv", dtype={"transaction_no": str}, parse_dates=["order_date"]))
    insert(cursor, "order_items", read("order_items.csv", dtype={"transaction_no": str}))
    apply_migrations(cursor)   # tables added after the first version (member cards, bought-together rules, …)
    print(f"Hoàn tất sau {time.time() - started:.0f} giây. Tài khoản demo được Spring Boot tạo khi khởi động.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
