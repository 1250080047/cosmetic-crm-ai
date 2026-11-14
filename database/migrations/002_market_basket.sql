-- Gợi ý mua kèm (phân tích giỏ hàng — sách Essentials of Marketing Analytics, chương 9). Chỉ thêm mới, chạy lại vẫn an toàn.

-- Mỗi lần tính quy tắc (khi khởi động lần đầu hoặc bấm "Tính lại" ở trang quản trị) — giữ lại làm lịch sử.
IF OBJECT_ID('dbo.association_runs', 'U') IS NULL
CREATE TABLE association_runs (
    id INT IDENTITY(1,1) PRIMARY KEY,
    computed_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    basket_count INT NOT NULL,              -- số hóa đơn đã đếm (dữ liệu nền + bán tại quầy)
    store_basket_count INT NOT NULL,        -- trong đó hóa đơn bán tại quầy
    min_together INT NOT NULL,              -- cặp phải được mua chung ít nhất ngần này hóa đơn
    rule_count INT NOT NULL,
    requested_by NVARCHAR(100) NULL
);
GO

-- Quy tắc A → B của lần tính mới nhất: "khách mua A thường mua B".
IF OBJECT_ID('dbo.product_associations', 'U') IS NULL
CREATE TABLE product_associations (
    run_id INT NOT NULL REFERENCES association_runs(id),
    antecedent_id INT NOT NULL REFERENCES products(id),   -- A: sản phẩm đã có trong giỏ
    consequent_id INT NOT NULL REFERENCES products(id),   -- B: sản phẩm gợi ý
    together_count INT NOT NULL,            -- số hóa đơn có cả A và B
    antecedent_count INT NOT NULL,          -- số hóa đơn có A
    consequent_count INT NOT NULL,          -- số hóa đơn có B
    support DECIMAL(9,6) NOT NULL,          -- có cả A và B / tất cả hóa đơn
    confidence DECIMAL(9,6) NOT NULL,       -- có cả A và B / có A
    lift DECIMAL(12,4) NOT NULL,            -- confidence / tỉ lệ hóa đơn có B (> 1: hay mua chung hơn ngẫu nhiên)
    CONSTRAINT pk_product_associations PRIMARY KEY (run_id, antecedent_id, consequent_id)
);
GO

-- Món thu ngân thêm vào giỏ từ khung "Khách hay mua kèm" (để đo gợi ý có ích không).
IF COL_LENGTH('dbo.order_items', 'from_suggestion') IS NULL
    ALTER TABLE order_items ADD from_suggestion BIT NOT NULL CONSTRAINT df_items_from_suggestion DEFAULT 0;
GO
