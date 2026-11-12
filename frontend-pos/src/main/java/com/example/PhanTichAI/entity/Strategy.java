package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategies")
public class Strategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "segment_id", unique = true)
    private Integer segmentId;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent;

    @Column(name = "gift_description", length = 255)
    private String giftDescription;

    @Column(name = "cashier_script", columnDefinition = "NVARCHAR(MAX)")
    private String cashierScript;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Strategy() {}

    public Strategy(Integer segmentId, String title, BigDecimal discountPercent, String giftDescription, String cashierScript) {
        this.segmentId = segmentId;
        this.title = title;
        this.discountPercent = discountPercent;
        this.giftDescription = giftDescription;
        this.cashierScript = cashierScript;
        this.updatedAt = LocalDateTime.now();
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getSegmentId() { return segmentId; }
    public void setSegmentId(Integer segmentId) { this.segmentId = segmentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public BigDecimal getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(BigDecimal discountPercent) { this.discountPercent = discountPercent; }

    public String getGiftDescription() { return giftDescription; }
    public void setGiftDescription(String giftDescription) { this.giftDescription = giftDescription; }

    public String getCashierScript() { return cashierScript; }
    public void setCashierScript(String cashierScript) { this.cashierScript = cashierScript; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}