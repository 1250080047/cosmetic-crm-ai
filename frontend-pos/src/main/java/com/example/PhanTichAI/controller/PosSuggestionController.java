package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.service.ProductSuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Suggestions shown to the cashier while the cart is being filled (staff only, see SecurityConfig: /api/pos/**). */
@RestController
public class PosSuggestionController {
    private final ProductSuggestionService suggestions;

    public PosSuggestionController(ProductSuggestionService suggestions) { this.suggestions = suggestions; }

    @GetMapping("/api/pos/suggestions")
    public List<Map<String, Object>> suggestions(@RequestParam(required = false) List<Integer> ids) {
        return suggestions.forCart(ids);
    }
}
