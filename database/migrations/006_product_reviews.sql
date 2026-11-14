-- Khách đánh giá sản phẩm đã mua tại quầy + phân tích cảm xúc (sách Essentials of Marketing Analytics, chương 10).
-- Chỉ thêm mới, chạy lại vẫn an toàn.

IF OBJECT_ID('dbo.product_reviews', 'U') IS NULL
CREATE TABLE product_reviews (
    id INT IDENTITY(1,1) PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES customers(customer_id),
    product_id INT NOT NULL REFERENCES products(id),
    rating TINYINT NOT NULL CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    comment NVARCHAR(500) NULL,
    sentiment VARCHAR(10) NULL CONSTRAINT ck_review_sentiment CHECK (sentiment IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE')),
    sentiment_score DECIMAL(6,3) NULL,      -- -1 (chê) … +1 (khen); NULL: chưa phân tích được (dịch vụ AI tắt)
    topics NVARCHAR(200) NULL,              -- VD "Mùi hương, Giá"
    irritation BIT NOT NULL DEFAULT 0,      -- 1: khách báo dị ứng / kích ứng da → cảnh báo quản lý
    status VARCHAR(10) NOT NULL DEFAULT 'VISIBLE' CONSTRAINT ck_review_status CHECK (status IN ('VISIBLE', 'HIDDEN')),
    created_at DATETIME2 NOT NULL,
    updated_at DATETIME2 NOT NULL,
    analyzed_at DATETIME2 NULL,
    hidden_by INT NULL REFERENCES users(id),
    CONSTRAINT ux_review_customer_product UNIQUE (customer_id, product_id)   -- mỗi khách một đánh giá cho mỗi sản phẩm (sửa được)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_reviews_product')
    CREATE INDEX ix_reviews_product ON product_reviews(product_id, status) INCLUDE (rating, sentiment, irritation);
GO

-- Điểm thưởng cho lần đánh giá đầu tiên (có nhận xét từ 20 ký tự) của mỗi sản phẩm: một dòng điểm BONUS cho mỗi đánh giá.
IF COL_LENGTH('dbo.loyalty_points', 'review_id') IS NULL
    ALTER TABLE loyalty_points ADD review_id INT NULL CONSTRAINT fk_points_review REFERENCES product_reviews(id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_points_review')
    CREATE UNIQUE INDEX ux_points_review ON loyalty_points(review_id) WHERE review_id IS NOT NULL;
GO
