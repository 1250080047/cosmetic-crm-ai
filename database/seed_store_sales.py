"""Tạo hóa đơn bán tại quầy MÔ PHỎNG (15/08/2026 → 13/11/2026) để có dữ liệu phân tích doanh thu, lợi nhuận, LRFM,
mua kèm, hạng thẻ. Script dùng đúng quy tắc của POS:

* lấy hàng theo lô, lô có hạn gần nhất trước (ghi order_item_lots), trừ tồn sản phẩm;
* giá bán hiện tại; chưa có chiến lược ưu đãi nào đang bật nên không giảm giá;
* khách thành viên được tích điểm 1 điểm / 10.000 đ × hệ số hạng (Bạc ×1,5, Vàng ×2) theo chi tiêu tại quầy 12 tháng trước đó;
* mỗi ngày có hóa đơn là một ca (đã kết ca) của một thu ngân; tiền mặt kết ca = tiền đầu ca + tiền mặt thu;
* một phần hóa đơn mua theo cặp "hay mua kèm" (product_associations); từ 01/11/2026 phần lớn món mua kèm được ghi là
  thêm từ gợi ý (from_suggestion) — tính năng gợi ý coi như bắt đầu dùng từ đầu tháng 11.

Khách: 3 thành viên đã mua tại quầy + 31 khách của dữ liệu nền quay lại (họ tên, SĐT vốn là giả lập) + khách vãng lai,
tức hơn 30 khách khác nhau mua trong 12 tháng — đủ để bước 7 (kiểm tra lệch dữ liệu) đưa ra kết luận.
Mỗi ca mô phỏng ghi chú "Dữ liệu bán hàng mô phỏng (database/seed_store_sales.py)" — dùng để nhận ra và gỡ bỏ.

    python database/seed_store_sales.py            # tạo 50 hóa đơn (từ chối nếu đã tạo)
    python database/seed_store_sales.py --undo     # gỡ toàn bộ: trả hàng về lô, xóa điểm, hóa đơn, ca; tính lại tổng của khách

Sau khi tạo/gỡ, script nhờ FastAPI (nếu đang chạy) xếp lại nhóm khách và tính lại quy tắc mua kèm.
"""
from __future__ import annotations

import argparse
import random
import sys
import urllib.request
import uuid
from datetime import date, datetime, timedelta
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

from build_database import PROTECTED, connect, settings

NOTE = "Dữ liệu bán hàng mô phỏng (database/seed_store_sales.py)"
SEED = 20261114
START, END = date(2026, 8, 15), date(2026, 11, 13)
OPENING_CASH = Decimal("1000000")
TIERS = [(Decimal("5000000"), Decimal("2"), "Vàng"), (Decimal("2000000"), Decimal("1.5"), "Bạc")]
ROOT = Path(__file__).resolve().parent.parent


def tier(spend: Decimal):
    for minimum, multiplier, label in TIERS:
        if spend >= minimum:
            return multiplier, label
    return Decimal("1"), None


