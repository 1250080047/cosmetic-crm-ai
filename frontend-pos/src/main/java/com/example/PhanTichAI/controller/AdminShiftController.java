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
import java.util.Map;
import java.util.function.Supplier;

/**
 * Managers and admins (/api/admin/**) review every cashier's shifts, may close a shift a cashier forgot to close, and
 * confirm how a cash difference beyond ±10.000 đ is settled (after fixing a payment method entered by mistake, if any).
 */
@RestController
@RequestMapping("/api/admin/shifts")
public class AdminShiftController {
    private final ShiftService shifts;
    private final JdbcTemplate jdbc;

    public AdminShiftController(ShiftService shifts, JdbcTemplate jdbc) { this.shifts = shifts; this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Integer cashierId, @RequestParam(required = false) String review) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", shifts.list(from, to, cashierId, review));
        result.put("cashiers", jdbc.queryForList("""
                SELECT DISTINCT u.id,u.username,u.full_name AS fullName FROM users u JOIN shifts s ON s.cashier_id=u.id ORDER BY u.full_name
                """));
        result.put("discrepancies", shifts.discrepancies(from, to));
        result.put("pending", jdbc.queryForObject("SELECT COUNT(*) FROM shifts WHERE review_status='PENDING'", Integer.class));
        result.put("tolerance", ShiftService.TOLERANCE);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> report(@PathVariable int id) { return shifts.report(id); }

    public record CloseRequest(BigDecimal countedCash, String note) {}

    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable int id, @RequestBody(required = false) CloseRequest req, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.close(id, user.getId(), true, req == null ? null : req.countedCash(), req == null ? null : req.note()));
    }

    public record ReviewRequest(String decision, String note) {}

    /** Confirms how the cash difference of a shift is settled (managers and admins). */
    @PostMapping("/{id}/review")
    public ResponseEntity<?> review(@PathVariable int id, @RequestBody ReviewRequest req, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.review(id, user.getId(), req == null ? null : req.decision(), req == null ? null : req.note()));
    }

    public record PaymentRequest(String method, String reason) {}

    /** Fixes the payment method of an invoice in a shift waiting for review; kept in order_payment_changes. */
    @PostMapping("/orders/{transactionNo}/payment-method")
    public ResponseEntity<?> payment(@PathVariable String transactionNo, @RequestBody PaymentRequest req, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.correctPayment(transactionNo, req == null ? null : req.method(), req == null ? null : req.reason(), user.getId()));
    }

    private ResponseEntity<?> handle(Supplier<Map<String, Object>> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>(action.get());
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
        } catch (ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("success", false, "message", String.valueOf(error.getReason())));
        }
    }
}
