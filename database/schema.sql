-- Cơ sở dữ liệu cosmetic_crm_db (dữ liệu nền OnlineSales).
-- Tạo bằng: python database/build_database.py  (script tự CREATE DATABASE rồi chạy file này).
-- Mỗi khối được ngăn bằng GO.

CREATE TABLE roles (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE,
    description NVARCHAR(100) NULL
);
GO
INSERT INTO roles(name, description) VALUES ('ADMIN', N'Quản trị hệ thống'), ('MANAGER', N'Quản lý kinh doanh'), ('CASHIER', N'Thu ngân');
GO

CREATE TABLE users (
    id INT IDENTITY(1,1) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name NVARCHAR(100) NOT NULL,
    role_id INT NOT NULL REFERENCES roles(id),
    is_active BIT NOT NULL DEFAULT 1,
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME()
);
GO

-- Nhóm khách của mô hình đang áp dụng (id = số thứ tự nhóm 0..K-1).
CREATE TABLE customer_segments (
    id INT PRIMARY KEY,
    segment_name NVARCHAR(50) NOT NULL,
    color_code VARCHAR(20) NULL
);
GO

CREATE TABLE customers (
    customer_id INT PRIMARY KEY,           -- giữ nguyên CustomerID của OnlineSales
    customer_code VARCHAR(20) NOT NULL UNIQUE,
    phone VARCHAR(20) NULL,                -- khách dữ liệu gốc: số GIẢ LẬP 0100xxxxxx để tra cứu tại quầy
    full_name NVARCHAR(150) NULL,          -- khách dữ liệu gốc: họ tên GIẢ LẬP
    segment_id INT NULL,                   -- nhóm hiện tại theo mô hình đang áp dụng; NULL = khách mới chưa mua
    segment_scored_at DATETIME2 NULL,      -- lần cuối khách được đặt lên thước đo của mô hình
    total_orders INT NOT NULL DEFAULT 0,   -- tổng đơn từ trước tới nay (hiển thị)
    total_spent NUMERIC(16,2) NOT NULL DEFAULT 0,
    first_purchase DATETIME2 NULL,
    last_purchase DATETIME2 NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME()
);
GO
CREATE UNIQUE INDEX ux_customers_phone ON customers(phone) WHERE phone IS NOT NULL;
CREATE INDEX ix_customers_segment ON customers(segment_id);
GO

CREATE TABLE strategies (
    id INT IDENTITY(1,1) PRIMARY KEY,
    segment_id INT NULL UNIQUE,
    title NVARCHAR(150) NOT NULL,
    discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0,
    gift_description NVARCHAR(255) NULL,
    cashier_script NVARCHAR(MAX) NULL,
    is_active BIT NOT NULL DEFAULT 0,
    updated_at DATETIME2 NOT NULL DEFAULT SYSDATETIME()
);
GO

CREATE TABLE categories (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(100) NOT NULL UNIQUE
);
GO

CREATE TABLE products (
    id INT IDENTITY(1,1) PRIMARY KEY,
    barcode VARCHAR(50) NULL UNIQUE,       -- mã nội bộ SPxxxxx
    product_name NVARCHAR(255) NOT NULL,
    category_id INT NULL REFERENCES categories(id),
    unit_price NUMERIC(12,2) NOT NULL,
    cost_price NUMERIC(12,2) NULL,
    stock_quantity INT NOT NULL DEFAULT 0,
    image_url VARCHAR(500) NULL,
    is_active BIT NOT NULL DEFAULT 1,
    source_title NVARCHAR(500) NULL        -- mô tả gốc tiếng Anh trong OnlineSales (để đối chiếu nguồn)
);
GO
CREATE INDEX ix_products_active ON products(is_active) INCLUDE (product_name, unit_price, stock_quantity, category_id, barcode);
GO

