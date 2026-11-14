package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_no", nullable = false, length = 50)
    private String transactionNo;

    @Column(name = "product_id", nullable = false)
    private Integer productId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "list_price", precision = 12, scale = 2)
    private BigDecimal listPrice;
    @Column(name = "revenue", nullable = false, precision = 16, scale = 2)
    private BigDecimal revenue;

    /** Added by the cashier from the "bought together" suggestions (to measure whether suggestions help). */
    @Column(name = "from_suggestion", nullable = false)
    private Boolean fromSuggestion = false;

    public OrderItem() {}

    public Boolean getFromSuggestion() { return fromSuggestion; }
    public void setFromSuggestion(Boolean fromSuggestion) { this.fromSuggestion = fromSuggestion; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getListPrice() { return listPrice; }
    public void setListPrice(BigDecimal listPrice) { this.listPrice = listPrice; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
}