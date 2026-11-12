-- Thêm thẻ thành viên tự phục vụ và tích điểm vào database ĐANG CHẠY (chỉ thêm mới, không sửa/xóa dữ liệu cũ).
-- Chạy: python database/migrate.py   (chạy lại nhiều lần vẫn an toàn)

IF OBJECT_ID('dbo.member_accounts', 'U') IS NULL
CREATE TABLE member_accounts (
    customer_id INT NOT NULL PRIMARY KEY REFERENCES customers(customer_id),
    pin_hash VARCHAR(100) NULL,
    activation_code_hash VARCHAR(100) NULL,
    activation_expires_at DATETIME2 NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME2 NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    last_login_at DATETIME2 NULL
);
GO

IF OBJECT_ID('dbo.loyalty_points', 'U') IS NULL
CREATE TABLE loyalty_points (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES customers(customer_id),
    transaction_no VARCHAR(50) NULL REFERENCES orders(transaction_no),
    points INT NOT NULL,
    note NVARCHAR(200) NOT NULL,
    created_at DATETIME2 NOT NULL
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_points_customer')
    CREATE INDEX ix_points_customer ON loyalty_points(customer_id) INCLUDE (points, created_at);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_points_invoice')
    CREATE UNIQUE INDEX ux_points_invoice ON loyalty_points(transaction_no) WHERE transaction_no IS NOT NULL;
GO

-- Hóa đơn bán TẠI QUẦY trước khi có tích điểm cũng được cộng điểm theo đúng quy tắc 1 điểm / 10.000 đ thực trả.
-- Hóa đơn lịch sử OnlineSales (ONLINE) không tính điểm.
INSERT INTO loyalty_points(customer_id, transaction_no, points, note, created_at)
SELECT o.customer_id, o.transaction_no, CAST(FLOOR(o.final_amount / 10000) AS INT),
       N'Cộng điểm hóa đơn bán tại quầy trước khi có tích điểm', o.order_date
FROM orders o
WHERE o.customer_id IS NOT NULL AND o.payment_method <> 'ONLINE' AND FLOOR(o.final_amount / 10000) > 0
  AND NOT EXISTS (SELECT 1 FROM loyalty_points p WHERE p.transaction_no = o.transaction_no);
GO
