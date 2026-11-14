"""Market basket analysis — which products are bought together (Essentials of Marketing Analytics, chapter 9).

For two products A and B, over all invoices ("baskets"):
    support(A, B)      = invoices with both A and B / all invoices
    confidence(A → B)  = invoices with both A and B / invoices with A
                         ("x% of customers who buy A also buy B")
    lift(A → B)        = confidence(A → B) / (invoices with B / all invoices)
                         (> 1: bought together more often than if the two were unrelated)

Rules are found for products sold at the counter (is_active = 1), from every invoice in the database: the OnlineSales
base invoices plus the invoices of the store (POS). Gifts (price 0) are not purchases and are left out.
A rule is kept when the two products were bought together in at least MIN_TOGETHER invoices and lift > 1.
The cashier sees up to three suggestions with the highest confidence for what is already in the cart.
"""
from __future__ import annotations

import itertools
from collections import Counter
from dataclasses import dataclass
from typing import Iterable

import pandas as pd
from sqlalchemy import text

MIN_TOGETHER = 30


@dataclass(frozen=True)
class Rule:
    antecedent: int
    consequent: int
    together: int
    antecedent_count: int
    consequent_count: int
    support: float
    confidence: float
    lift: float


def rules_from_baskets(baskets: Iterable[frozenset], sellable: set, min_together: int = MIN_TOGETHER) -> tuple[list[Rule], int]:
    """All rules A → B between sellable products, and the number of baskets they were counted on."""
    baskets = [basket for basket in baskets if basket]
    total = len(baskets)
    single, pairs = Counter(), Counter()
    for basket in baskets:
        mine = sorted(product for product in basket if product in sellable)
        single.update(mine)
        pairs.update(itertools.combinations(mine, 2))
    rules = []
    for (a, b), together in pairs.items():
        if together < min_together:
            continue
        for x, y in ((a, b), (b, a)):
            confidence = together / single[x]
            lift = confidence / (single[y] / total)
            if lift > 1:
                rules.append(Rule(x, y, together, single[x], single[y], together / total, confidence, lift))
    rules.sort(key=lambda rule: (-rule.confidence, -rule.lift, rule.antecedent, rule.consequent))
    return rules, total


def _baskets(connection) -> tuple[list[frozenset], int, set]:
    items = pd.read_sql(text(
        "SELECT oi.transaction_no, oi.product_id, o.payment_method FROM order_items oi "
        "JOIN orders o ON o.transaction_no = oi.transaction_no WHERE oi.unit_price > 0"), connection)
    sellable = set(pd.read_sql(text("SELECT id FROM products WHERE is_active = 1"), connection)["id"].astype(int))
    if items.empty:
        return [], 0, sellable
    grouped = items.groupby("transaction_no")
    baskets = grouped["product_id"].agg(lambda ids: frozenset(int(i) for i in ids)).tolist()
    store = int((grouped["payment_method"].first() != "ONLINE").sum())
    return baskets, store, sellable


def compute(engine, requested_by: str = "", min_together: int = MIN_TOGETHER) -> dict:
    """Counts every basket again and replaces the stored rules in one transaction (the previous run stays listed)."""
    with engine.begin() as connection:
        if engine.dialect.name == "mssql":
            connection.execute(text(
                "DECLARE @r INT; EXEC @r = sys.sp_getapplock @Resource='association_rules', @LockMode='Exclusive', "
                "@LockOwner='Transaction', @LockTimeout=15000; IF @r < 0 THROW 50001, 'busy', 1;"))
        baskets, store_baskets, sellable = _baskets(connection)
        rules, total = rules_from_baskets(baskets, sellable, min_together)
        insert = ("INSERT INTO association_runs(computed_at, basket_count, store_basket_count, min_together, rule_count, "
                  "requested_by) {output} VALUES (CURRENT_TIMESTAMP, :total, :store, :minimum, :count, :user)")
        values = {"total": total, "store": store_baskets, "minimum": min_together, "count": len(rules),
                  "user": (requested_by or "")[:100]}
        if engine.dialect.name == "mssql":
            run_id = connection.execute(text(insert.format(output="OUTPUT INSERTED.id")), values).scalar_one()
        else:
            run_id = connection.execute(text(insert.format(output="")), values).lastrowid
        connection.execute(text("DELETE FROM product_associations"))
        if rules:
            connection.execute(text(
                "INSERT INTO product_associations(run_id, antecedent_id, consequent_id, together_count, antecedent_count, "
                "consequent_count, support, confidence, lift) VALUES (:run, :a, :b, :together, :ca, :cb, :support, :confidence, :lift)"),
                [{"run": run_id, "a": r.antecedent, "b": r.consequent, "together": r.together, "ca": r.antecedent_count,
                  "cb": r.consequent_count, "support": round(r.support, 6), "confidence": round(r.confidence, 6),
                  "lift": round(r.lift, 4)} for r in rules])
    return latest(engine)


