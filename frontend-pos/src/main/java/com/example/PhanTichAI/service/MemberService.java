package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.entity.LoyaltyPoint;
import com.example.PhanTichAI.entity.MemberAccount;
import com.example.PhanTichAI.repository.CustomerRepository;
import com.example.PhanTichAI.repository.MemberAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.*;

/**
 * Self-service member card: a customer registers (new phone number) or activates the card with a one-time code
 * from the cashier (phone number already known to the store), then signs in with phone number + 6-digit PIN.
 */
@Service
public class MemberService {
    public static final int MAX_FAILED = 5;
    public static final Duration LOCK = Duration.ofMinutes(15);
    public static final Duration ACTIVATION_VALID = Duration.ofMinutes(10);
    private static final String DEFAULT_OFFER_PREFIX = "Ưu đãi — ";

    private final MemberAccountRepository accounts;
    private final CustomerRepository customers;
    private final CustomerRegistrationService registration;
    private final LoyaltyService loyalty;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** A refusal the member page shows as is (HTTP status + Vietnamese message). */
    public static class MemberException extends RuntimeException {
        private final int status;
        public MemberException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
    public record IssuedCode(String code, LocalDateTime expiresAt, boolean resetsPin) {}

    @Autowired
    public MemberService(MemberAccountRepository accounts, CustomerRepository customers, CustomerRegistrationService registration,
                         LoyaltyService loyalty, PasswordEncoder encoder, JdbcTemplate jdbc) {
        this(accounts, customers, registration, loyalty, encoder, jdbc, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    public MemberService(MemberAccountRepository accounts, CustomerRepository customers, CustomerRegistrationService registration,
                         LoyaltyService loyalty, PasswordEncoder encoder, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts; this.customers = customers; this.registration = registration;
        this.loyalty = loyalty; this.encoder = encoder; this.jdbc = jdbc; this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    static void checkPin(String pin) {
        if (pin == null || !pin.matches("\\d{6}"))
            throw new MemberException(400, "PIN gồm đúng 6 chữ số.");
        boolean same = pin.chars().distinct().count() == 1;
        if (same || "0123456789".contains(pin) || "9876543210".contains(pin))
            throw new MemberException(400, "PIN quá dễ đoán (VD 123456, 111111). Hãy chọn 6 số khác.");
    }
    private static String phone(String value) {
        if (!CustomerRegistrationService.validPhone(value)) throw new MemberException(400, "Số điện thoại cần 8–15 chữ số.");
        return value.trim();
    }

    // ---- register / activate / sign in -----------------------------------------------------------------------
    @Transactional
    public Customer register(String fullName, String phoneNumber, String pin) {
        String phone = phone(phoneNumber);
        String name = fullName == null ? "" : fullName.trim();
        if (name.length() < 2 || name.length() > 100) throw new MemberException(400, "Họ tên cần từ 2 đến 100 ký tự.");
        checkPin(pin);
        Optional<Customer> existing = customers.findByPhone(phone);
        if (existing.isPresent()) {
            boolean active = accounts.findById(existing.get().getCustomerId()).map(MemberAccount::isActivated).orElse(false);
            throw new MemberException(409, active
                    ? "Số điện thoại này đã có thẻ thành viên. Hãy đăng nhập bằng PIN."
                    : "Số điện thoại này đã là khách của cửa hàng. Nhờ thu ngân cấp mã kích hoạt rồi chọn \"Kích hoạt thẻ\".");
        }
        Customer customer = registration.register(phone, name);
        MemberAccount account = new MemberAccount(customer.getCustomerId(), now());
        account.setPinHash(encoder.encode(pin));
        account.setLastLoginAt(now());
        accounts.save(account);
        return customer;
    }

    @Transactional(noRollbackFor = MemberException.class)
    public Customer login(String phoneNumber, String pin) {
        MemberException wrong = new MemberException(401, "Số điện thoại hoặc PIN chưa đúng.");
        if (!CustomerRegistrationService.validPhone(phoneNumber) || pin == null) throw wrong;
        Customer customer = customers.findByPhone(phoneNumber.trim()).orElseThrow(() -> wrong);
        MemberAccount account = accounts.findByIdForUpdate(customer.getCustomerId())
                .filter(MemberAccount::isActivated)
                .orElseThrow(() -> new MemberException(403, "Số điện thoại này chưa kích hoạt thẻ thành viên. Nhờ thu ngân cấp mã kích hoạt rồi chọn \"Kích hoạt thẻ\"."));
        LocalDateTime now = now();
        if (account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, account.getLockedUntil()).toMinutes() + 1);
            throw new MemberException(429, "Nhập sai PIN quá " + MAX_FAILED + " lần. Thử lại sau " + minutes + " phút, hoặc nhờ thu ngân cấp mã kích hoạt để đặt lại PIN.");
        }
        if (!encoder.matches(pin, account.getPinHash())) {
            int failed = account.getFailedAttempts() + 1;
            if (failed >= MAX_FAILED) { account.setLockedUntil(now.plus(LOCK)); failed = 0; }
            account.setFailedAttempts(failed);
            accounts.save(account);
            throw account.getLockedUntil() != null && account.getLockedUntil().isAfter(now)
                    ? new MemberException(429, "Nhập sai PIN quá " + MAX_FAILED + " lần. Tài khoản tạm khóa " + LOCK.toMinutes() + " phút.")
                    : wrong;
        }
        account.setFailedAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        accounts.save(account);
        return customer;
    }

    /** Existing customers get their card (or a new PIN) with the one-time code the cashier gave them at the counter. */
    @Transactional(noRollbackFor = MemberException.class)
    public Customer activate(String phoneNumber, String code, String pin) {
        MemberException wrong = new MemberException(400, "Mã kích hoạt không đúng hoặc đã hết hạn. Nhờ thu ngân cấp mã mới.");
        checkPin(pin);
        if (!CustomerRegistrationService.validPhone(phoneNumber) || code == null || !code.trim().matches("\\d{6}")) throw wrong;
        Customer customer = customers.findByPhone(phoneNumber.trim()).orElseThrow(() -> wrong);
        MemberAccount account = accounts.findByIdForUpdate(customer.getCustomerId()).orElseThrow(() -> wrong);
        LocalDateTime now = now();
        if (account.getActivationCodeHash() == null || account.getActivationExpiresAt() == null || !account.getActivationExpiresAt().isAfter(now))
            throw wrong;
        if (!encoder.matches(code.trim(), account.getActivationCodeHash())) {
            int failed = account.getFailedAttempts() + 1;
            if (failed >= MAX_FAILED) { account.setActivationCodeHash(null); account.setActivationExpiresAt(null); failed = 0; }
            account.setFailedAttempts(failed);
            accounts.save(account);
            throw wrong;
        }
        account.setPinHash(encoder.encode(pin));
        account.setActivationCodeHash(null);
        account.setActivationExpiresAt(null);
        account.setFailedAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        accounts.save(account);
        return customer;
    }

    /** Counter: a 6-digit one-time code, valid 10 minutes, so the customer standing there can claim the card or reset the PIN. */
    @Transactional
    public IssuedCode issueActivationCode(int customerId) {
        customers.findById(customerId).orElseThrow(() -> new MemberException(404, "Không tìm thấy khách hàng."));
        MemberAccount account = accounts.findByIdForUpdate(customerId).orElseGet(() -> new MemberAccount(customerId, now()));
        String code = String.format("%06d", random.nextInt(1_000_000));
        LocalDateTime expires = now().plus(ACTIVATION_VALID);
        account.setActivationCodeHash(encoder.encode(code));
        account.setActivationExpiresAt(expires);
        account.setFailedAttempts(0);
        accounts.save(account);
        return new IssuedCode(code, expires, account.isActivated());
    }

    /** "ACTIVE" (has a PIN), "PENDING" (account row without PIN) or "NONE". */
    public String status(int customerId) {
        return accounts.findById(customerId).map(a -> a.isActivated() ? "ACTIVE" : "PENDING").orElse("NONE");
    }

    // ---- the card page -------------------------------------------------------------------------------------------
    public Map<String, Object> card(int customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new MemberException(401, "Phiên đăng nhập đã hết hạn."));
        MemberAccount account = accounts.findById(customerId).orElse(null);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", customer.getFullName() == null || customer.getFullName().isBlank() ? "Khách hàng" : customer.getFullName());
        card.put("customer_code", customer.getCustomerCode());
        card.put("phone", maskPhone(customer.getPhone()));
        card.put("member_since", account == null ? null : account.getCreatedAt());
        int balance = loyalty.balance(customerId);
        LoyaltyService.TierStatus tier = loyalty.tier(customerId, now());
        if (tier != null) card.putAll(tier.describe());
        boolean boosted = tier != null && tier.tier() != LoyaltyService.Tier.MEMBER;
        card.put("points", balance);
        card.put("points_value", LoyaltyService.REDEEM_STEP_VALUE.multiply(BigDecimal.valueOf(balance / LoyaltyService.REDEEM_STEP)));
        card.put("points_rule", "1 điểm cho mỗi 10.000 đ thanh toán" + (boosted
                ? " · hạng " + tier.tier().label + " được ×" + tier.tier().multiplier.stripTrailingZeros().toPlainString().replace('.', ',') : ""));
        card.put("redeem_rule", "100 điểm = giảm 10.000 đ khi thanh toán tại quầy (tối đa 50% hóa đơn). Nhờ thu ngân quét mã QR của bạn.");
        card.put("total_orders", customer.getTotalOrders());
        card.put("offer", offer(customerId));
        card.put("orders", jdbc.queryForList("""
                SELECT TOP (10) o.transaction_no AS transactionNo, o.order_date AS orderDate, o.final_amount AS paid,
                       o.discount_amount AS discount, o.payment_method AS paymentMethod, o.points_redeemed AS pointsUsed,
                       (SELECT COALESCE(SUM(i.quantity),0) FROM order_items i WHERE i.transaction_no=o.transaction_no) AS items,
                       COALESCE(p.points,0) AS points
                FROM orders o LEFT JOIN loyalty_points p ON p.transaction_no=o.transaction_no AND p.kind='EARN'
                WHERE o.customer_id=? ORDER BY o.order_date DESC, o.transaction_no DESC
                """, customerId));
        List<Map<String, Object>> history = new ArrayList<>();
        for (LoyaltyPoint row : loyalty.recent(customerId))
            history.add(Map.of("points", row.getPoints(), "note", row.getNote(), "at", row.getCreatedAt()));
        card.put("points_history", history);
        return card;
    }

    /** The approved offer of the customer's group, in customer wording (the internal group name is never shown). */
    private Map<String, Object> offer(int customerId) {
        var rows = jdbc.queryForList("""
                SELECT s.title, s.discount_percent AS discountPercent, s.gift_description AS gift
                FROM customers c JOIN strategies s ON s.segment_id=c.segment_id AND s.is_active=1
                WHERE c.customer_id=?
                  AND NOT EXISTS (SELECT 1 FROM campaign_assignments a JOIN campaign_experiments e ON e.id=a.experiment_id
                                  WHERE e.strategy_id=s.id AND e.ended_at IS NULL AND a.customer_id=c.customer_id AND a.arm='CONTROL')
                """, customerId);
        if (rows.isEmpty()) return null;
        Map<String, Object> offer = new LinkedHashMap<>(rows.get(0));
        String title = String.valueOf(offer.get("title"));
        offer.put("title", title.startsWith(DEFAULT_OFFER_PREFIX) ? "Ưu đãi dành riêng cho bạn" : title);
        return offer;
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 4) + "•••" + phone.substring(phone.length() - 3);
    }
}
