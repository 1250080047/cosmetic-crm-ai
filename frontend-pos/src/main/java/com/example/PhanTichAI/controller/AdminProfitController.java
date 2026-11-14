package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.service.ProfitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Gross profit of the counter invoices (managers and admins: /api/admin/**). */
@RestController
public class AdminProfitController {
    private final ProfitService profit;

    public AdminProfitController(ProfitService profit) { this.profit = profit; }

    @GetMapping("/api/admin/profit")
    public Map<String, Object> report() { return profit.report(); }
}
