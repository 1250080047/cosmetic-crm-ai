package com.example.PhanTichAI.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Demo accounts for the thesis presentation (password 123456). Created only when missing, never reset.
 * Accounts created later from the admin page still follow the 10-character password rule.
 * Disable with app.demo-accounts.enabled=false before any real deployment.
 */
@Component
@ConditionalOnProperty(name = "app.demo-accounts.enabled", havingValue = "true")
public class DemoAccountSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);
    private record Demo(String username, String fullName, String role) {}
    private static final String DEMO_PASSWORD = "123456";
    private static final List<Demo> ACCOUNTS = List.of(
            new Demo("admin", "Quản trị viên", "ADMIN"),
            new Demo("manager", "Quản lý cửa hàng", "MANAGER"),
            new Demo("nhanvien1", "Nhân viên 1", "CASHIER"),
            new Demo("nhanvien2", "Nhân viên 2", "CASHIER"),
            new Demo("nhanvien3", "Nhân viên 3", "CASHIER"));
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public DemoAccountSeeder(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Demo demo : ACCOUNTS) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, demo.username()) > 0) continue;
            var roles = jdbc.queryForList("SELECT id FROM roles WHERE UPPER(name)=?", Integer.class, demo.role());
            if (roles.isEmpty()) continue;
            jdbc.update("INSERT INTO users(username,password_hash,full_name,role_id,is_active) VALUES(?,?,?,?,1)",
                    demo.username(), encoder.encode(DEMO_PASSWORD), demo.fullName(), roles.get(0));
            created++;
        }
        if (created > 0) log.info("Đã tạo {} tài khoản demo (mật khẩu 123456).", created);
    }
}
