-- Nhập hàng theo lô và hạn sử dụng (mỹ phẩm có hạn dùng). Chỉ thêm mới, chạy lại vẫn an toàn.
-- Quy ước: products.stock_quantity = tổng quantity_remaining của các lô (kể cả lô hết hạn chưa xuất hủy).
-- Hàng "bán được" = các lô còn hạn (hoặc không ghi hạn); khi bán, hệ thống lấy lô có hạn gần nhất trước (FEFO).

IF OBJECT_ID('dbo.stock_receipts', 'U') IS NULL
CREATE TABLE stock_receipts (
    id INT IDENTITY(1,1) PRIMARY KEY,
    receipt_no VARCHAR(30) NOT NULL UNIQUE,     -- PNyyMMddHHmmss + 4 số
    supplier NVARCHAR(150) NULL,
    note NVARCHAR(300) NULL,
    created_by INT NULL REFERENCES users(id),
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME()
);
GO

IF OBJECT_ID('dbo.stock_lots', 'U') IS NULL
CREATE TABLE stock_lots (
    id INT IDENTITY(1,1) PRIMARY KEY,
    product_id INT NOT NULL REFERENCES products(id),
    receipt_id INT NULL REFERENCES stock_receipts(id),     -- NULL: lô tồn đầu kỳ
    lot_no VARCHAR(50) NOT NULL,
    expiry_date DATE NULL,                                  -- NULL: không ghi hạn dùng
    quantity_received INT NOT NULL,
    quantity_remaining INT NOT NULL CONSTRAINT ck_lots_remaining CHECK (quantity_remaining >= 0),
    unit_cost NUMERIC(12,2) NULL,
    is_demo BIT NOT NULL DEFAULT 0,                         -- 1: hạn dùng GIẢ LẬP để minh họa (lô tồn đầu kỳ)
    received_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    disposed_quantity INT NOT NULL DEFAULT 0,               -- số lượng đã xuất hủy (hết hạn, hư hỏng)
    disposed_at DATETIME2 NULL,
    disposed_by INT NULL REFERENCES users(id),
    dispose_reason NVARCHAR(200) NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_lots_product')
    CREATE INDEX ix_lots_product ON stock_lots(product_id, expiry_date) INCLUDE (quantity_remaining);
GO

-- Mỗi dòng hóa đơn lấy hàng từ lô nào (để truy vết khi cần thu hồi một lô lỗi).
IF OBJECT_ID('dbo.order_item_lots', 'U') IS NULL
CREATE TABLE order_item_lots (
    order_item_id BIGINT NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    lot_id INT NOT NULL REFERENCES stock_lots(id),
    quantity INT NOT NULL,
    CONSTRAINT pk_order_item_lots PRIMARY KEY (order_item_id, lot_id)
);
GO

-- Lô tồn đầu kỳ cho hàng đang có. HẠN DÙNG LÀ GIẢ LẬP (is_demo = 1, số lô DK-…) vì dữ liệu nền không có hạn dùng:
-- phần lớn còn 4–24 tháng, khoảng 1/23 sản phẩm sắp hết hạn (12–51 ngày) và vài sản phẩm có 5 hộp đã hết hạn
-- (lô DK-…-HH) để minh họa cảnh báo. Nhập hàng thật sau này ghi hạn dùng thật.
INSERT INTO stock_lots(product_id, receipt_id, lot_no, expiry_date, quantity_received, quantity_remaining, unit_cost, is_demo, received_at)
SELECT p.id, NULL, v.lot_no, v.expiry_date, v.quantity, v.quantity, p.cost_price, 1, SYSDATETIME()
FROM products p
CROSS APPLY (SELECT CASE WHEN p.id % 47 = 0 AND p.stock_quantity >= 10 THEN 5 ELSE 0 END AS expired_part,
                    'DK-' + COALESCE(p.barcode, CAST(p.id AS VARCHAR(20))) AS code) d
CROSS APPLY (VALUES
    (d.code, DATEADD(day, CASE WHEN p.id % 23 = 0 THEN 12 + p.id % 40 ELSE 120 + (p.id * 37) % 600 END, CAST(SYSDATETIME() AS DATE)),
     p.stock_quantity - d.expired_part),
    (d.code + '-HH', DATEADD(day, -(3 + p.id % 10), CAST(SYSDATETIME() AS DATE)), d.expired_part)
) v(lot_no, expiry_date, quantity)
WHERE p.stock_quantity > 0 AND v.quantity > 0
  AND NOT EXISTS (SELECT 1 FROM stock_lots l WHERE l.product_id = p.id);
GO
