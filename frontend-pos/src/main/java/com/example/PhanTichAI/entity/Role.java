package com.example.PhanTichAI.entity;

import jakarta.persistence.*;

/** Bảng roles: các vai trò ADMIN, MANAGER, CASHIER (tạo sẵn trong schema.sql). */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "name", nullable = false, unique = true, length = 20)
    private String name;

    @Column(name = "description", length = 100)
    private String description;

    public Role() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
