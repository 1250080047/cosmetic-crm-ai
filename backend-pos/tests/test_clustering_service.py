"""Base-model behaviour in an isolated SQLite database, never the store's SQL data.

The key property (teacher's requirement): the model learns ONLY when a version is trained. Adding customers or
invoices afterwards never changes the stored ruler (caps, mean, std, centres) nor how the base customers are grouped.
"""
import json
import os
import unittest
from datetime import date, datetime, timedelta

os.environ.setdefault("LOKY_MAX_CPU_COUNT", "4")

import numpy as np
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool
from threadpoolctl import threadpool_limits

from clustering_service import (
    NEW_CUSTOMER, ClusterError, ClusterStore, StaleDeployment, base_reference_date, load_lrfm, recommend_k, score,
)
from customer_language import NO_PURCHASE, SEGMENT_NAMES

REFERENCE = base_reference_date()          # 2021-01-12, the day after the last OnlineSales invoice
TODAY = date(2026, 11, 11)

SCHEMA = [
    "CREATE TABLE roles (id INTEGER PRIMARY KEY)",
    """CREATE TABLE customers (customer_id INTEGER PRIMARY KEY, segment_id INTEGER, segment_scored_at DATETIME,
       total_orders INTEGER DEFAULT 0, total_spent NUMERIC DEFAULT 0, first_purchase DATETIME, last_purchase DATETIME)""",
    "CREATE TABLE customer_segments (id INTEGER PRIMARY KEY, segment_name TEXT NOT NULL, color_code TEXT)",
    """CREATE TABLE strategies (id INTEGER PRIMARY KEY AUTOINCREMENT, segment_id INTEGER UNIQUE, title TEXT NOT NULL,
       discount_percent NUMERIC, gift_description TEXT, cashier_script TEXT, is_active INTEGER, updated_at DATETIME)""",
    """CREATE TABLE orders (transaction_no TEXT PRIMARY KEY, customer_id INTEGER, order_date DATETIME,
       final_amount NUMERIC, applied_strategy_id INTEGER)""",
    """CREATE TABLE model_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, version TEXT UNIQUE, label TEXT, status TEXT,
       k INTEGER, reference_date DATE, training_source TEXT, training_rows INTEGER, model_json TEXT, reason TEXT,
       created_by TEXT, created_at DATETIME, activated_at DATETIME, archived_at DATETIME)""",
    """CREATE TABLE drift_checks (id INTEGER PRIMARY KEY AUTOINCREMENT, model_version TEXT, checked_at DATETIME,
       as_of_date DATE, sample_size INTEGER, drift_detected INTEGER, result_json TEXT)""",
    """CREATE TABLE strategy_history (original_strategy_id INTEGER PRIMARY KEY, segment_id INTEGER, snapshot_json TEXT,
       archived_at DATETIME, deployment_version TEXT)""",
]


