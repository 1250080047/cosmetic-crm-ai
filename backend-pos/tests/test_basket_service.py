"""Market basket analysis on a tiny SQLite copy of the tables (never the store's SQL Server data)."""
import unittest

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

import basket_service
from basket_service import rules_from_baskets

SCHEMA = [
    "CREATE TABLE products (id INTEGER PRIMARY KEY, product_name TEXT, is_active INTEGER)",
    "CREATE TABLE orders (transaction_no TEXT PRIMARY KEY, payment_method TEXT)",
    """CREATE TABLE order_items (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_no TEXT, product_id INTEGER, quantity INTEGER,
       unit_price NUMERIC, revenue NUMERIC, from_suggestion INTEGER DEFAULT 0)""",
    """CREATE TABLE association_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, computed_at DATETIME, basket_count INTEGER,
       store_basket_count INTEGER, min_together INTEGER, rule_count INTEGER, requested_by TEXT)""",
    """CREATE TABLE product_associations (run_id INTEGER, antecedent_id INTEGER, consequent_id INTEGER, together_count INTEGER,
       antecedent_count INTEGER, consequent_count INTEGER, support NUMERIC, confidence NUMERIC, lift NUMERIC)""",
]


class RulesTest(unittest.TestCase):
    def test_shampoo_and_conditioner(self):
        # 10 invoices: conditioner (2) in 4, shampoo (1) in 5, both in 3; soap (3) is everywhere.
        baskets = [frozenset({1, 2, 3})] * 3 + [frozenset({2, 3})] + [frozenset({1, 3})] * 2 + [frozenset({3})] * 4
        rules, total = rules_from_baskets(baskets, sellable={1, 2, 3}, min_together=2)
        self.assertEqual(total, 10)
        rule = next(r for r in rules if (r.antecedent, r.consequent) == (2, 1))
        self.assertEqual(rule.together, 3)
        self.assertAlmostEqual(rule.support, 0.3)
        self.assertAlmostEqual(rule.confidence, 0.75)          # 3 of the 4 conditioner invoices have shampoo
        self.assertAlmostEqual(rule.lift, 0.75 / 0.5)           # shampoo alone: 5 of 10 invoices
        # soap is in every invoice: buying anything with it is no signal (lift = 1), so no rule
        self.assertFalse([r for r in rules if 3 in (r.antecedent, r.consequent)])

    def test_rare_pairs_and_products_not_for_sale_are_left_out(self):
        baskets = [frozenset({1, 2})] * 2 + [frozenset({1, 9})] * 5 + [frozenset({4})] * 5
        rules, _ = rules_from_baskets(baskets, sellable={1, 2, 4}, min_together=3)
        self.assertEqual(rules, [])                             # 1+2 only twice; 9 is not sold at the counter


class StoredRulesTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
        with self.engine.begin() as c:
            for statement in SCHEMA:
                c.execute(text(statement))
            c.execute(text("INSERT INTO products VALUES (1, 'Dầu gội', 1), (2, 'Dầu xả', 1), (3, 'Xà phòng', 1), (4, 'Ngừng bán', 0)"))
            invoices = [("A1", "ONLINE", [1, 2]), ("A2", "ONLINE", [1, 2]), ("A3", "ONLINE", [1, 2, 3]),
                        ("A4", "ONLINE", [3]), ("A5", "ONLINE", [3, 4]), ("H1", "CASH", [1, 2]), ("H2", "CASH", [3])]
            for number, method, products in invoices:
                c.execute(text("INSERT INTO orders VALUES (:n, :m)"), {"n": number, "m": method})
                for product in products:
                    c.execute(text("INSERT INTO order_items(transaction_no, product_id, quantity, unit_price, revenue, from_suggestion) "
                                   "VALUES (:n, :p, 1, 100, 100, :s)"),
                              {"n": number, "p": product, "s": 1 if (number, product) == ("H1", 2) else 0})
            # a gift (price 0) is not a purchase
            c.execute(text("INSERT INTO order_items(transaction_no, product_id, quantity, unit_price, revenue) VALUES ('H2', 1, 1, 0, 0)"))

    def test_compute_stores_rules_and_reports_suggestion_use(self):
        self.assertFalse(basket_service.has_rules(self.engine))
        result = basket_service.compute(self.engine, "manager", min_together=2)
        self.assertTrue(basket_service.has_rules(self.engine))
        self.assertEqual(result["run"]["basket_count"], 7)
        self.assertEqual(result["run"]["store_basket_count"], 2)
        pair = {(r["antecedent_name"], r["consequent_name"]): r for r in result["rules"]}
        rule = pair[("Dầu xả", "Dầu gội")]
        self.assertEqual(rule["together"], 4)
        self.assertAlmostEqual(rule["confidence"], 1.0)
        self.assertAlmostEqual(rule["lift"], 7 / 4, places=3)
        self.assertEqual(result["usage"], {"store_invoices": 2, "invoices_with_suggestion": 1, "share": 0.5,
                                           "units": 1, "revenue": 100.0})

    def test_recompute_replaces_rules_but_keeps_run_history(self):
        basket_service.compute(self.engine, "a", min_together=2)
        result = basket_service.compute(self.engine, "b", min_together=2)
        with self.engine.connect() as c:
            runs = c.execute(text("SELECT COUNT(*) FROM association_runs")).scalar_one()
            stale = c.execute(text("SELECT COUNT(*) FROM product_associations WHERE run_id <> :r"), {"r": result["run"]["id"]}).scalar_one()
        self.assertEqual(runs, 2)
        self.assertEqual(stale, 0)
        self.assertEqual(result["run"]["requested_by"], "b")


if __name__ == "__main__":
    unittest.main()
