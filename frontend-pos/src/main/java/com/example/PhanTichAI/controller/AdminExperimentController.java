package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.service.ExperimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Results of the offers measured against a control group, and a labelled simulation (managers and admins). */
@RestController
@RequestMapping("/api/admin/experiments")
public class AdminExperimentController {
    private final ExperimentService experiments;

    public AdminExperimentController(ExperimentService experiments) { this.experiments = experiments; }

    @GetMapping
    public Map<String, Object> results() {
        List<Map<String, Object>> list = experiments.results();
        return Map.of("experiments", list, "control_percent", ExperimentService.CONTROL_PERCENT, "window_days", ExperimentService.WINDOW_DAYS);
    }

    public record SimulationRequest(Double baseRate, Double uplift, Integer groupSize, Long seed) {}

    /** What a result could look like with assumed numbers. Nothing is stored; the answer says it is a simulation. */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody SimulationRequest request) {
        return ExperimentService.simulate(request.baseRate() == null ? 0.10 : request.baseRate(),
                request.uplift() == null ? 0.05 : request.uplift(), request.groupSize() == null ? 3000 : request.groupSize(),
                request.seed() == null ? System.nanoTime() : request.seed());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> refused(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
    }
}
