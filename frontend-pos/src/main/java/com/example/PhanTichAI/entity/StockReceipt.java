package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Bảng stock_receipts: phiếu nhập hàng (mỗi dòng hàng của phiếu là một lô trong stock_lots). Ghi bởi InventoryService. */
@Entity
@Table(name = "stock_receipts")
public class StockReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Số phiếu PNyyMMddHHmmss + 4 số. */
    @Column(name = "receipt_no", nullable = false, unique = true, length = 30)
    private String receiptNo;

    @Column(name = "supplier", length = 150)
    private String supplier;

    @Column(name = "note", length = 300)
    private String note;

    /** users.id của người lập phiếu. */
    @Column(name = "created_by")
    private Integer createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public StockReceipt() {}

    public Integer getId() { return id; }
    public String getReceiptNo() { return receiptNo; }
    public String getSupplier() { return supplier; }
    public String getNote() { return note; }
    public Integer getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
