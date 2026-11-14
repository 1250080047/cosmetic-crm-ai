package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.dto.CheckoutRequest;
import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.entity.Product;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.repository.ProductRepository;
import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.CustomerOfferService;
import com.example.PhanTichAI.service.CustomerRegistrationService;
import com.example.PhanTichAI.service.LoyaltyService;
import com.example.PhanTichAI.service.MemberLinkService;
import com.example.PhanTichAI.service.MemberQrService;
import com.example.PhanTichAI.service.MemberService;
import com.example.PhanTichAI.service.PosCheckoutService;
import com.example.PhanTichAI.service.ShiftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/pos")
public class PosApiController {
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CustomerOfferService customerOfferService;
    private final PosCheckoutService posCheckoutService;
    private final ShiftService shifts;
    private final JdbcTemplate jdbc;
    private final CustomerRegistrationService registration;
    private final MemberService members;
    private final MemberQrService memberQr;
    private final LoyaltyService loyalty;
    private final MemberLinkService memberLinks;

    public PosApiController(CustomerRepository customerRepository, ProductRepository productRepository,
                            CustomerOfferService customerOfferService, PosCheckoutService posCheckoutService,
                            ShiftService shifts, JdbcTemplate jdbc, CustomerRegistrationService registration,
                            MemberService members, MemberQrService memberQr, LoyaltyService loyalty, MemberLinkService memberLinks) {
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.customerOfferService = customerOfferService;
        this.posCheckoutService = posCheckoutService;
        this.shifts = shifts;
        this.jdbc = jdbc;
        this.registration = registration;
        this.members = members;
        this.memberQr = memberQr;
        this.loyalty = loyalty;
        this.memberLinks = memberLinks;
    }

