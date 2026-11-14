package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "transaction_no", length = 50)
    private String transactionNo;

    @Column(name = "customer_id")
    private Integer customerId;

    @Column(name = "cashier_id")
    private Integer cashierId;

    @Column(name = "shift_id")
    private Integer shiftId;
    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    @Column(name = "total_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "discount_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "final_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal finalAmount;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod = "CASH";

    @Column(name = "applied_strategy_id")
    private Integer appliedStrategyId;

    /** Điểm khách đã dùng trên hóa đơn này. */
    @Column(name = "points_redeemed", nullable = false)
    private Integer pointsRedeemed = 0;

    /** Số tiền được giảm nhờ đổi điểm (đã tính trong discount_amount). */
    @Column(name = "points_discount", nullable = false, precision = 16, scale = 2)
    private BigDecimal pointsDiscount = BigDecimal.ZERO;

    public Order() {}

    public Integer getPointsRedeemed() { return pointsRedeemed; }
    public void setPointsRedeemed(Integer pointsRedeemed) { this.pointsRedeemed = pointsRedeemed; }
    public BigDecimal getPointsDiscount() { return pointsDiscount; }
    public void setPointsDiscount(BigDecimal pointsDiscount) { this.pointsDiscount = pointsDiscount; }

    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public Integer getCashierId() { return cashierId; }
    public void setCashierId(Integer cashierId) { this.cashierId = cashierId; }

    public Integer getShiftId() { return shiftId; }
    public void setShiftId(Integer shiftId) { this.shiftId = shiftId; }
    public LocalDateTime getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDateTime orderDate) { this.orderDate = orderDate; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public Integer getAppliedStrategyId() { return appliedStrategyId; }
    public void setAppliedStrategyId(Integer appliedStrategyId) { this.appliedStrategyId = appliedStrategyId; }
}