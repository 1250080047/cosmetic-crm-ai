-- Đối soát tiền mặt cuối ca. Chỉ thêm mới, chạy lại vẫn an toàn.
-- Tiền mặt phải có = tiền đầu ca + tiền mặt bán hàng + phiếu thu − phiếu chi trong ca.
-- Lệch quá ±10.000 đ: thu ngân phải ghi lý do, ca chờ quản lý / quản trị viên xác nhận cách xử lý.

IF COL_LENGTH('dbo.shifts', 'cash_breakdown') IS NULL ALTER TABLE shifts ADD cash_breakdown NVARCHAR(400) NULL;   -- số tờ theo mệnh giá (JSON)
IF COL_LENGTH('dbo.shifts', 'expected_cash') IS NULL ALTER TABLE shifts ADD expected_cash NUMERIC(14,2) NULL;      -- tiền mặt phải có lúc kết ca
IF COL_LENGTH('dbo.shifts', 'cash_difference') IS NULL ALTER TABLE shifts ADD cash_difference NUMERIC(14,2) NULL;  -- đếm được − phải có
IF COL_LENGTH('dbo.shifts', 'discrepancy_reason') IS NULL ALTER TABLE shifts ADD discrepancy_reason NVARCHAR(500) NULL;  -- lý do thu ngân ghi
IF COL_LENGTH('dbo.shifts', 'review_status') IS NULL ALTER TABLE shifts ADD review_status VARCHAR(10) NULL;        -- NULL / PENDING / RESOLVED
IF COL_LENGTH('dbo.shifts', 'review_decision') IS NULL ALTER TABLE shifts ADD review_decision VARCHAR(20) NULL;
IF COL_LENGTH('dbo.shifts', 'review_note') IS NULL ALTER TABLE shifts ADD review_note NVARCHAR(500) NULL;
IF COL_LENGTH('dbo.shifts', 'reviewed_by') IS NULL ALTER TABLE shifts ADD reviewed_by INT NULL CONSTRAINT fk_shifts_reviewed_by REFERENCES users(id);
IF COL_LENGTH('dbo.shifts', 'reviewed_at') IS NULL ALTER TABLE shifts ADD reviewed_at DATETIME2 NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_shifts_review_status')
    ALTER TABLE shifts ADD CONSTRAINT ck_shifts_review_status CHECK (review_status IN ('PENDING', 'RESOLVED'));
-- STAFF_REPAY: nhân viên bồi hoàn phần thiếu · STORE_EXPENSE: cửa hàng chịu (chi phí) · ADD_TO_FUND: nộp phần dư vào quỹ (thu khác)
-- CORRECTED: tìm ra nguyên nhân và đã sửa (VD bấm nhầm phương thức thanh toán), không còn lệch
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_shifts_review_decision')
    ALTER TABLE shifts ADD CONSTRAINT ck_shifts_review_decision CHECK (review_decision IN ('STAFF_REPAY', 'STORE_EXPENSE', 'ADD_TO_FUND', 'CORRECTED'));
GO

-- Phiếu thu / chi tiền mặt trong ca (VD chi mua nước, nộp bớt tiền về két chính, nhận thêm tiền lẻ).
IF OBJECT_ID('dbo.shift_cash_movements', 'U') IS NULL
CREATE TABLE shift_cash_movements (
    id INT IDENTITY(1,1) PRIMARY KEY,
    shift_id INT NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    kind VARCHAR(3) NOT NULL CONSTRAINT ck_movement_kind CHECK (kind IN ('IN', 'OUT')),
    amount NUMERIC(14,2) NOT NULL CONSTRAINT ck_movement_amount CHECK (amount > 0),
    reason NVARCHAR(200) NOT NULL,
    created_by INT NOT NULL REFERENCES users(id),
    created_at DATETIME2 NOT NULL
);
GO

-- Lịch sử sửa phương thức thanh toán khi đối soát (ai sửa, lúc nào, vì sao).
IF OBJECT_ID('dbo.order_payment_changes', 'U') IS NULL
CREATE TABLE order_payment_changes (
    id INT IDENTITY(1,1) PRIMARY KEY,
    transaction_no VARCHAR(50) NOT NULL REFERENCES orders(transaction_no) ON DELETE CASCADE,
    shift_id INT NULL,
    old_method VARCHAR(30) NOT NULL,
    new_method VARCHAR(30) NOT NULL,
    reason NVARCHAR(300) NOT NULL,
    changed_by INT NULL REFERENCES users(id),
    changed_at DATETIME2 NOT NULL
);
GO

-- Ca đã kết trước khi có đối soát: ghi tiền phải có / chênh lệch; lệch quá ±10.000 đ hoặc chưa đếm tiền → chờ xác nhận.
UPDATE s SET expected_cash = s.opening_cash + COALESCE((SELECT SUM(o.final_amount) FROM orders o WHERE o.shift_id = s.id AND o.payment_method = 'CASH'), 0),
             cash_difference = s.counted_cash - (s.opening_cash + COALESCE((SELECT SUM(o.final_amount) FROM orders o WHERE o.shift_id = s.id AND o.payment_method = 'CASH'), 0))
FROM shifts s
WHERE s.status = 'CLOSED' AND s.expected_cash IS NULL;
UPDATE shifts SET review_status = 'PENDING'
WHERE status = 'CLOSED' AND review_status IS NULL AND (counted_cash IS NULL OR ABS(cash_difference) > 10000);
GO
