package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Bảng member_accounts: tài khoản để khách tự đăng nhập trang thẻ thành viên (/member) bằng số điện thoại + PIN 6 số.
 * Mỗi khách có tối đa một tài khoản (khóa chính = customer_id). PIN và mã kích hoạt chỉ lưu dạng băm BCrypt.
 */
@Entity
@Table(name = "member_accounts")
public class MemberAccount {

    @Id
    @Column(name = "customer_id")
    private Integer customerId;

    /** NULL = thẻ chưa kích hoạt (khách có trong hệ thống nhưng chưa đặt PIN). */
    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    /** Mã 6 số do thu ngân cấp để khách cũ nhận thẻ hoặc đặt lại PIN; dùng một lần. */
    @Column(name = "activation_code_hash", length = 100)
    private String activationCodeHash;

    @Column(name = "activation_expires_at")
    private LocalDateTime activationExpiresAt;

    /** Số lần nhập sai PIN / mã kích hoạt liên tiếp. */
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    /** Khóa đăng nhập tạm thời sau nhiều lần nhập sai PIN. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    public MemberAccount() {}

    public MemberAccount(Integer customerId, LocalDateTime createdAt) {
        this.customerId = customerId;
        this.createdAt = createdAt;
    }

    public boolean isActivated() { return pinHash != null; }

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public String getActivationCodeHash() { return activationCodeHash; }
    public void setActivationCodeHash(String activationCodeHash) { this.activationCodeHash = activationCodeHash; }

    public LocalDateTime getActivationExpiresAt() { return activationExpiresAt; }
    public void setActivationExpiresAt(LocalDateTime activationExpiresAt) { this.activationExpiresAt = activationExpiresAt; }

    public Integer getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(Integer failedAttempts) { this.failedAttempts = failedAttempts; }

    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
