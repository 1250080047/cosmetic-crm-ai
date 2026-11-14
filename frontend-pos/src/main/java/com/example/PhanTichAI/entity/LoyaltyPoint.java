package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Bảng loyalty_points: sổ điểm tích lũy. Mỗi dòng là một lần cộng (số dương) hoặc đổi điểm (số âm);
 * số điểm hiện có = tổng cột points.
 */
@Entity
@Table(name = "loyalty_points")
public class LoyaltyPoint {
    public static final String EARN = "EARN", REDEEM = "REDEEM", BONUS = "BONUS", ADJUST = "ADJUST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    /** Hóa đơn sinh ra điểm (mỗi hóa đơn cộng điểm tối đa một lần). */
    @Column(name = "transaction_no", length = 50)
    private String transactionNo;

    @Column(name = "points", nullable = false)
    private Integer points;

    @Column(name = "note", nullable = false, length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** EARN (tích điểm hóa đơn), REDEEM (đổi điểm), BONUS (thưởng), ADJUST (điều chỉnh). Mỗi hóa đơn tối đa một dòng mỗi loại. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind = EARN;

    public LoyaltyPoint() {}

    public LoyaltyPoint(Integer customerId, String transactionNo, Integer points, String note, LocalDateTime createdAt) {
        this(customerId, transactionNo, points, note, createdAt, EARN);
    }

    public LoyaltyPoint(Integer customerId, String transactionNo, Integer points, String note, LocalDateTime createdAt, String kind) {
        this.customerId = customerId;
        this.transactionNo = transactionNo;
        this.points = points;
        this.note = note;
        this.createdAt = createdAt;
        this.kind = kind;
    }

    public String getKind() { return kind; }

    public Long getId() { return id; }
    public Integer getCustomerId() { return customerId; }
    public String getTransactionNo() { return transactionNo; }
    public Integer getPoints() { return points; }
    public String getNote() { return note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
