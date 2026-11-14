-- Giá gốc và lô tồn đầu kỳ hợp lệ cho các sản phẩm đang bán ở POS. Chạy lại vẫn an toàn.
-- Dữ liệu nền OnlineSales không có giá gốc và hạn dùng, nên hai giá trị này được GÁN TỰ ĐỘNG theo quy tắc cố định:
--   * giá gốc = 55%–70% giá bán (lãi gộp 30%–45%, mức thường gặp của bán lẻ mỹ phẩm), làm tròn tới 1.000 đ;
--   * lô tồn đầu kỳ (DK-…, is_demo = 1) nhận ngày 01/08/2026, hạn dùng còn 5–29 tháng tính từ lúc chạy migration.

-- 1. Giá gốc của sản phẩm đang bán (chỉ khi chưa có)
UPDATE products
SET cost_price = ROUND(unit_price * (0.55 + ((id * 37) % 16) / 100.0), -3)
WHERE is_active = 1 AND cost_price IS NULL;
GO

-- 2. Lô tồn đầu kỳ: gộp phần đã hết hạn (lô DK-…-HH chưa bán) vào lô chính, đặt hạn dùng còn dài, ngày nhận và giá nhập.
--    Chỉ xử lý lô chưa được chuẩn hóa (received_at khác 01/08/2026 08:00), nên chạy lại không đổi gì.
BEGIN TRANSACTION;
UPDATE m SET m.quantity_received = m.quantity_received + h.quantity_remaining,
             m.quantity_remaining = m.quantity_remaining + h.quantity_remaining
FROM stock_lots m JOIN stock_lots h ON h.product_id = m.product_id AND h.lot_no = m.lot_no + '-HH'
WHERE m.is_demo = 1 AND h.is_demo = 1 AND h.quantity_remaining > 0
  AND NOT EXISTS (SELECT 1 FROM order_item_lots x WHERE x.lot_id = h.id);
DELETE h FROM stock_lots h
WHERE h.is_demo = 1 AND h.lot_no LIKE 'DK-%-HH'
  AND NOT EXISTS (SELECT 1 FROM order_item_lots x WHERE x.lot_id = h.id);
UPDATE l SET l.expiry_date = DATEADD(day, 150 + (l.product_id * 37) % 720, CAST(SYSDATETIME() AS DATE)),
             l.received_at = '2026-08-01T08:00:00',
             l.unit_cost = COALESCE(l.unit_cost, p.cost_price)
FROM stock_lots l JOIN products p ON p.id = l.product_id
WHERE l.is_demo = 1 AND l.receipt_id IS NULL AND l.received_at <> '2026-08-01T08:00:00';
COMMIT;
GO

-- 3. Lô nhập qua phiếu nhập mà chưa ghi giá nhập: lấy giá gốc hiện tại của sản phẩm
UPDATE l SET l.unit_cost = p.cost_price
FROM stock_lots l JOIN products p ON p.id = l.product_id
WHERE l.unit_cost IS NULL AND p.cost_price IS NOT NULL;
GO
