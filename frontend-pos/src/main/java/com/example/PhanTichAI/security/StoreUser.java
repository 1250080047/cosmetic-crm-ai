package com.example.PhanTichAI.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;

public class StoreUser extends User {
    private final int id;
    private final String fullName;
    private final String role;
    private final String passwordVersion;

    public StoreUser(int id, String username, String password, String fullName, String role, boolean active) {
        super(username, password, active, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.id = id;
        this.fullName = fullName;
        this.role = role;
        try {
            this.passwordVersion = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public int getId() { return id; }
    public String getFullName() { return fullName; }
    public String getRole() { return role; }
    public String getPasswordVersion() { return passwordVersion; }
}
