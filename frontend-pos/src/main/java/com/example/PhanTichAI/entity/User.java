package com.example.PhanTichAI.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Bảng users: tài khoản nhân viên đăng nhập trang quản trị và quầy POS. Mật khẩu chỉ lưu dạng băm (BCrypt). */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** Khóa ngoại tới roles.id. */
    @Column(name = "role_id", nullable = false)
    private Integer roleId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** SQL Server tự ghi (DEFAULT SYSDATETIME()). */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public User() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Integer getRoleId() { return roleId; }
    public void setRoleId(Integer roleId) { this.roleId = roleId; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
