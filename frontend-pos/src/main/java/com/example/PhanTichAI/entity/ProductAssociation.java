package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Bảng product_associations: quy tắc "khách mua A thường mua B" của lần tính mới nhất (support, confidence, lift).
 * Dịch vụ AI (Python) ghi; POS đọc để gợi ý mua kèm (ProductSuggestionService), nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "product_associations")
@IdClass(ProductAssociation.Key.class)
public class ProductAssociation {

    @Id
    @Column(name = "run_id", nullable = false)
    private Integer runId;

    /** A: sản phẩm đã có trong giỏ. */
    @Id
    @Column(name = "antecedent_id", nullable = false)
    private Integer antecedentId;

    /** B: sản phẩm được gợi ý. */
    @Id
    @Column(name = "consequent_id", nullable = false)
    private Integer consequentId;

    @Column(name = "together_count", nullable = false)
    private Integer togetherCount;

    @Column(name = "antecedent_count", nullable = false)
    private Integer antecedentCount;

    @Column(name = "consequent_count", nullable = false)
    private Integer consequentCount;

    @Column(name = "support", nullable = false, precision = 9, scale = 6)
    private BigDecimal support;

    @Column(name = "confidence", nullable = false, precision = 9, scale = 6)
    private BigDecimal confidence;

    @Column(name = "lift", nullable = false, precision = 12, scale = 4)
    private BigDecimal lift;

    public ProductAssociation() {}

    public Integer getRunId() { return runId; }
    public Integer getAntecedentId() { return antecedentId; }
    public Integer getConsequentId() { return consequentId; }
    public Integer getTogetherCount() { return togetherCount; }
    public Integer getAntecedentCount() { return antecedentCount; }
    public Integer getConsequentCount() { return consequentCount; }
    public BigDecimal getSupport() { return support; }
    public BigDecimal getConfidence() { return confidence; }
    public BigDecimal getLift() { return lift; }

    /** Khóa ghép (run_id, antecedent_id, consequent_id). */
    public static class Key implements Serializable {
        private Integer runId;
        private Integer antecedentId;
        private Integer consequentId;

        public Key() {}

        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(runId, key.runId)
                    && Objects.equals(antecedentId, key.antecedentId) && Objects.equals(consequentId, key.consequentId);
        }
        @Override public int hashCode() { return Objects.hash(runId, antecedentId, consequentId); }
    }
}
