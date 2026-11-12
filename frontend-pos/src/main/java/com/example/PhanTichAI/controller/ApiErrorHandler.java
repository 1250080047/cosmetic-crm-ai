package com.example.PhanTichAI.controller;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice(annotations = RestController.class)
public class ApiErrorHandler {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("success", false, "message", ex.getReason() == null ? "Yêu cầu không hợp lệ." : ex.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", "Dữ liệu trùng hoặc không phù hợp với ràng buộc cơ sở dữ liệu."));
    }
}
