package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Bảng shift_cash_movements: phiếu thu (IN) / chi (OUT) tiền mặt trong ca, tính vào tiền mặt phải có khi kết ca. */
@Entity
@Table(name = "shift_cash_movements")
public class ShiftCashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "shift_id", nullable = false)
    private Integer shiftId;

    /** IN (thu vào két) hoặc OUT (chi ra khỏi két). */
    @Column(name = "kind", nullable = false, length = 3)
    private String kind;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private Integer createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ShiftCashMovement() {}

    public Integer getId() { return id; }
    public Integer getShiftId() { return shiftId; }
    public String getKind() { return kind; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public Integer getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
