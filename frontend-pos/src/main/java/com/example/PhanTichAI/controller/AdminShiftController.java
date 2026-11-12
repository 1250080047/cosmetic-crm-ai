package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.ShiftService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Managers review every cashier's shifts and may close a shift a cashier forgot to close. */
@RestController
@RequestMapping("/api/admin/shifts")
public class AdminShiftController {
    private final ShiftService shifts;
    private final JdbcTemplate jdbc;

    public AdminShiftController(ShiftService shifts, JdbcTemplate jdbc) { this.shifts = shifts; this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Integer cashierId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", shifts.list(from, to, cashierId));
        result.put("cashiers", jdbc.queryForList("""
                SELECT DISTINCT u.id,u.username,u.full_name AS fullName FROM users u JOIN shifts s ON s.cashier_id=u.id ORDER BY u.full_name
                """));
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> report(@PathVariable int id) { return shifts.report(id); }

    public record CloseRequest(BigDecimal countedCash, String note) {}

    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable int id, @RequestBody(required = false) CloseRequest req, @AuthenticationPrincipal StoreUser user) {
        try {
            Map<String, Object> body = new LinkedHashMap<>(shifts.close(id, user.getId(), true,
                    req == null ? null : req.countedCash(), req == null ? null : req.note()));
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
        } catch (ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("success", false, "message", String.valueOf(error.getReason())));
        }
    }
}