def has_rules(engine) -> bool:
    with engine.connect() as connection:
        return connection.execute(text("SELECT COUNT(*) FROM association_runs")).scalar_one() > 0


def latest(engine, limit: int = 300) -> dict:
    """The rules of the last run with product names, and how often cashiers added a suggested product."""
    with engine.connect() as connection:
        run = connection.execute(text(
            "SELECT id, computed_at, basket_count, store_basket_count, min_together, rule_count, requested_by "
            "FROM association_runs WHERE id = (SELECT MAX(id) FROM association_runs)")).mappings().first()
        if run is None:
            return {"has_rules": False, "rules": [], "usage": _usage(connection), "min_together": MIN_TOGETHER}
        rows = connection.execute(text(
            "SELECT r.antecedent_id, a.product_name AS antecedent_name, r.consequent_id, b.product_name AS consequent_name, "
            "r.together_count, r.antecedent_count, r.consequent_count, r.support, r.confidence, r.lift "
            "FROM product_associations r JOIN products a ON a.id = r.antecedent_id JOIN products b ON b.id = r.consequent_id "
            "WHERE r.run_id = :run ORDER BY r.confidence DESC, r.lift DESC"), {"run": run["id"]}).mappings().all()
        usage = _usage(connection)
    return {
        "has_rules": True,
        "run": {"id": run["id"], "computed_at": str(run["computed_at"])[:19], "basket_count": run["basket_count"],
                "store_basket_count": run["store_basket_count"], "min_together": run["min_together"],
                "rule_count": run["rule_count"], "requested_by": run["requested_by"] or ""},
        "pair_count": len({frozenset((row["antecedent_id"], row["consequent_id"])) for row in rows}),
        "rules": [{"antecedent_id": row["antecedent_id"], "antecedent_name": row["antecedent_name"],
                   "consequent_id": row["consequent_id"], "consequent_name": row["consequent_name"],
                   "together": row["together_count"], "antecedent_count": row["antecedent_count"],
                   "consequent_count": row["consequent_count"], "support": float(row["support"]),
                   "confidence": float(row["confidence"]), "lift": float(row["lift"])} for row in rows[:limit]],
        "usage": usage,
    }


def _usage(connection) -> dict:
    """Counter invoices (not OnlineSales) and the products cashiers added from a suggestion."""
    row = connection.execute(text(
        "SELECT COUNT(DISTINCT o.transaction_no) AS invoices, "
        "COUNT(DISTINCT CASE WHEN oi.from_suggestion = 1 THEN o.transaction_no END) AS with_suggestion, "
        "COALESCE(SUM(CASE WHEN oi.from_suggestion = 1 THEN oi.quantity END), 0) AS units, "
        "COALESCE(SUM(CASE WHEN oi.from_suggestion = 1 THEN oi.revenue END), 0) AS revenue "
        "FROM orders o JOIN order_items oi ON oi.transaction_no = o.transaction_no WHERE o.payment_method <> 'ONLINE'")).mappings().first()
    invoices = int(row["invoices"] or 0)
    with_suggestion = int(row["with_suggestion"] or 0)
    return {"store_invoices": invoices, "invoices_with_suggestion": with_suggestion,
            "share": round(with_suggestion / invoices, 4) if invoices else 0.0,
            "units": int(row["units"] or 0), "revenue": float(row["revenue"] or 0)}