def schedule(rng: random.Random, count: int) -> list[datetime]:
    """Order times, more of them in recent weeks, during opening hours."""
    days = [(START + timedelta(days=i)) for i in range((END - START).days + 1)]
    weights = [1 + 2 * i / len(days) for i in range(len(days))]
    picked = rng.choices(days, weights=weights, k=count)
    times = []
    for day in picked:
        minute = rng.randint(9 * 60, 20 * 60 + 45)
        times.append(datetime(day.year, day.month, day.day, minute // 60, minute % 60, rng.randint(0, 59), rng.randint(0, 999) * 1000))
    return sorted(times)


def create(cur, count: int) -> None:
    if cur.execute("SELECT COUNT(*) FROM shifts WHERE note = ?", NOTE).fetchone()[0]:
        raise SystemExit("Đã có dữ liệu bán hàng mô phỏng. Chạy với --undo trước nếu muốn tạo lại.")
    rng = random.Random(SEED)
    products = {row.id: row for row in cur.execute(
        "SELECT id, product_name, unit_price, cost_price FROM products WHERE is_active = 1 ORDER BY id").fetchall()}
    rules: dict[int, list[tuple[int, float]]] = {}
    for a, b, confidence in cur.execute("""SELECT antecedent_id, consequent_id, confidence FROM product_associations
                                           WHERE run_id = (SELECT MAX(id) FROM association_runs)""").fetchall():
        if a in products and b in products:
            rules.setdefault(a, []).append((b, float(confidence)))
    cashiers = [row[0] for row in cur.execute("""SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id
                                                 WHERE r.name = 'CASHIER' AND u.is_active = 1 ORDER BY u.id""").fetchall()]
    if not cashiers:
        raise SystemExit("Chưa có tài khoản thu ngân (khởi động Spring một lần để tạo tài khoản).")
    members = [row[0] for row in cur.execute("""SELECT DISTINCT customer_id FROM orders
                                                WHERE payment_method <> 'ONLINE' AND customer_id IS NOT NULL ORDER BY customer_id""").fetchall()]
    base = [row[0] for row in cur.execute("""SELECT customer_id FROM customers WHERE phone IS NOT NULL
                                             AND customer_id NOT IN (SELECT customer_id FROM orders WHERE payment_method <> 'ONLINE' AND customer_id IS NOT NULL)
                                             ORDER BY customer_id""").fetchall()]
    # 3 members + 31 returning customers (≥ 30 different buyers in 12 months: enough for the drift check of step 7)
    returning = rng.sample(base, 31)
    buyers = [m for m in members for _ in range(2)] + [c for c in returning[:7] for _ in range(2)] + returning[7:]
    buyers = buyers[:count]
    buyers += [None] * (count - len(buyers))
    rng.shuffle(buyers)
    with_rules = [p for p in products if p in rules]

    shifts: dict[date, int] = {}
    totals = {"orders": 0, "units": 0, "revenue": Decimal(0), "points": 0, "suggested": 0}
    for when, customer in zip(schedule(rng, count), buyers):
        day = when.date()
        if day not in shifts:
            cashier = cashiers[(day - START).days % len(cashiers)]
            shifts[day] = cur.execute("""INSERT INTO shifts(cashier_id, opened_at, closed_at, opening_cash, counted_cash, note, closed_by, status)
                                         OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, 'CLOSED')""",
                                      cashier, datetime.combine(day, datetime.min.time()).replace(hour=8),
                                      datetime.combine(day, datetime.min.time()).replace(hour=21, minute=30),
                                      OPENING_CASH, OPENING_CASH, NOTE, cashier).fetchone()[0]
        shift = shifts[day]
        cashier = cur.execute("SELECT cashier_id FROM shifts WHERE id = ?", shift).fetchone()[0]

        # the basket: a first product, often what is "bought together" with it, sometimes something else
        first = rng.choice(with_rules) if rng.random() < 0.6 else rng.choice(list(products))
        lines = [(first, rng.choices([1, 2, 3], [0.72, 0.23, 0.05])[0], False)]
        if first in rules and rng.random() < 0.55:
            options = rules[first]
            partner = rng.choices([b for b, _ in options], weights=[c for _, c in options])[0]
            suggested = when.date() >= date(2026, 11, 1) and rng.random() < 0.7
            lines.append((partner, 1, suggested))
        for _ in range(rng.choice([0, 0, 0, 1, 1, 2])):
            extra = rng.choice(list(products))
            if all(extra != line[0] for line in lines):
                lines.append((extra, 1, False))

        total = sum(products[p].unit_price * q for p, q, _ in lines)
        payment = rng.choices(["CASH", "BANK_TRANSFER", "CARD"], [0.5, 0.35, 0.15])[0]
        number = "HD" + when.strftime("%y%m%d%H%M%S") + f"{when.microsecond // 1000:03d}" + uuid.UUID(int=rng.getrandbits(128)).hex[:8].upper()
        cur.execute("""INSERT INTO orders(transaction_no, customer_id, cashier_id, shift_id, order_date, total_amount, discount_amount,
                                          final_amount, payment_method, applied_strategy_id, points_redeemed, points_discount)
                       VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, 0, 0)""", number, customer, cashier, shift, when, total, total, payment)
        for product, quantity, suggested in lines:
            price = products[product].unit_price
            item = cur.execute("""INSERT INTO order_items(transaction_no, product_id, quantity, unit_price, list_price, revenue, from_suggestion)
                                  OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?)""",
                               number, product, quantity, price, price, price * quantity, 1 if suggested else 0).fetchone()[0]
            left = quantity
            for lot, remaining in cur.execute("""SELECT id, quantity_remaining FROM stock_lots WITH (UPDLOCK)
                                                 WHERE product_id = ? AND quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= ?)
                                                 ORDER BY CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END, expiry_date, id""",
                                              product, day).fetchall():
                take = min(left, remaining)
                cur.execute("UPDATE stock_lots SET quantity_remaining = quantity_remaining - ? WHERE id = ?", take, lot)
                cur.execute("INSERT INTO order_item_lots(order_item_id, lot_id, quantity) VALUES (?, ?, ?)", item, lot, take)
                left -= take
                if left == 0:
                    break
            if left:
                raise SystemExit(f"Không đủ hàng còn hạn cho sản phẩm #{product}.")
            cur.execute("UPDATE products SET stock_quantity = stock_quantity - ? WHERE id = ?", quantity, product)
            totals["units"] += quantity
            totals["suggested"] += 1 if suggested else 0
        if customer is not None:
            spend = cur.execute("""SELECT COALESCE(SUM(final_amount), 0) FROM orders WHERE customer_id = ? AND payment_method <> 'ONLINE'
                                   AND order_date >= ? AND order_date < ?""", customer, when - timedelta(days=365), when).fetchone()[0]
            multiplier, label = tier(Decimal(spend))
            earned = int((total * multiplier / Decimal(10000)).to_integral_value(rounding=ROUND_DOWN))
            if earned > 0:
                bonus = f" (hạng {label} ×{str(multiplier.normalize()).replace('.', ',')})" if label else ""
                cur.execute("""INSERT INTO loyalty_points(customer_id, transaction_no, points, note, created_at, kind)
                               VALUES (?, ?, ?, ?, ?, 'EARN')""", customer, number, earned, f"Tích điểm hóa đơn {number}{bonus}", when)
                totals["points"] += earned
            cur.execute("""UPDATE customers SET total_orders = total_orders + 1, total_spent = total_spent + ?,
                                  first_purchase = CASE WHEN first_purchase IS NULL OR first_purchase > ? THEN ? ELSE first_purchase END,
                                  last_purchase = CASE WHEN last_purchase IS NULL OR last_purchase < ? THEN ? ELSE last_purchase END
                           WHERE customer_id = ?""", total, when, when, when, when, customer)
        totals["orders"] += 1
        totals["revenue"] += total
    cur.execute("""UPDATE s SET counted_cash = s.opening_cash + COALESCE((SELECT SUM(o.final_amount) FROM orders o
                                                                        WHERE o.shift_id = s.id AND o.payment_method = 'CASH'), 0)
                   FROM shifts s WHERE s.note = ?""", NOTE)
    revenue = f"{totals['revenue']:,.0f}".replace(",", ".")
    print(f"Đã tạo {totals['orders']} hóa đơn trong {len(shifts)} ca, {totals['units']} sản phẩm, doanh thu {revenue} đ, "
          f"cộng {totals['points']} điểm, {totals['suggested']} món thêm từ gợi ý.")


def undo(cur) -> None:
    # created without parameters so the temporary table lives for the whole session (not just one prepared statement)
    cur.execute("CREATE TABLE #seeded (transaction_no VARCHAR(50) PRIMARY KEY)")
    cur.execute("INSERT INTO #seeded SELECT o.transaction_no FROM orders o JOIN shifts s ON s.id = o.shift_id WHERE s.note = ?", NOTE)
    count = cur.execute("SELECT COUNT(*) FROM #seeded").fetchone()[0]
    customers = [row[0] for row in cur.execute("""SELECT DISTINCT o.customer_id FROM orders o JOIN #seeded x ON x.transaction_no = o.transaction_no
                                                  WHERE o.customer_id IS NOT NULL""").fetchall()]
    cur.execute("""UPDATE l SET quantity_remaining = l.quantity_remaining + t.quantity FROM stock_lots l
                   JOIN (SELECT x.lot_id, SUM(x.quantity) AS quantity FROM order_item_lots x JOIN order_items i ON i.id = x.order_item_id
                         JOIN #seeded s ON s.transaction_no = i.transaction_no GROUP BY x.lot_id) t ON t.lot_id = l.id""")
    cur.execute("""UPDATE p SET stock_quantity = p.stock_quantity + t.quantity FROM products p
                   JOIN (SELECT i.product_id, SUM(i.quantity) AS quantity FROM order_items i JOIN #seeded s ON s.transaction_no = i.transaction_no
                         GROUP BY i.product_id) t ON t.product_id = p.id""")
    cur.execute("DELETE p FROM loyalty_points p JOIN #seeded s ON s.transaction_no = p.transaction_no")
    cur.execute("DELETE i FROM order_items i JOIN #seeded s ON s.transaction_no = i.transaction_no")   # order_item_lots: ON DELETE CASCADE
    cur.execute("DELETE o FROM orders o JOIN #seeded s ON s.transaction_no = o.transaction_no")
    cur.execute("DELETE FROM shifts WHERE note = ?", NOTE)
    for customer in customers:
        cur.execute("""UPDATE c SET total_orders = t.orders, total_spent = t.spent, first_purchase = t.first_purchase, last_purchase = t.last_purchase
                       FROM customers c CROSS APPLY (SELECT COUNT(*) AS orders, COALESCE(SUM(final_amount), 0) AS spent,
                                                            MIN(order_date) AS first_purchase, MAX(order_date) AS last_purchase
                                                     FROM orders WHERE customer_id = c.customer_id) t
                       WHERE c.customer_id = ?""", customer)
    cur.execute("DROP TABLE #seeded")
    print(f"Đã gỡ {count} hóa đơn mô phỏng, trả hàng về đúng lô, xóa điểm và ca; tính lại tổng của {len(customers)} khách.")


def refresh_analysis() -> None:
    """Ask the analysis service to place every customer on the ruler again and to count the baskets again."""
    try:
        key = (ROOT / ".runtime" / "ai-internal.key").read_text(encoding="utf-8").strip()
        for path, body in (("/api/ai/rescore", b""), ("/api/ai/associations/recompute", b'{"user": "seed_store_sales.py"}')):
            request = urllib.request.Request("http://127.0.0.1:8000" + path, data=body, method="POST",
                                             headers={"X-Internal-Key": key, "Content-Type": "application/json"})
            urllib.request.urlopen(request, timeout=180).read()
        print("FastAPI đã xếp lại nhóm khách và tính lại quy tắc mua kèm.")
    except Exception as error:
        print(f"Chưa gọi được FastAPI ({type(error).__name__}). Khi FastAPI chạy, vào trang quản trị bấm 'Xếp lại khách' và 'Tính lại'.")


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--undo", action="store_true", help="gỡ toàn bộ hóa đơn mô phỏng")
    parser.add_argument("--orders", type=int, default=50)
    args = parser.parse_args()
    config = settings()
    if config["name"].lower() in PROTECTED:
        print(f"Từ chối: {config['name']} là database được bảo vệ.", file=sys.stderr)
        return 1
    connection = connect(config, config["name"])
    connection.autocommit = False
    cursor = connection.cursor()
    try:
        undo(cursor) if args.undo else create(cursor, max(1, min(args.orders, 500)))
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    refresh_analysis()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
