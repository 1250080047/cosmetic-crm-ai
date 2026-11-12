package com.example.PhanTichAI.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import java.util.*;

@Service
public class AccountService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public AccountService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @Override public StoreUser loadUserByUsername(String username) {
        var users = jdbc.query("""
                SELECT u.id,u.username,u.password_hash,u.full_name,u.is_active,r.name
                FROM users u JOIN roles r ON r.id=u.role_id WHERE u.username=?
                """, (rs, n) -> new StoreUser(rs.getInt("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("full_name"),
                rs.getString("name").toUpperCase(Locale.ROOT), rs.getBoolean("is_active")), username);
        if (users.isEmpty() || !Set.of("ADMIN", "MANAGER", "CASHIER").contains(users.get(0).getRole()))
            throw new UsernameNotFoundException("Tài khoản không hợp lệ");
        return users.get(0);
    }
    public boolean needsSetup() { return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class) == 0; }
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT u.id,u.username,u.full_name AS fullName,u.is_active AS active,
                UPPER(r.name) AS role,u.created_at AS createdAt
                FROM users u LEFT JOIN roles r ON r.id=u.role_id ORDER BY u.id
                """);
    }
    public record AccountInput(String username, String fullName, String password, String role, Boolean active) {
        @Override public String toString() { return "AccountInput[username=" + username + ",role=" + role + ",active=" + active + ",password=REDACTED]"; }
    }

    @Transactional public void setup(AccountInput input) {
        lockAccounts();
        if (!needsSetup()) throw new ResponseStatusException(CONFLICT, "Cửa hàng đã có tài khoản.");
        for (String role : List.of("ADMIN", "MANAGER", "CASHIER")) roleId(role);
        save(null, new AccountInput(input.username(), input.fullName(), input.password(), "ADMIN", true), null);
    }

    @Transactional public void save(Integer id, AccountInput input, StoreUser actor) {
        lockAccounts();
        if (input.fullName() == null || input.fullName().isBlank() || input.fullName().length() > 100)
            throw new ResponseStatusException(BAD_REQUEST, "Họ tên cần từ 1 đến 100 ký tự.");
        if (input.role() == null || !Set.of("ADMIN", "MANAGER", "CASHIER").contains(input.role()))
            throw new ResponseStatusException(BAD_REQUEST, "Vai trò không hợp lệ.");
        boolean active = Boolean.TRUE.equals(input.active());
        boolean passwordProvided = input.password() != null && !input.password().isEmpty();
        if ((id == null || passwordProvided) && (input.password() == null || input.password().length() < 10
                || input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72))
            throw new ResponseStatusException(BAD_REQUEST, "Mật khẩu cần ít nhất 10 ký tự, tối đa 72 byte UTF-8.");
        int roleId = roleId(input.role());
        if (id == null) {
            if (input.username() == null || !input.username().matches("[A-Za-z0-9_.-]{3,50}"))
                throw new ResponseStatusException(BAD_REQUEST, "Tên đăng nhập cần 3–50 ký tự chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, input.username()) > 0)
                throw new ResponseStatusException(CONFLICT, "Tên đăng nhập đã tồn tại.");
            jdbc.update("INSERT INTO users(username,password_hash,full_name,role_id,is_active) VALUES(?,?,?,?,?)",
                    input.username(), encoder.encode(input.password()), input.fullName().trim(), roleId, active);
        } else {
            var old = jdbc.queryForList("SELECT u.is_active,r.name FROM users u LEFT JOIN roles r ON r.id=u.role_id WHERE u.id=?", id);
            if (old.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy tài khoản.");
            if (actor != null && actor.getId() == id && (!active || !"ADMIN".equals(input.role())))
                throw new ResponseStatusException(BAD_REQUEST, "Không thể tự khóa hoặc hạ quyền tài khoản đang đăng nhập.");
            boolean wasAdmin = Boolean.TRUE.equals(old.get(0).get("is_active")) && "ADMIN".equalsIgnoreCase(String.valueOf(old.get(0).get("name")));
            if (wasAdmin && (!active || !"ADMIN".equals(input.role())) &&
                    jdbc.queryForObject("SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE UPPER(r.name)='ADMIN' AND u.is_active=1", Integer.class) <= 1)
                throw new ResponseStatusException(BAD_REQUEST, "Cần giữ ít nhất một quản trị viên đang hoạt động.");
            jdbc.update("UPDATE users SET full_name=?,role_id=?,is_active=? WHERE id=?", input.fullName().trim(), roleId, active, id);
            if (passwordProvided) jdbc.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(input.password()), id);
        }
    }
    private void lockAccounts() { jdbc.queryForObject("SELECT COUNT(*) FROM users WITH (UPDLOCK,HOLDLOCK)", Integer.class); }
    private int roleId(String role) {
        var ids = jdbc.queryForList("SELECT id FROM roles WITH (UPDLOCK,HOLDLOCK) WHERE UPPER(name)=?", Integer.class, role);
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO roles(name,description) VALUES(?,?)", role,
                    Map.of("ADMIN", "Quản trị hệ thống", "MANAGER", "Quản lý kinh doanh", "CASHIER", "Thu ngân").get(role));
            return jdbc.queryForObject("SELECT id FROM roles WHERE UPPER(name)=?", Integer.class, role);
        }
        return ids.get(0);
    }
}
