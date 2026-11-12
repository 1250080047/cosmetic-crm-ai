package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.service.RevenueService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/revenue")
public class RevenueController {
    private final RevenueService revenue;
    public RevenueController(RevenueService revenue) { this.revenue = revenue; }
    @GetMapping("/options") public Map<String,Object> options() { return revenue.options(); }
    @GetMapping public Map<String,Object> report(@RequestParam(defaultValue="month") String granularity,
            @RequestParam int year, @RequestParam(defaultValue="1") int month, @RequestParam(defaultValue="year") String comparison) {
        return revenue.report(granularity,year,month,comparison);
    }
}
