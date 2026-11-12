package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "customer_id")
    private Integer customerId;

    @Column(name = "customer_code", length = 20, nullable = false, unique = true)
    private String customerCode;
    @Column(name = "phone", unique = true, length = 20)
    private String phone;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(name = "segment_id")
    private Integer segmentId;

    @Column(name = "segment_scored_at")
    private LocalDateTime segmentScoredAt;

    @Column(name = "total_orders", nullable = false)
    private Integer totalOrders = 0;

    @Column(name = "total_spent", nullable = false, precision = 16, scale = 2)
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(name = "first_purchase")
    private LocalDateTime firstPurchase;

    @Column(name = "last_purchase")
    private LocalDateTime lastPurchase;

    /** Set by SQL Server (DEFAULT SYSDATETIME()); never written from Java. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Customer() {}

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Integer getSegmentId() { return segmentId; }
    public void setSegmentId(Integer segmentId) { this.segmentId = segmentId; }

    public LocalDateTime getSegmentScoredAt() { return segmentScoredAt; }
    public void setSegmentScoredAt(LocalDateTime segmentScoredAt) { this.segmentScoredAt = segmentScoredAt; }

    public Integer getTotalOrders() { return totalOrders; }
    public void setTotalOrders(Integer totalOrders) { this.totalOrders = totalOrders; }

    public BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(BigDecimal totalSpent) { this.totalSpent = totalSpent; }

    public LocalDateTime getFirstPurchase() { return firstPurchase; }
    public void setFirstPurchase(LocalDateTime firstPurchase) { this.firstPurchase = firstPurchase; }

    public LocalDateTime getLastPurchase() { return lastPurchase; }
    public void setLastPurchase(LocalDateTime lastPurchase) { this.lastPurchase = lastPurchase; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}