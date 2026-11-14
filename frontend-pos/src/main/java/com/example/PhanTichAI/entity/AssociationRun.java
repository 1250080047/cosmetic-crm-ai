package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.LocalDateTime;

/**
 * Bảng association_runs: mỗi lần tính quy tắc "mua kèm" (phân tích giỏ hàng). Dịch vụ AI (Python, basket_service.py)
 * ghi bảng này, nên phía Java chỉ đọc (@Immutable).
 */
@Entity
@Immutable
@Table(name = "association_runs")
public class AssociationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    /** Số hóa đơn đã đếm (dữ liệu nền + bán tại quầy). */
    @Column(name = "basket_count", nullable = false)
    private Integer basketCount;

    /** Trong đó số hóa đơn bán tại quầy. */
    @Column(name = "store_basket_count", nullable = false)
    private Integer storeBasketCount;

    /** Cặp sản phẩm phải được mua chung ít nhất ngần này hóa đơn. */
    @Column(name = "min_together", nullable = false)
    private Integer minTogether;

    @Column(name = "rule_count", nullable = false)
    private Integer ruleCount;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    public AssociationRun() {}

    public Integer getId() { return id; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public Integer getBasketCount() { return basketCount; }
    public Integer getStoreBasketCount() { return storeBasketCount; }
    public Integer getMinTogether() { return minTogether; }
    public Integer getRuleCount() { return ruleCount; }
    public String getRequestedBy() { return requestedBy; }
}
