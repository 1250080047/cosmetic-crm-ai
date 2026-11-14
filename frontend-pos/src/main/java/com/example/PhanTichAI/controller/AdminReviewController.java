package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Customers' product reviews with their sentiment, irritation alerts, hiding (managers and admins: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {
    private final ReviewService reviews;

    public AdminReviewController(ReviewService reviews) { this.reviews = reviews; }

    @GetMapping("/overview") public Map<String, Object> overview() { return reviews.overview(); }

    @GetMapping public List<Map<String, Object>> list(@RequestParam(defaultValue = "all") String filter) { return reviews.list(filter); }

    public record StatusRequest(String status) {}

    @PostMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable int id, @RequestBody StatusRequest request, @AuthenticationPrincipal StoreUser user) {
        return reviews.setStatus(id, request == null ? null : request.status(), user == null || user.getId() <= 0 ? null : user.getId());
    }

    @PostMapping("/analyze-pending") public Map<String, Object> analyzePending() { return reviews.analyzePending(); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> refused(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
    }
}
