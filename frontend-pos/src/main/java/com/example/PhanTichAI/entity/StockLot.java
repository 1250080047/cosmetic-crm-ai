package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bảng stock_lots: lô hàng với hạn sử dụng. Tổng quantity_remaining của các lô = products.stock_quantity.
 * Khi bán, InventoryService lấy lô còn hạn có hạn gần nhất trước (FEFO).
 */
@Entity
@Table(name = "stock_lots")
public class StockLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "product_id", nullable = false)
    private Integer productId;

    /** stock_receipts.id; NULL với lô tồn đầu kỳ. */
    @Column(name = "receipt_id")
    private Integer receiptId;

    @Column(name = "lot_no", nullable = false, length = 50)
    private String lotNo;

    /** NULL: không ghi hạn dùng. */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "quantity_received", nullable = false)
    private Integer quantityReceived;

    @Column(name = "quantity_remaining", nullable = false)
    private Integer quantityRemaining;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    /** true: hạn dùng giả lập để minh họa (lô tồn đầu kỳ DK-…). */
    @Column(name = "is_demo", nullable = false)
    private Boolean demo = false;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** Số lượng đã xuất hủy (hết hạn, hư hỏng). */
    @Column(name = "disposed_quantity", nullable = false)
    private Integer disposedQuantity = 0;

    @Column(name = "disposed_at")
    private LocalDateTime disposedAt;

    @Column(name = "disposed_by")
    private Integer disposedBy;

    @Column(name = "dispose_reason", length = 200)
    private String disposeReason;

    public StockLot() {}

    public Integer getId() { return id; }
    public Integer getProductId() { return productId; }
    public Integer getReceiptId() { return receiptId; }
    public String getLotNo() { return lotNo; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Integer getQuantityReceived() { return quantityReceived; }
    public Integer getQuantityRemaining() { return quantityRemaining; }
    public BigDecimal getUnitCost() { return unitCost; }
    public Boolean getDemo() { return demo; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public Integer getDisposedQuantity() { return disposedQuantity; }
    public LocalDateTime getDisposedAt() { return disposedAt; }
    public Integer getDisposedBy() { return disposedBy; }
    public String getDisposeReason() { return disposeReason; }
}
