-- Đo hiệu quả ưu đãi bằng nhóm đối chứng (holdout). Chỉ thêm mới, chạy lại vẫn an toàn.
-- Khi quản lý BẬT một chiến lược, toàn bộ khách đang thuộc nhóm đó được chia ngẫu nhiên (cố định theo mã khách):
-- 90% nhận ưu đãi (OFFER), 10% không nhận (CONTROL). Sau 30 ngày so tỉ lệ quay lại mua tại quầy của hai nhóm.
-- Không khóa ngoại tới strategies vì chiến lược được thay mới khi áp dụng phân cụm khác (tên chiến lược được lưu lại).

IF OBJECT_ID('dbo.campaign_experiments', 'U') IS NULL
CREATE TABLE campaign_experiments (
    id INT IDENTITY(1,1) PRIMARY KEY,
    strategy_id INT NOT NULL,
    segment_id INT NULL,
    strategy_title NVARCHAR(150) NOT NULL,
    control_percent INT NOT NULL DEFAULT 10,
    window_days INT NOT NULL DEFAULT 30,
    started_at DATETIME2 NOT NULL,
    ended_at DATETIME2 NULL,                 -- NULL: đang đo (chiến lược đang bật)
    started_by NVARCHAR(100) NULL,
    cohort_size INT NOT NULL DEFAULT 0
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_experiment_running')
    CREATE UNIQUE INDEX ux_experiment_running ON campaign_experiments(strategy_id) WHERE ended_at IS NULL;
GO

IF OBJECT_ID('dbo.campaign_assignments', 'U') IS NULL
CREATE TABLE campaign_assignments (
    experiment_id INT NOT NULL REFERENCES campaign_experiments(id),
    customer_id INT NOT NULL REFERENCES customers(customer_id),
    arm VARCHAR(10) NOT NULL CONSTRAINT ck_assignment_arm CHECK (arm IN ('OFFER', 'CONTROL')),
    assigned_at DATETIME2 NOT NULL,
    CONSTRAINT pk_campaign_assignments PRIMARY KEY (experiment_id, customer_id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_assignments_customer')
    CREATE INDEX ix_assignments_customer ON campaign_assignments(customer_id) INCLUDE (arm);
GO
