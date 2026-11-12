package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Bảng shifts: ca làm việc của thu ngân (mở ca, kết ca, tiền mặt đầu ca và cuối ca). */
@Entity
@Table(name = "shifts")
public class Shift {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Khóa ngoại tới users.id: thu ngân đứng ca. */
    @Column(name = "cashier_id", nullable = false)
    private Integer cashierId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "opening_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingCash = BigDecimal.ZERO;

    @Column(name = "counted_cash", precision = 14, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "note", length = 500)
    private String note;

    /** Khóa ngoại tới users.id: người kết ca (có thể là quản lý). */
    @Column(name = "closed_by")
    private Integer closedBy;

    /** OPEN hoặc CLOSED; mỗi thu ngân chỉ có tối đa một ca OPEN. */
    @Column(name = "status", nullable = false, length = 10)
    private String status = OPEN;

    public Shift() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getCashierId() { return cashierId; }
    public void setCashierId(Integer cashierId) { this.cashierId = cashierId; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public BigDecimal getOpeningCash() { return openingCash; }
    public void setOpeningCash(BigDecimal openingCash) { this.openingCash = openingCash; }

    public BigDecimal getCountedCash() { return countedCash; }
    public void setCountedCash(BigDecimal countedCash) { this.countedCash = countedCash; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Integer getClosedBy() { return closedBy; }
    public void setClosedBy(Integer closedBy) { this.closedBy = closedBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