class BaseModelTest(unittest.TestCase):
    def setUp(self):
        self.thread_limit = threadpool_limits(limits=1)
        self.thread_limit.__enter__()
        self.engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
        self.invoice = 0
        with self.engine.begin() as connection:
            for statement in SCHEMA:
                connection.execute(text(statement))
            connection.execute(text("INSERT INTO roles VALUES(1)"))
        rng = np.random.RandomState(7)
        start = datetime(2020, 1, 15)
        for customer in range(1, 181):
            visits = int(rng.choice([1, 1, 1, 2, 3, 5, 8, 13]))
            first = start + timedelta(days=int(rng.randint(0, 360)))
            days = sorted({first + timedelta(days=int(rng.randint(0, max(1, (datetime(2021, 1, 11) - first).days + 1))))
                           for _ in range(visits - 1)} | {first})
            amounts = [float(rng.lognormal(16.5, 1.0)) for _ in days]
            self.add_customer(customer, list(zip(days, amounts)))
        self.store = ClusterStore(self.engine, today=lambda: TODAY)

    def tearDown(self):
        self.engine.dispose()
        self.thread_limit.__exit__(None, None, None)

    # ---- helpers ------------------------------------------------------------------------------
    def add_customer(self, customer, purchases):
        with self.engine.begin() as connection:
            connection.execute(text("INSERT INTO customers(customer_id) VALUES(:id)"), {"id": customer})
            for moment, amount in purchases:
                self.invoice += 1
                connection.execute(text("INSERT INTO orders(transaction_no, customer_id, order_date, final_amount) "
                                        "VALUES(:no, :customer, :at, :amount)"),
                                   {"no": f"T{self.invoice}", "customer": customer, "at": moment, "amount": amount})

    def rows(self, query, **params):
        with self.engine.connect() as connection:
            return [dict(r) for r in connection.execute(text(query), params).mappings()]

    def deploy(self, k=3, source="base", reason=""):
        preview = self.store.preview(k, source)
        return preview, self.store.deploy(preview["preview_id"], preview["expected_version"], reason, "test")

    def active_model(self):
        row = self.rows("SELECT model_json FROM model_versions WHERE status='ACTIVE'")[0]
        return json.loads(row["model_json"])

    # ---- tests --------------------------------------------------------------------------------
    def test_first_model_learns_only_from_base_data(self):
        with self.assertRaises(ClusterError):
            self.store.preview(3, "extended")
        preview, result = self.deploy(3)
        self.assertIsNone(preview["expected_version"])
        self.assertEqual(result["label"], "v1")
        model = self.active_model()
        self.assertEqual(model["reference_date"], str(REFERENCE))
        self.assertEqual(model["training_rows"], 180)
        self.assertEqual(set(model["mean"]), {"Length", "Recency", "Frequency", "Monetary"})
        self.assertEqual(len(model["centroids"]), 3)
        self.assertEqual(len(self.rows("SELECT * FROM strategies")), 3)
        self.assertTrue(self.store.status()["synchronized"])

    def test_new_customers_never_move_the_ruler_or_the_base_groups(self):
        self.deploy(3)
        before = self.active_model()
        with self.engine.connect() as connection:
            base_before = score(before, load_lrfm(connection, REFERENCE))
        for customer in range(1000, 1400):   # 400 big spenders today: very different from the base data
            self.add_customer(customer, [(datetime(2026, 11, 10, 10), 9e9), (datetime(2026, 11, 11, 9), 9e9)])
        self.store.rescore()
        after = self.active_model()
        for key in ("version", "caps", "mean", "std", "centroids", "clusters"):
            self.assertEqual(before[key], after[key], key)
        with self.engine.connect() as connection:
            base_after = score(after, load_lrfm(connection, REFERENCE))
        np.testing.assert_array_equal(base_before[:180], base_after[:180])
        self.assertEqual(len(self.rows("SELECT * FROM model_versions")), 1)

    def test_counter_prediction_matches_rescoring(self):
        self.deploy(3)
        self.add_customer(2000, [(datetime(2026, 9, 1, 15), 3_000_000), (datetime(2026, 11, 2, 11), 1_200_000)])
        self.store.rescore()
        with self.engine.connect() as connection:
            frame = load_lrfm(connection, TODAY).set_index("CustomerID")
        row = frame.loc[2000]
        self.assertEqual((row.Length, row.Recency, row.Frequency), (71.0, 9.0, 2.0))
        predicted = self.store.predict(row.Length, row.Recency, row.Frequency, row.Monetary, True)["cluster_id"]
        stored = self.rows("SELECT segment_id FROM customers WHERE customer_id=2000")[0]["segment_id"]
        self.assertEqual(predicted, stored)

    def test_customer_without_orders_is_new_and_lapsed_customer_goes_to_lapsed_group(self):
        _, result = self.deploy(3)
        self.add_customer(3000, [])
        self.store.rescore()
        self.assertIsNone(self.rows("SELECT segment_id FROM customers WHERE customer_id=3000")[0]["segment_id"])
        self.assertEqual(self.store.predict(0, 0, 0, 0, False)["cluster_id"], NEW_CUSTOMER)
        lapsed = self.active_model()["lapsed_cluster_id"]
        # Every base customer last bought in 2021: today they are all in the lapsed group.
        segments = {r["segment_id"] for r in self.rows("SELECT segment_id FROM customers WHERE customer_id <= 180")}
        self.assertEqual(segments, {lapsed})
        self.assertEqual(self.store.predict(2000, 2000, 0, 0, True)["cluster_id"], lapsed)
        self.assertEqual(result["rescored"]["lapsed"], 180)

    def test_drift_needs_enough_customers_then_detects_a_shift(self):
        self.deploy(3)
        for customer in range(4000, 4010):
            self.add_customer(customer, [(datetime(2026, 10, 1), 2e6)])
        report = self.store.drift()
        self.assertEqual(report["status"], "insufficient")
        self.assertFalse(report["drift_detected"])
        for customer in range(4010, 4060):
            self.add_customer(customer, [(datetime(2026, 11, 1) + timedelta(hours=h), 5e9) for h in range(20)])
        report = self.store.drift(record=True)
        self.assertEqual(report["status"], "drift")
        self.assertTrue(report["reasons"])
        self.assertTrue(any(f["flagged"] for f in report["features"] if f["feature"] in ("Frequency", "Monetary")))
        logged = self.rows("SELECT sample_size, drift_detected FROM drift_checks")
        self.assertEqual(logged, [{"sample_size": 60, "drift_detected": 1}])
        # Checking drift never retrains.
        self.assertEqual(len(self.rows("SELECT * FROM model_versions")), 1)

    def test_controlled_retraining_keeps_history_and_rejects_stale_previews(self):
        _, first = self.deploy(3)
        for customer in range(5000, 5080):
            self.add_customer(customer, [(datetime(2026, 6, 1) + timedelta(days=d), 4e6) for d in range(0, 120, 30)])
        before = self.rows("SELECT * FROM customers ORDER BY customer_id")
        preview = self.store.preview(3, "extended")
        self.assertEqual(preview["training_rows"], 180 + 80)
        self.assertEqual(sum(map(sum, preview["transition"]["counts"])), 260)
        self.assertEqual(self.rows("SELECT * FROM customers ORDER BY customer_id"), before)   # preview changes nothing
        with self.assertRaises(StaleDeployment):
            self.store.deploy(preview["preview_id"], "someone-else", "x")
        result = self.store.deploy(preview["preview_id"], first["active_version"], "Khách mới khác dữ liệu nền", "test")
        self.assertEqual(result["label"], "v2")
        versions = self.rows("SELECT label, status, reason FROM model_versions ORDER BY id")
        self.assertEqual([(v["label"], v["status"]) for v in versions], [("v1", "ARCHIVED"), ("v2", "ACTIVE")])
        self.assertEqual(versions[1]["reason"], "Khách mới khác dữ liệu nền")
        self.assertEqual(result["preserved_strategies"] + result["archived_strategies"], 3)
        with self.assertRaises(StaleDeployment):        # the old preview cannot be applied twice
            self.store.deploy(preview["preview_id"], result["active_version"])
        self.assertTrue(self.store.status()["synchronized"])

    def test_base_analysis_explores_other_k_without_touching_the_active_model(self):
        self.deploy(3)
        before = (self.rows("SELECT * FROM model_versions"), self.rows("SELECT * FROM customers ORDER BY customer_id"),
                  self.rows("SELECT * FROM customer_segments ORDER BY id"), self.rows("SELECT * FROM strategies ORDER BY id"))
        analysis = self.store.base_analysis(5)
        self.assertEqual(analysis["k"], 5)
        self.assertEqual(len(analysis["clusters"]), 5)
        self.assertEqual(sum(c["count"] for c in analysis["clusters"]), 180)
        # Today every base customer is lapsed, so the whole base lands in the lapsed group of that split too.
        self.assertEqual(sum(c["current_count"] for c in analysis["clusters"]), 180)
        self.assertEqual(next(c for c in analysis["clusters"] if c["cluster_id"] == analysis["lapsed_cluster_id"])["current_count"], 180)
        after = (self.rows("SELECT * FROM model_versions"), self.rows("SELECT * FROM customers ORDER BY customer_id"),
                 self.rows("SELECT * FROM customer_segments ORDER BY id"), self.rows("SELECT * FROM strategies ORDER BY id"))
        self.assertEqual(before, after)
        # The active K on base data gives exactly the active groups (same data, same random_state).
        same = self.store.base_analysis(3)
        self.assertEqual([c["name"] for c in same["clusters"]], [c["name"] for c in self.active_model()["clusters"]])
        with self.assertRaises(ClusterError):
            self.store.base_analysis(11)

    def test_group_names_use_the_professional_vocabulary(self):
        self.deploy(3)
        names = [c["name"].split(" · ")[0] for c in self.active_model()["clusters"]]
        self.assertTrue(set(names) <= set(SEGMENT_NAMES), names)
        self.assertEqual(self.store.predict(0, 0, 0, 0, False)["name"], NO_PURCHASE)

    def test_refresh_names_relabels_old_names_but_keeps_the_ruler(self):
        self.deploy(3)
        model = self.active_model()
        old = {0: "Lâu chưa ghé", 1: "Mới quen", 2: "Mua đều"}
        stored = {**model, "clusters": [{**c, "name": old[c["cluster_id"]]} for c in model["clusters"]]}
        with self.engine.begin() as connection:
            connection.execute(text("UPDATE model_versions SET model_json=:json"), {"json": json.dumps(stored, ensure_ascii=False)})
            for cid, name in old.items():
                connection.execute(text("UPDATE customer_segments SET segment_name=:n WHERE id=:id"), {"n": name, "id": cid})
                connection.execute(text("UPDATE strategies SET title=:t WHERE segment_id=:id"), {"t": "Ưu đãi — " + name, "id": cid})
            connection.execute(text("UPDATE strategies SET title='Tri ân khách' WHERE segment_id=2"))   # edited by the admin
        segments_before = self.rows("SELECT customer_id, segment_id FROM customers ORDER BY customer_id")
        result = self.store.refresh_names()
        self.assertEqual(result["renamed"], 3)
        renamed = self.active_model()
        self.assertEqual([c["name"] for c in renamed["clusters"]], [c["name"] for c in model["clusters"]])
        for key in ("version", "caps", "mean", "std", "centroids", "lapsed_cluster_id"):
            self.assertEqual(renamed[key], model[key], key)
        self.assertEqual(renamed["previous_names"][0]["names"], list(old.values()))
        self.assertEqual(self.rows("SELECT customer_id, segment_id FROM customers ORDER BY customer_id"), segments_before)
        titles = {r["segment_id"]: r["title"] for r in self.rows("SELECT segment_id, title FROM strategies")}
        self.assertEqual(titles[0], "Ưu đãi — " + model["clusters"][0]["name"])
        self.assertEqual(titles[2], "Tri ân khách")
        self.assertTrue(self.store.status()["synchronized"])
        self.assertEqual(self.store.refresh_names()["renamed"], 0)   # idempotent

    def test_recommend_k_prefers_usable_group_sizes(self):
        k, _ = recommend_k([2, 3, 4, 5], [0.50, 0.40, 0.395, 0.30], [40, 20, 9, 2])
        self.assertEqual(k, 4)
        k, reason = recommend_k([2, 3], [0.5, 0.4], [3, 2])
        self.assertEqual(k, 2)
        self.assertIn("chưa đủ", reason)


if __name__ == "__main__":
    unittest.main()