CREATE TABLE shifts (
    id INT IDENTITY(1,1) PRIMARY KEY,
    cashier_id INT NOT NULL REFERENCES users(id),
    opened_at DATETIME2 NOT NULL,
    closed_at DATETIME2 NULL,
    opening_cash NUMERIC(14,2) NOT NULL DEFAULT 0,
    counted_cash NUMERIC(14,2) NULL,
    note NVARCHAR(500) NULL,
    closed_by INT NULL REFERENCES users(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED'))
);
GO
CREATE UNIQUE INDEX ux_shifts_one_open ON shifts(cashier_id) WHERE status = 'OPEN';
CREATE INDEX ix_shifts_opened ON shifts(opened_at);
GO

CREATE TABLE orders (
    transaction_no VARCHAR(50) PRIMARY KEY,
    customer_id INT NULL REFERENCES customers(customer_id),
    cashier_id INT NULL REFERENCES users(id),
    shift_id INT NULL REFERENCES shifts(id),
    order_date DATETIME2 NOT NULL,
    total_amount NUMERIC(16,2) NOT NULL,
    discount_amount NUMERIC(16,2) NOT NULL DEFAULT 0,
    final_amount NUMERIC(16,2) NOT NULL,
    payment_method VARCHAR(30) NOT NULL DEFAULT 'CASH',   -- ONLINE = hóa đơn lịch sử của dữ liệu nền
    applied_strategy_id INT NULL
);
GO
CREATE INDEX ix_orders_date ON orders(order_date) INCLUDE (final_amount, total_amount, discount_amount, payment_method, customer_id);
CREATE INDEX ix_orders_customer ON orders(customer_id) INCLUDE (order_date, final_amount);
CREATE INDEX ix_orders_shift ON orders(shift_id);
GO

CREATE TABLE order_items (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    transaction_no VARCHAR(50) NOT NULL REFERENCES orders(transaction_no) ON DELETE CASCADE,
    product_id INT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL,      -- giá thu thực tế (0 với quà tặng)
    list_price NUMERIC(12,2) NULL,          -- giá niêm yết lúc bán, dùng để định giá quà tặng
    revenue NUMERIC(16,2) NOT NULL
);
GO
CREATE INDEX ix_items_tx ON order_items(transaction_no);
CREATE INDEX ix_items_product ON order_items(product_id) INCLUDE (quantity, unit_price, revenue);
GO

-- Các phiên bản mô hình phân cụm LRFM. Mỗi phiên bản lưu công khai "thước đo" của nó:
-- ngày tham chiếu, ngưỡng cắt, mean/std, tâm cụm, tên nhóm và chỉ số đánh giá (trong model_json).
-- Chỉ một phiên bản ACTIVE; phiên bản cũ chuyển ARCHIVED và không bao giờ bị xóa.
CREATE TABLE model_versions (
    id INT IDENTITY(1,1) PRIMARY KEY,
    version VARCHAR(64) NOT NULL UNIQUE,
    label NVARCHAR(20) NOT NULL,            -- v1, v2, ...
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    k INT NOT NULL,
    reference_date DATE NOT NULL,
    training_source NVARCHAR(300) NOT NULL,
    training_rows INT NOT NULL,
    model_json NVARCHAR(MAX) NOT NULL,
    reason NVARCHAR(500) NULL,
    created_by NVARCHAR(100) NULL,
    created_at DATETIME2 NOT NULL,
    activated_at DATETIME2 NULL,
    archived_at DATETIME2 NULL
);
GO
CREATE UNIQUE INDEX ux_model_versions_active ON model_versions(status) WHERE status = 'ACTIVE';
GO

-- Nhật ký kiểm tra lệch dữ liệu (bước 7).
CREATE TABLE drift_checks (
    id INT IDENTITY(1,1) PRIMARY KEY,
    model_version VARCHAR(64) NOT NULL,
    checked_at DATETIME2 NOT NULL,
    as_of_date DATE NOT NULL,
    sample_size INT NOT NULL,
    drift_detected BIT NOT NULL,
    result_json NVARCHAR(MAX) NOT NULL
);
GO

CREATE TABLE strategy_history (
    original_strategy_id INT NOT NULL PRIMARY KEY,
    segment_id INT NULL,
    snapshot_json NVARCHAR(MAX) NOT NULL,
    archived_at DATETIME2 NOT NULL,
    deployment_version VARCHAR(64) NOT NULL
);
GO

-- Tài khoản thành viên của khách: khách tự đăng nhập trang /member bằng số điện thoại + PIN 6 số.
-- PIN và mã kích hoạt chỉ lưu dạng băm BCrypt. Khách cũ (đã có trong customers) nhận thẻ bằng mã kích hoạt do thu ngân cấp.
CREATE TABLE member_accounts (
    customer_id INT NOT NULL PRIMARY KEY REFERENCES customers(customer_id),
    pin_hash VARCHAR(100) NULL,                  -- NULL = chưa kích hoạt thẻ
    activation_code_hash VARCHAR(100) NULL,      -- mã 6 số dùng một lần, hết hạn sau 10 phút
    activation_expires_at DATETIME2 NULL,
    failed_attempts INT NOT NULL DEFAULT 0,      -- nhập sai PIN/mã liên tiếp
    locked_until DATETIME2 NULL,                 -- khóa đăng nhập tạm thời sau 5 lần sai
    created_at DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    last_login_at DATETIME2 NULL
);
GO

-- Sổ điểm tích lũy: mỗi dòng là một lần cộng điểm (1 điểm cho mỗi 10.000 đ khách thực trả). Số điểm = SUM(points).
CREATE TABLE loyalty_points (
    id BIGINT IDENTITY(1,1) PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES customers(customer_id),
    transaction_no VARCHAR(50) NULL REFERENCES orders(transaction_no),
    points INT NOT NULL,
    note NVARCHAR(200) NOT NULL,
    created_at DATETIME2 NOT NULL
);
GO
CREATE INDEX ix_points_customer ON loyalty_points(customer_id) INCLUDE (points, created_at);
CREATE UNIQUE INDEX ux_points_invoice ON loyalty_points(transaction_no) WHERE transaction_no IS NOT NULL;
GO
