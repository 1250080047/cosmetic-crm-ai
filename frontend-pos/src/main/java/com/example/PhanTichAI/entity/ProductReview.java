package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bảng product_reviews: đánh giá của khách cho sản phẩm đã mua tại quầy (1–5 sao + nhận xét) và kết quả phân tích cảm xúc
 * (backend-pos/sentiment_service.py). Mỗi khách một đánh giá cho mỗi sản phẩm. Ghi bởi ReviewService.
 */
@Entity
@Table(name = "product_reviews")
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(name = "product_id", nullable = false)
    private Integer productId;

    @Column(name = "rating", nullable = false)
    private Short rating;

    @Column(name = "comment", length = 500)
    private String comment;

    /** POSITIVE / NEUTRAL / NEGATIVE; NULL khi chưa phân tích được. */
    @Column(name = "sentiment", length = 10)
    private String sentiment;

    /** -1 (chê) … +1 (khen). */
    @Column(name = "sentiment_score", precision = 6, scale = 3)
    private BigDecimal sentimentScore;

    /** Chủ đề, VD "Mùi hương, Giá". */
    @Column(name = "topics", length = 200)
    private String topics;

    /** Khách báo dị ứng / kích ứng da. */
    @Column(name = "irritation", nullable = false)
    private Boolean irritation = false;

    /** VISIBLE hoặc HIDDEN (quản lý ẩn). */
    @Column(name = "status", nullable = false, length = 10)
    private String status = "VISIBLE";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "hidden_by")
    private Integer hiddenBy;

    public ProductReview() {}

    public Integer getId() { return id; }
    public Integer getCustomerId() { return customerId; }
    public Integer getProductId() { return productId; }
    public Short getRating() { return rating; }
    public String getComment() { return comment; }
    public String getSentiment() { return sentiment; }
    public BigDecimal getSentimentScore() { return sentimentScore; }
    public String getTopics() { return topics; }
    public Boolean getIrritation() { return irritation; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public Integer getHiddenBy() { return hiddenBy; }
}
