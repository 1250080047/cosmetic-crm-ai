"""Turn data/raw/OnlineSales.xlsx into the CSV files loaded by database/build_database.py.

Usage:  python data/prepare_dataset.py

Rules (documented in data/processed/data_report.md, written by this script):
- Rows with Quantity <= 0 (cancelled / zeroed lines) or without a product description are removed.
- Dates are kept exactly as in the source (2020-01-03 -> 2021-01-11): no time shift.
- Money is converted once at 25,600 VND/USD (central rate of the State Bank of Vietnam, 10/11/2026).
  Line unit price = round(UnitPrice x 25,600) dong; line revenue = quantity x that unit price.
- Products = unique descriptions. Group (11 Vietnamese groups) and product type come from the ordered keyword
  rules in data/category_rules.csv (first match wins). The 200 best sellers of the last 364 days get a
  hand-written Vietnamese name (data/product_names_vi.csv) and are the active POS catalogue (stock 100);
  every other product keeps a "type + cleaned original name" label and is inactive (history only).
- Customers keep their original CustomerID. Code KH<id>. Full name and phone are SYNTHETIC (deterministic from
  the id) so the POS can look customers up; the source has no names or phones. No gender or address is stored.
- Historical orders keep the original TransactionNo, payment method ONLINE (the source is an online shop).
"""
import html
import random
import re
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "raw" / "OnlineSales.xlsx"
OUT = ROOT / "data" / "processed"
VND_PER_USD = 25_600
ACTIVE_PRODUCTS = 200
ACTIVE_STOCK = 100
GROUPS = {1: "Chăm sóc da", 2: "Chống nắng", 3: "Chăm sóc tóc", 4: "Trang điểm", 5: "Chăm sóc móng",
          6: "Dụng cụ làm đẹp", 7: "Phụ kiện & thiết bị tóc", 8: "Nước hoa & khử mùi",
          9: "Tắm & chăm sóc cơ thể", 10: "Cạo râu & tẩy lông", 11: "Làm đẹp tổng hợp"}
SURNAMES = ["Nguyễn"] * 8 + ["Trần"] * 3 + ["Lê"] * 3 + ["Phạm"] * 2 + [
    "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô", "Dương", "Lý", "Trương", "Đinh"]
MIDDLE = ["Thị", "Thị", "Văn", "Ngọc", "Minh", "Thanh", "Hoàng", "Thu", "Gia", "Bảo", "Khánh", "Hải", "Quốc",
          "Phương", "Mỹ", "Hồng", "Đức", "Anh", "Kim", "Tuấn"]
GIVEN = ["Anh", "Trang", "Linh", "Hương", "Ngọc", "Hà", "Lan", "Mai", "Thảo", "Vy", "Nhi", "Hân", "Phương", "Yến",
         "Quỳnh", "Trinh", "Uyên", "Tâm", "My", "Duyên", "Hằng", "Nga", "Thủy", "Châu", "Diệp", "Hiền", "Loan",
         "Nam", "Huy", "Minh", "Khoa", "Tùng", "Dũng", "Long", "Phúc", "Quân", "Sơn", "Bình", "Hùng", "Tài",
         "Thắng", "Đạt", "Hiếu", "Khang", "Trí", "Vinh", "An", "Bảo", "Tú", "Hải"]
UNIT = r"(\d+(?:[.,]\d+)?|\.\d+)"


def synthetic_name(customer_id: int) -> str:
    rng = random.Random(customer_id * 7919)
    return f"{rng.choice(SURNAMES)} {rng.choice(MIDDLE)} {rng.choice(GIVEN)}"


def synthetic_phone(customer_id: int) -> str:
    return "0100" + str(customer_id).zfill(6)[-6:]


def load_rules() -> list:
    rules = pd.read_csv(ROOT / "data" / "category_rules.csv").sort_values("order")
    return [(re.compile(row.pattern, re.IGNORECASE), int(row.group_id), row.type_vi) for row in rules.itertuples()]