    @GetMapping("/products")
    public List<Map<String, Object>> getAllProducts() {
        return productRepository.findByIsActiveTrueOrderByIdAsc().stream().map(this::publicProduct).toList();
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                SELECT c.id,c.name,COUNT(p.id) AS products FROM categories c
                JOIN products p ON p.category_id=c.id AND p.is_active=1 GROUP BY c.id,c.name ORDER BY c.id
                """);
    }

    @GetMapping("/product-scan")
    public ResponseEntity<?> scanBarcode(@RequestParam String barcode) {
        return productRepository.findByBarcode(barcode)
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(publicProduct(p)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Không tìm thấy sản phẩm")));
    }

    /**
     * Find a member by phone number, by customer code (KH00012) or by the QR code of the member card
     * (changes every 60 seconds); returns the approved offer of their group, points and card status.
     */
    /**
     * A member card scanned at this counter (the rotating QR code on the customer's own phone proves the customer is
     * here): their points may be used on an invoice for 15 minutes. Typing a phone number is not enough to spend points.
     */
    static final String VERIFIED_MEMBER = "POS_VERIFIED_MEMBER";
    static final java.time.Duration VERIFIED_FOR = java.time.Duration.ofMinutes(15);
    record VerifiedMember(int customerId, java.time.Instant at) implements java.io.Serializable {}

    static boolean verified(jakarta.servlet.http.HttpSession session, Integer customerId) {
        Object value = session == null ? null : session.getAttribute(VERIFIED_MEMBER);
        return value instanceof VerifiedMember member && customerId != null && member.customerId() == customerId
                && member.at().plus(VERIFIED_FOR).isAfter(java.time.Instant.now());
    }

    @GetMapping("/customer-lookup")
    public ResponseEntity<?> lookupCustomer(@RequestParam(required = false) String phone, @RequestParam(required = false) String q,
                                            jakarta.servlet.http.HttpSession session) {
        String key = (q != null && !q.isBlank() ? q : phone == null ? "" : phone).trim();
        if (key.isEmpty() || key.length() > 64)
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Nhập số điện thoại hoặc mã khách hàng."));
        boolean scanned = MemberQrService.looksLikeToken(key);
        if (scanned) {
            MemberQrService.Check check = memberQr.verify(key);
            if (check.status() != MemberQrService.Status.VALID)
                return ResponseEntity.ok(Map.of("found", false, "reason", "qr", "message", check.message()));
            key = check.customerCode();
        }
        Customer customer = (key.toUpperCase(Locale.ROOT).matches("KH\\d{1,10}")
                ? customerRepository.findByCustomerCodeIgnoreCase(key)
                : customerRepository.findByPhone(key)).orElse(null);
        if (customer == null) {
            return ResponseEntity.ok(Map.of("found", false, "message", "Khách hàng chưa có trong hệ thống"));
        }
        CustomerOfferService.Lrfm lrfm = customerOfferService.measure(customer);
        CustomerOfferService.Offer offer = customerOfferService.forCustomer(customer);
        Map<String, Object> res = new HashMap<>();
        res.put("found", true);
        res.put("customer_id", customer.getCustomerId());
        res.put("customer_code", customer.getCustomerCode());
        res.put("name", customer.getFullName() == null || customer.getFullName().isBlank() ? "Khách " + customer.getCustomerCode() : customer.getFullName());
        res.put("phone", customer.getPhone());
        res.put("cluster_id", offer.clusterId());
        res.put("segment", offer.segment());
        res.put("color", offer.color());
        res.put("discount_percent", offer.discountPercent());
        res.put("gift", offer.giftDescription());
        res.put("script", offer.cashierScript());
        res.put("offer_available", offer.strategyId() != null);
        res.put("ai_available", offer.aiAvailable());
        res.put("gift_quantity_limit", offer.giftQuantityLimit());
        res.put("gift_options", offer.giftProducts().stream().map(product -> Map.of(
                "product_id", product.getId(), "name", product.getProductName(),
                "stock_quantity", product.getStockQuantity(), "unit_price", product.getUnitPrice()
        )).toList());
        res.put("has_purchase", lrfm.hasPurchase());
        res.put("length_days", (int) lrfm.length());
        res.put("recency_days", (int) lrfm.recency());
        res.put("orders_12m", (int) lrfm.frequency());
        res.put("spent_12m", BigDecimal.valueOf(lrfm.monetary()).setScale(0, java.math.RoundingMode.HALF_UP));
        res.put("total_orders", customer.getTotalOrders());
        res.put("total_spent", customer.getTotalSpent());
        res.put("last_purchase", customer.getLastPurchase());
        int balance = loyalty.balance(customer.getCustomerId());
        String memberStatus = members.status(customer.getCustomerId());
        res.putAll(loyalty.tier(customer.getCustomerId(), java.time.LocalDateTime.now()).describe());
        res.put("points", balance);
        res.put("points_value", LoyaltyService.REDEEM_STEP_VALUE.multiply(BigDecimal.valueOf(balance / LoyaltyService.REDEEM_STEP)));
        res.put("member_status", memberStatus);
        res.put("scanned", scanned);
        boolean present = scanned && "ACTIVE".equals(memberStatus);
        if (present) session.setAttribute(VERIFIED_MEMBER, new VerifiedMember(customer.getCustomerId(), java.time.Instant.now()));
        res.put("can_redeem", (present || verified(session, customer.getCustomerId())) && balance >= LoyaltyService.REDEEM_STEP);
        res.put("redeem_step", LoyaltyService.REDEEM_STEP);
        res.put("redeem_step_value", LoyaltyService.REDEEM_STEP_VALUE);
        res.put("redeem_max_share", LoyaltyService.REDEEM_MAX_SHARE);
        return ResponseEntity.ok(res);
    }

    /** One-time code (valid 10 minutes) the cashier reads to the customer so they can claim the card or set a new PIN. */
    @PostMapping("/customers/{customerId}/activation-code")
    public ResponseEntity<?> activationCode(@PathVariable int customerId, HttpServletRequest request) {
        try {
            MemberService.IssuedCode issued = members.issueActivationCode(customerId);
            return ResponseEntity.ok(Map.of("success", true, "code", issued.code(), "expires_at", issued.expiresAt(),
                    "minutes", MemberService.ACTIVATION_VALID.toMinutes(), "resets_pin", issued.resetsPin(),
                    "member_url", memberLinks.link(request).url()));
        } catch (MemberService.MemberException error) {
            return ResponseEntity.status(error.status()).body(Map.of("success", false, "message", error.getMessage()));
        }
    }

    /** Address of the member page for the customer's phone (shown as a QR code at the counter). */
    @GetMapping("/member-link")
    public Map<String, Object> memberLink(HttpServletRequest request) {
        MemberLinkService.Link link = memberLinks.link(request);
        return Map.of("url", link.url(), "alternatives", link.alternatives(), "reachable_from_phones", link.reachableFromPhones(),
                "scope", link.scope());
    }

    public static class CreateCustomerRequest {
        private String phone;
        private String fullName;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
    }

    @PostMapping("/customers")
    @Transactional
    public ResponseEntity<?> createCustomer(@RequestBody CreateCustomerRequest req) {
        if (req.getPhone() == null || !req.getPhone().trim().matches("\\+?[0-9]{8,15}") ||
                (req.getFullName() != null && req.getFullName().trim().length() > 100)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Số điện thoại cần 8–15 chữ số; họ tên tối đa 100 ký tự."));
        }
        if (customerRepository.findByPhone(req.getPhone().trim()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Số điện thoại này đã tồn tại trong hệ thống!"));
        }
        Customer c = registration.register(req.getPhone(), req.getFullName());
        return ResponseEntity.ok(Map.of(
                "success", true, "message", "Đã tạo thành viên mới thành công!",
                "customer_id", c.getCustomerId(), "customer_code", c.getCustomerCode(), "name", c.getFullName(), "phone", c.getPhone()));
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest req, @AuthenticationPrincipal StoreUser user,
                                      jakarta.servlet.http.HttpSession session) {
        boolean usesPoints = req.getRedeemPoints() != null && req.getRedeemPoints() != 0;
        if (usesPoints && !verified(session, req.getCustomerId()))
            return ResponseEntity.status(403).body(Map.of("success", false, "message",
                    "Muốn dùng điểm, hãy bấm 📷 quét mã QR trên điện thoại của khách (có hiệu lực 15 phút) rồi thanh toán lại."));
        try {
            Map<String, Object> result = posCheckoutService.checkout(req, user.getId());
            if (usesPoints) session.removeAttribute(VERIFIED_MEMBER);   // one scan, one invoice with points
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
        } catch (Exception error) {
            System.err.println("Checkout failed: " + error.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "Chưa hoàn tất thanh toán. Vui lòng kiểm tra kết nối và thử lại."));
        }
    }

    // ---- Shifts -------------------------------------------------------------------------------
    public record OpenShiftRequest(BigDecimal openingCash) {}
    public record CloseShiftRequest(BigDecimal countedCash, String note) {}

    @GetMapping("/shift")
    public Map<String, Object> currentShift(@AuthenticationPrincipal StoreUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("open", shifts.current(user.getId()));
        return result;
    }

    @PostMapping("/shift/open")
    public ResponseEntity<?> openShift(@RequestBody(required = false) OpenShiftRequest req, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.open(user.getId(), req == null ? null : req.openingCash()));
    }

    @PostMapping("/shift/close")
    public ResponseEntity<?> closeShift(@RequestBody(required = false) CloseShiftRequest req, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.close(null, user.getId(), false, req == null ? null : req.countedCash(), req == null ? null : req.note()));
    }

    @GetMapping("/shift/{id}/report")
    public ResponseEntity<?> shiftReport(@PathVariable int id, @AuthenticationPrincipal StoreUser user) {
        return handle(() -> shifts.reportFor(id, user.getId(), !"CASHIER".equals(user.getRole())));
    }

    private ResponseEntity<?> handle(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>(action.get());
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
        } catch (ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("success", false, "message", String.valueOf(error.getReason())));
        }
    }

    private Map<String, Object> publicProduct(Product p) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", p.getId()); result.put("barcode", p.getBarcode()); result.put("productName", p.getProductName());
        result.put("categoryId", p.getCategoryId()); result.put("unitPrice", p.getUnitPrice());
        result.put("stockQuantity", p.getStockQuantity()); result.put("imageUrl", p.getImageUrl()); result.put("isActive", p.getIsActive());
        return result;
    }
}
