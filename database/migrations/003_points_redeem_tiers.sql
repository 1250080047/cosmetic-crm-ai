-- Đổi điểm tại quầy và hạng thành viên. Chỉ thêm mới, chạy lại vẫn an toàn.

-- Loại dòng trong sổ điểm: EARN (tích điểm hóa đơn), REDEEM (đổi điểm, số âm), BONUS (thưởng, VD đánh giá), ADJUST (điều chỉnh).
IF COL_LENGTH('dbo.loyalty_points', 'kind') IS NULL
    ALTER TABLE loyalty_points ADD kind VARCHAR(10) NOT NULL CONSTRAINT df_points_kind DEFAULT 'EARN';
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_points_kind')
    ALTER TABLE loyalty_points ADD CONSTRAINT ck_points_kind CHECK (kind IN ('EARN', 'REDEEM', 'BONUS', 'ADJUST'));
GO
-- Một hóa đơn có thể vừa đổi điểm vừa tích điểm: mỗi loại tối đa một dòng cho mỗi hóa đơn.
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_points_invoice' AND object_id = OBJECT_ID('dbo.loyalty_points'))
    DROP INDEX ux_points_invoice ON loyalty_points;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ux_points_invoice_kind')
    CREATE UNIQUE INDEX ux_points_invoice_kind ON loyalty_points(transaction_no, kind) WHERE transaction_no IS NOT NULL;
GO

-- Điểm khách đã dùng trên hóa đơn và số tiền được giảm nhờ điểm (đã nằm trong discount_amount).
IF COL_LENGTH('dbo.orders', 'points_redeemed') IS NULL
    ALTER TABLE orders ADD points_redeemed INT NOT NULL CONSTRAINT df_orders_points_redeemed DEFAULT 0;
IF COL_LENGTH('dbo.orders', 'points_discount') IS NULL
    ALTER TABLE orders ADD points_discount NUMERIC(16,2) NOT NULL CONSTRAINT df_orders_points_discount DEFAULT 0;
GO
