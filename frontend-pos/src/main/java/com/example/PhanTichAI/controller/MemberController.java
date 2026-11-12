package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.service.MemberQrService;
import com.example.PhanTichAI.service.MemberService;
import com.example.PhanTichAI.service.MemberService.MemberException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-service member card for customers (opened on their phone): register, activate, sign in, show the
 * card with a QR code that changes every 60 seconds, points and purchases. Customers never get staff rights:
 * the member session only holds the customer id and is checked here (see SecurityConfig.memberSecurity).
 */
@Controller
public class MemberController {
    public static final String SESSION_KEY = "MEMBER_CUSTOMER_ID";
    private final MemberService members;
    private final MemberQrService qr;
    private final CustomerRepository customers;

    public MemberController(MemberService members, MemberQrService qr, CustomerRepository customers) {
        this.members = members; this.qr = qr; this.customers = customers;
    }

    public record RegisterRequest(String fullName, String phone, String pin) {}
    public record LoginRequest(String phone, String pin) {}
    public record ActivateRequest(String phone, String code, String pin) {}

    @GetMapping({"/member", "/member/"})
    public String page() { return "member"; }

    @PostMapping("/api/member/register") @ResponseBody
    public Map<String, Object> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        try {
            return signIn(request, members.register(body.fullName(), body.phone(), body.pin()), "Chào mừng bạn! Thẻ thành viên đã sẵn sàng.");
        } catch (DataIntegrityViolationException duplicate) {
            throw new MemberException(409, "Số điện thoại vừa được đăng ký. Hãy thử đăng nhập.");
        }
    }

    @PostMapping("/api/member/login") @ResponseBody
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return signIn(request, members.login(body.phone(), body.pin()), "Đăng nhập thành công.");
    }

    @PostMapping("/api/member/activate") @ResponseBody
    public Map<String, Object> activate(@RequestBody ActivateRequest body, HttpServletRequest request) {
        return signIn(request, members.activate(body.phone(), body.code(), body.pin()), "Đã kích hoạt thẻ và lưu PIN mới.");
    }

    @PostMapping("/api/member/logout") @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        session.removeAttribute(SESSION_KEY);   // a staff login in the same browser is kept
        return Map.of("success", true);
    }

    @GetMapping("/api/member/me") @ResponseBody
    public Map<String, Object> me(HttpSession session) {
        return members.card(member(session));
    }

    /** A fresh QR code; the page asks again when the current one is about to change. */
    @GetMapping("/api/member/qr") @ResponseBody
    public Map<String, Object> qrCode(HttpSession session) {
        Customer customer = customers.findById(member(session)).orElseThrow(() -> new MemberException(401, "Phiên đăng nhập đã hết hạn."));
        MemberQrService.Token token = qr.issue(customer.getCustomerCode());
        return Map.of("token", token.value(), "seconds_left", token.secondsLeft(), "step_seconds", MemberQrService.STEP_SECONDS);
    }

    @ExceptionHandler(MemberException.class)
    public ResponseEntity<Map<String, Object>> refused(MemberException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "message", error.getMessage()));
    }

    private static int member(HttpSession session) {
        Object id = session == null ? null : session.getAttribute(SESSION_KEY);
        if (!(id instanceof Integer customerId)) throw new MemberException(401, "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.");
        return customerId;
    }

    private Map<String, Object> signIn(HttpServletRequest request, Customer customer, String message) {
        request.getSession(true);
        request.changeSessionId();              // a new session id after sign-in (no session fixation)
        request.getSession().setAttribute(SESSION_KEY, customer.getCustomerId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        result.put("card", members.card(customer.getCustomerId()));
        return result;
    }
}
