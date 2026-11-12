package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Bảng loyalty_points: sổ điểm tích lũy. Mỗi dòng là một lần cộng điểm; số điểm hiện có = tổng cột points. */
@Entity
@Table(name = "loyalty_points")
public class LoyaltyPoint {

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

    public LoyaltyPoint() {}

    public LoyaltyPoint(Integer customerId, String transactionNo, Integer points, String note, LocalDateTime createdAt) {
        this.customerId = customerId;
        this.transactionNo = transactionNo;
        this.points = points;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Integer getCustomerId() { return customerId; }
    public String getTransactionNo() { return transactionNo; }
    public Integer getPoints() { return points; }
    public String getNote() { return note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
