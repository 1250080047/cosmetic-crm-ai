package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Bảng order_payment_changes: lịch sử sửa phương thức thanh toán của hóa đơn khi đối soát tiền cuối ca. */
@Entity
@Table(name = "order_payment_changes")
public class OrderPaymentChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "transaction_no", nullable = false, length = 50)
    private String transactionNo;

    @Column(name = "shift_id")
    private Integer shiftId;

    @Column(name = "old_method", nullable = false, length = 30)
    private String oldMethod;

    @Column(name = "new_method", nullable = false, length = 30)
    private String newMethod;

    @Column(name = "reason", nullable = false, length = 300)
    private String reason;

    /** users.id của người sửa (quản lý / quản trị viên). */
    @Column(name = "changed_by")
    private Integer changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    public OrderPaymentChange() {}

    public Integer getId() { return id; }
    public String getTransactionNo() { return transactionNo; }
    public Integer getShiftId() { return shiftId; }
    public String getOldMethod() { return oldMethod; }
    public String getNewMethod() { return newMethod; }
    public String getReason() { return reason; }
    public Integer getChangedBy() { return changedBy; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