def classify(title: str, rules: list) -> tuple:
    # "Fragrance free" (không mùi) describes a skin-care product, not a perfume.
    text = re.sub(r"fragrance[- ]free|free of fragrance|no fragrance", " ", html.unescape(title), flags=re.I)
    for pattern, group, kind in rules:
        if pattern.search(text):
            return group, kind
    return 11, "Sản phẩm làm đẹp"


def _amount(value: str) -> float:
    return float(value.replace(",", "."))


def clean_title(title: str) -> str:
    text = html.unescape(title)
    text = re.sub(r"[★☆✔✓®™©*]+", " ", text)
    text = re.sub(r"\b(?:weekend\s+)?sale\b!?|\bnew\b|\bhot sales?\b|\bfree shipping\b", " ", text, flags=re.I)
    for separator in (" | ", " - ", " -- ", " – ", "; ", ": "):
        if len(text) > 70 and separator in text:
            text = text.split(separator)[0]
    text = re.sub(UNIT + r"\s*-?\s*(?:fl\.?\s*oz\.?|fluid\s*ounces?|fl\.?\s*ounces?)(?![a-z])",
                  lambda m: f"{round(_amount(m.group(1)) * 29.57)}ml", text, flags=re.I)
    text = re.sub(UNIT + r"\s*-?\s*(?:oz\.?|ounces?)(?![a-z])",
                  lambda m: f"{round(_amount(m.group(1)) * 28.35)}g", text, flags=re.I)
    text = re.sub(UNIT + r"\s*-?\s*(?:lbs?|pounds?)(?![a-z])",
                  lambda m: f"{round(_amount(m.group(1)) * 453.6)}g", text, flags=re.I)
    text = re.sub(r"\(?\s*pack of (\d+)\s*\)?", r"(lốc \1)", text, flags=re.I)
    text = re.sub(r"\(?\s*set of (\d+)\s*\)?", r"(bộ \1)", text, flags=re.I)
    text = re.sub(r"(\d+)\s*-?\s*(?:count|ct|pcs|pieces?)\b", r"\1 cái", text, flags=re.I)
    text = re.sub(r"\s+,", ",", re.sub(r"\s+", " ", text)).strip(" ,.-:;")
    if len(text) > 90:
        text = text[:90].rsplit(" ", 1)[0].rstrip(" ,.-") + "…"
    return text


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    if not RAW.exists():
        print(f"Không thấy {RAW}", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    raw = pd.read_excel(RAW)
    report = {"raw_rows": len(raw), "raw_transactions": raw.TransactionNo.nunique(),
              "raw_customers": raw.CustomerID.nunique()}
    report["missing_description"] = int(raw["Product Description"].isna().sum())
    report["non_positive_quantity"] = int((raw.Quantity <= 0).sum())
    frame = raw.dropna(subset=["Product Description", "CustomerID"])
    frame = frame[frame.Quantity > 0].copy()
    frame["Product Description"] = frame["Product Description"].astype(str).str.strip()
    frame["order_date"] = pd.to_datetime(frame.TransactionDate)
    frame["unit_price"] = (frame.UnitPrice * VND_PER_USD).round(0)
    frame["revenue"] = frame.Quantity * frame.unit_price
    last_day = frame.order_date.max().normalize()

    # Products ------------------------------------------------------------------------------
    rules = load_rules()
    recent = frame[(last_day - frame.order_date.dt.normalize()).dt.days <= 363]
    popularity = recent.groupby("Product Description").TransactionNo.nunique()
    names_vi = pd.read_csv(ROOT / "data" / "product_names_vi.csv", dtype=str)
    vi = dict(zip(names_vi.source_description, names_vi.name_vi))
    missing = [d for d in vi if d not in popularity.index]
    if missing:
        print(f"{len(missing)} tên tiếng Việt không khớp mô tả gốc, ví dụ: {missing[:2]}", file=sys.stderr)
        return 1
    stats = frame.groupby("Product Description").agg(median_usd=("UnitPrice", "median"))
    stats["popularity"] = popularity.reindex(stats.index).fillna(0).astype(int)
    stats["is_active"] = stats.index.isin(list(vi))
    stats = stats.reset_index().sort_values(["is_active", "popularity", "Product Description"],
                                            ascending=[False, False, True]).reset_index(drop=True)
    if int(stats.is_active.sum()) != ACTIVE_PRODUCTS:
        print("Danh sách 200 sản phẩm đang bán chưa đủ.", file=sys.stderr)
        return 1
    stats["id"] = stats.index + 1
    classified = stats["Product Description"].map(lambda title: classify(title, rules))
    stats["category_id"] = [group for group, _ in classified]
    stats["type_vi"] = [kind for _, kind in classified]
    stats["product_name"] = [
        vi[title] if title in vi else f"{kind} {clean_title(title)}"
        for title, kind in zip(stats["Product Description"], stats.type_vi)]
    stats["unit_price"] = (stats.median_usd * VND_PER_USD / 1000).round(0) * 1000
    stats["stock_quantity"] = stats.is_active.map({True: ACTIVE_STOCK, False: 0})
    stats["barcode"] = stats.id.map(lambda value: f"SP{value:05d}")
    stats["image_url"] = stats.category_id.map(lambda group: f"/images/categories/{group}.svg")
    stats["source_title"] = stats["Product Description"].map(html.unescape).str.slice(0, 500)
    products = stats[["id", "barcode", "product_name", "category_id", "unit_price", "stock_quantity", "image_url",
                      "is_active", "source_title"]].copy()
    products["is_active"] = products.is_active.astype(int)
    products["product_name"] = products.product_name.str.slice(0, 255)
    categories = pd.DataFrame({"id": list(GROUPS), "name": list(GROUPS.values())})

    # Orders and items ----------------------------------------------------------------------
    frame["product_id"] = frame["Product Description"].map(dict(zip(stats["Product Description"], stats.id)))
    frame["transaction_no"] = frame.TransactionNo.astype(str)
    items = frame[["transaction_no", "product_id", "Quantity", "unit_price", "revenue"]].rename(columns={"Quantity": "quantity"})
    items["list_price"] = items.unit_price
    orders = frame.groupby("transaction_no").agg(customer_id=("CustomerID", "first"), order_date=("order_date", "first"),
                                                 total_amount=("revenue", "sum")).reset_index()
    orders["customer_id"] = orders.customer_id.astype(int)
    orders["discount_amount"] = 0
    orders["final_amount"] = orders.total_amount
    orders["payment_method"] = "ONLINE"

    # Customers -----------------------------------------------------------------------------
    customers = orders.groupby("customer_id").agg(total_orders=("transaction_no", "size"), total_spent=("final_amount", "sum"),
                                                  first_purchase=("order_date", "min"), last_purchase=("order_date", "max")).reset_index()
    customers["customer_code"] = customers.customer_id.map(lambda value: f"KH{value}")
    customers["phone"] = customers.customer_id.map(synthetic_phone)
    customers["full_name"] = customers.customer_id.map(synthetic_name)
    if customers.phone.duplicated().any():
        print("Số điện thoại giả lập bị trùng.", file=sys.stderr)
        return 1
    customers = customers[["customer_id", "customer_code", "phone", "full_name", "total_orders", "total_spent",
                           "first_purchase", "last_purchase"]]

    categories.to_csv(OUT / "categories.csv", index=False, encoding="utf-8")
    products.to_csv(OUT / "products.csv", index=False, encoding="utf-8")
    customers.to_csv(OUT / "customers.csv", index=False, encoding="utf-8")
    orders[["transaction_no", "customer_id", "order_date", "total_amount", "discount_amount", "final_amount",
            "payment_method"]].to_csv(OUT / "orders.csv", index=False, encoding="utf-8")
    items[["transaction_no", "product_id", "quantity", "unit_price", "list_price", "revenue"]].to_csv(
        OUT / "order_items.csv", index=False, encoding="utf-8")

    by_group = products.groupby("category_id").agg(products=("id", "size"), active=("is_active", "sum"))
    group_rows = "\n".join(f"| {GROUPS[g]} | {row.products:,} | {row.active:,} |" for g, row in by_group.iterrows())
    order_value = orders.final_amount
    report_text = f"""# Báo cáo chuẩn bị dữ liệu (OnlineSales)

Tệp gốc: `data/raw/OnlineSales.xlsx` — {report['raw_rows']:,} dòng, {report['raw_transactions']:,} giao dịch,
{report['raw_customers']:,} mã khách. Cột: TransactionNo, CustomerID, Quantity, UnitPrice (USD), Revenue,
TransactionDate, Product Description. Bộ dữ liệu không có danh mục, họ tên hay số điện thoại.

> Nguồn: bộ dữ liệu thực hành đi kèm sách *Essentials of Marketing Analytics* — Joseph F. Hair Jr., Dana E. Harrison,
> Haya Ajjan, McGraw Hill (2021, ISBN 9781260597745). Sách thuộc bản quyền của nhà xuất bản; chỉ dùng cho mục đích học tập.

## Các bước làm sạch
| Bước | Số dòng |
|---|---:|
| Dữ liệu gốc | {report['raw_rows']:,} |
| Bỏ dòng thiếu mô tả sản phẩm | −{report['missing_description']:,} |
| Bỏ dòng số lượng ≤ 0 (đơn hủy/không hợp lệ) | −{report['non_positive_quantity']:,} |
| **Còn lại** | **{len(frame):,}** |

Kết quả: {len(orders):,} hóa đơn · {len(customers):,} khách hàng · {len(products):,} sản phẩm ·
ngày {frame.order_date.min():%d/%m/%Y} → {frame.order_date.max():%d/%m/%Y} (giữ nguyên ngày gốc, không dời thời gian).

## Quy đổi tiền
1 USD = {VND_PER_USD:,} đ (tỷ giá trung tâm Ngân hàng Nhà nước, 10/11/2026), áp dụng cho mọi dòng:
đơn giá dòng = làm tròn(UnitPrice × {VND_PER_USD:,}) đ; doanh thu dòng = số lượng × đơn giá dòng.
Giá trị hóa đơn: trung vị {order_value.median():,.0f} đ, P90 {order_value.quantile(.9):,.0f} đ, lớn nhất {order_value.max():,.0f} đ
(dữ liệu có nhiều đơn số lượng rất lớn kiểu bán sỉ; mô hình phân cụm giới hạn ở phân vị 99 để các đơn này không kéo lệch nhóm).

## Sản phẩm
- Mỗi mô tả sản phẩm khác nhau là một sản phẩm. Nhóm hàng và loại hàng tiếng Việt lấy theo bảng từ khóa
  `data/category_rules.csv` (quy tắc đầu tiên khớp được dùng).
- {ACTIVE_PRODUCTS} sản phẩm có nhiều hóa đơn nhất trong 364 ngày cuối được đặt tên tiếng Việt thủ công
  (`data/product_names_vi.csv`), mở bán tại POS với tồn kho {ACTIVE_STOCK}. Giá bán = trung vị đơn giá gốc × {VND_PER_USD:,}, làm tròn nghìn đồng.
- Các sản phẩm còn lại chỉ dùng cho lịch sử: tên = loại hàng tiếng Việt + tên gốc đã rút gọn (đổi oz → ml/g), không bán tại POS.
- Ảnh: minh họa theo nhóm hàng lưu cục bộ (`/images/categories/<nhóm>.svg`), không dùng liên kết ngoài.

| Nhóm hàng | Số sản phẩm | Đang bán |
|---|---:|---:|
{group_rows}

## Khách hàng
- Giữ nguyên mã khách gốc (CustomerID), mã tra cứu `KH<mã>`.
- **Họ tên và số điện thoại là dữ liệu GIẢ LẬP** (sinh cố định theo mã khách; số điện thoại dạng 0100xxxxxx)
  chỉ để thao tác tra cứu tại quầy; không dùng trong phân tích. Không lưu giới tính, địa chỉ.
- Hóa đơn lịch sử ghi phương thức "Trực tuyến" (ONLINE), không thuộc ca làm việc nào.
"""
    (OUT / "data_report.md").write_text(report_text, encoding="utf-8")
    print(f"Đã ghi {OUT}: {len(orders):,} hóa đơn, {len(items):,} dòng hàng, {len(customers):,} khách, {len(products):,} sản phẩm")
    return 0


if __name__ == "__main__":
    sys.exit(main())
