package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }
    @GetMapping("/api/auth/me") public Map<String, Object> me(@AuthenticationPrincipal StoreUser user) {
        return Map.of("id", user.getId(), "username", user.getUsername(), "fullName", user.getFullName(), "role", user.getRole());
    }
    @GetMapping("/api/admin/users") public List<Map<String, Object>> users() { return accounts.list(); }
    @PostMapping("/api/admin/users") public Map<String, Boolean> create(@RequestBody AccountService.AccountInput input, @AuthenticationPrincipal StoreUser actor) {
        accounts.save(null, input, actor); return Map.of("success", true);
    }
    @PutMapping("/api/admin/users/{id}") public Map<String, Boolean> update(@PathVariable int id, @RequestBody AccountService.AccountInput input, @AuthenticationPrincipal StoreUser actor) {
        accounts.save(id, input, actor); return Map.of("success", true);
    }
}
