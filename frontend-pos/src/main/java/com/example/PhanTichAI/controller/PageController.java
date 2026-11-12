package com.example.PhanTichAI.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import jakarta.servlet.http.HttpServletRequest;
import com.example.PhanTichAI.security.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class PageController {
    private final AccountService accounts;
    public PageController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal StoreUser user) {
        return "redirect:" + ("CASHIER".equals(user.getRole()) ? "/pos" : "/admin");
    }

    @GetMapping("/login") public String login(Model model) {
        model.addAttribute("needsSetup", accounts.needsSetup());
        return "login";
    }
    private void requireSetup(HttpServletRequest request) {
        if (!java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr()) || !accounts.needsSetup())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ khởi tạo tại máy chủ khi chưa có tài khoản.");
    }
    @GetMapping("/setup") public String setup(HttpServletRequest request) { requireSetup(request); return "setup"; }
    @PostMapping("/setup") public String setup(HttpServletRequest request, @RequestParam String username,
            @RequestParam String fullName, @RequestParam String password, Model model) {
        requireSetup(request);
        try { accounts.setup(new AccountService.AccountInput(username, fullName, password, "ADMIN", true)); }
        catch (ResponseStatusException ex) { model.addAttribute("setupError", ex.getReason()); return "setup"; }
        return "redirect:/login?created";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin";
    }

    @GetMapping("/pos")
    public String pos() {
        return "pos";
    }
}
