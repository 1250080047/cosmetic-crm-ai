package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Goods receipts by lot and expiry date, expiry / low-stock alerts and disposal (managers and admins: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/stock")
public class AdminStockController {
    private final InventoryService inventory;

    public AdminStockController(InventoryService inventory) { this.inventory = inventory; }

    @GetMapping("/overview") public Map<String, Object> overview() { return inventory.overview(); }

    @GetMapping("/lots") public List<Map<String, Object>> lots(@RequestParam(defaultValue = "") String search) { return inventory.lots(search); }

    @GetMapping("/receipts") public List<Map<String, Object>> receipts() { return inventory.receipts(); }

    @PostMapping("/receipts")
    public Map<String, Object> receive(@RequestBody InventoryService.Receipt receipt, @AuthenticationPrincipal StoreUser user) {
        return inventory.receive(receipt, user == null || user.getId() <= 0 ? null : user.getId());
    }

    public record DisposeRequest(String reason) {}

    @PostMapping("/lots/{id}/dispose")
    public Map<String, Object> dispose(@PathVariable int id, @RequestBody DisposeRequest request, @AuthenticationPrincipal StoreUser user) {
        return inventory.dispose(id, request == null ? null : request.reason(), user == null || user.getId() <= 0 ? null : user.getId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> refused(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
    }
}
