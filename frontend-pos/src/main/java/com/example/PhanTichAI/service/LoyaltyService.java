package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.LoyaltyPoint;
import com.example.PhanTichAI.repository.LoyaltyPointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loyalty points and member tiers.
 * <ul>
 *   <li><b>Earn:</b> 1 point for every 10.000 đ the customer actually pays, rounded down, × the tier multiplier.</li>
 *   <li><b>Tier</b> (shown to the customer, a clear rule unlike the internal LRFM groups): spend at the store (not the
 *       OnlineSales history) in the last 12 months — Thành viên, Bạc from 2.000.000 đ (points ×1,5), Vàng from
 *       5.000.000 đ (points ×2). The base data are wholesale-sized (a median invoice is 13,1 million đ), so they are
 *       not used for tiers.</li>
 *   <li><b>Redeem</b> at the counter: 100 points = 10.000 đ (1% back), in steps of 100 points, at most 50% of the invoice
 *       after the offer, and only after the cashier scanned the QR code on the customer's phone (see PosApiController).</li>
 * </ul>
 */
@Service
public class LoyaltyService {
    public static final BigDecimal VND_PER_POINT = new BigDecimal("10000");
    public static final int REDEEM_STEP = 100;
    public static final BigDecimal REDEEM_STEP_VALUE = new BigDecimal("10000");
    public static final BigDecimal REDEEM_MAX_SHARE = new BigDecimal("0.5");
    private static final NumberFormat VND = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));

    public enum Tier {
        MEMBER("Thành viên", "0", "1"), SILVER("Bạc", "2000000", "1.5"), GOLD("Vàng", "5000000", "2");

        public final String label;
        public final BigDecimal minSpend;
        public final BigDecimal multiplier;

        Tier(String label, String minSpend, String multiplier) {
            this.label = label; this.minSpend = new BigDecimal(minSpend); this.multiplier = new BigDecimal(multiplier);
        }

        public static Tier of(BigDecimal spend12m) {
            BigDecimal spend = spend12m == null ? BigDecimal.ZERO : spend12m;
            if (spend.compareTo(GOLD.minSpend) >= 0) return GOLD;
            if (spend.compareTo(SILVER.minSpend) >= 0) return SILVER;
            return MEMBER;
        }

        public Tier next() { return this == MEMBER ? SILVER : this == SILVER ? GOLD : null; }
    }

    /** The customer's tier now, their 12-month store spend and how much more lifts them to the next tier. */
    public record TierStatus(Tier tier, BigDecimal spend12m, Tier next, BigDecimal toNext) {
        public static TierStatus of(BigDecimal spend12m) {
            Tier tier = Tier.of(spend12m);
            Tier next = tier.next();
            BigDecimal spend = spend12m == null ? BigDecimal.ZERO : spend12m;
            return new TierStatus(tier, spend, next, next == null ? BigDecimal.ZERO : next.minSpend.subtract(spend).max(BigDecimal.ZERO));
        }

        public Map<String, Object> describe() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tier", tier.name());
            map.put("tier_label", tier.label);
            map.put("points_multiplier", tier.multiplier);
            map.put("store_spend_12m", spend12m);
            map.put("next_tier", next == null ? null : next.name());
            map.put("next_tier_label", next == null ? null : next.label);
            map.put("next_tier_spend", next == null ? null : next.minSpend);
            map.put("to_next_tier", toNext);
            return map;
        }
    }

    public record Redemption(int points, BigDecimal value) {
        public static final Redemption NONE = new Redemption(0, BigDecimal.ZERO.setScale(2));
    }

    private final LoyaltyPointRepository points;
    private final JdbcTemplate jdbc;

    public LoyaltyService(LoyaltyPointRepository points) { this(points, null); }

    @Autowired
    public LoyaltyService(LoyaltyPointRepository points, JdbcTemplate jdbc) { this.points = points; this.jdbc = jdbc; }

    // ---- earning -------------------------------------------------------------------------------------------------
    public static int pointsFor(BigDecimal paid) { return pointsFor(paid, Tier.MEMBER); }

    public static int pointsFor(BigDecimal paid, Tier tier) {
        if (paid == null || paid.signum() <= 0) return 0;
        return paid.multiply(tier.multiplier).divide(VND_PER_POINT, 0, RoundingMode.DOWN).intValueExact();
    }

    /** Adds the points of one invoice (in the checkout transaction) and returns how many were added. */
    public int award(int customerId, String transactionNo, BigDecimal paid, LocalDateTime at) {
        return award(customerId, transactionNo, paid, at, Tier.MEMBER);
    }

    public int award(int customerId, String transactionNo, BigDecimal paid, LocalDateTime at, Tier tier) {
        int earned = pointsFor(paid, tier);
        String bonus = tier == Tier.MEMBER ? "" : " (hạng " + tier.label + " ×" + tier.multiplier.stripTrailingZeros().toPlainString().replace('.', ',') + ")";
        if (earned > 0) points.save(new LoyaltyPoint(customerId, transactionNo, earned, "Tích điểm hóa đơn " + transactionNo + bonus, at, LoyaltyPoint.EARN));
        return earned;
    }

    // ---- tiers ---------------------------------------------------------------------------------------------------
    /** Spend at the store (counter invoices, not the OnlineSales history) in the 365 days before {@code at}. */
    public BigDecimal storeSpend(int customerId, LocalDateTime at) {
        if (jdbc == null) return BigDecimal.ZERO;
        BigDecimal spend = jdbc.queryForObject("""
                SELECT COALESCE(SUM(final_amount), 0) FROM orders
                WHERE customer_id = ? AND payment_method <> 'ONLINE' AND order_date >= ? AND order_date < ?""",
                BigDecimal.class, customerId, at.minusDays(365), at);
        return spend == null ? BigDecimal.ZERO : spend;
    }

    public TierStatus tier(int customerId, LocalDateTime at) { return TierStatus.of(storeSpend(customerId, at)); }

    // ---- redeeming -----------------------------------------------------------------------------------------------
    /** Largest number of points (a multiple of 100) usable on an invoice of {@code payable} đ after the offer. */
    public static int maxRedeemable(int balance, BigDecimal payable) {
        if (balance < REDEEM_STEP || payable == null || payable.signum() <= 0) return 0;
        int byBalance = balance / REDEEM_STEP;
        int byInvoice = payable.multiply(REDEEM_MAX_SHARE).divide(REDEEM_STEP_VALUE, 0, RoundingMode.DOWN).intValue();
        return Math.max(0, Math.min(byBalance, byInvoice)) * REDEEM_STEP;
    }

    /** Checks a request to use points and returns the money off; the message explains a refusal to the cashier. */
    public static Redemption plan(int requested, int balance, BigDecimal payable) {
        if (requested < 0) throw new IllegalArgumentException("Số điểm đổi không hợp lệ.");
        if (requested == 0) return Redemption.NONE;
        if (requested % REDEEM_STEP != 0) throw new IllegalArgumentException("Đổi điểm theo từng 100 điểm (100 điểm = 10.000 đ).");
        if (requested > balance) throw new IllegalArgumentException("Khách chỉ còn " + VND.format(balance) + " điểm.");
        if (requested > maxRedeemable(balance, payable))
            throw new IllegalArgumentException("Tiền giảm từ điểm không được quá 50% hóa đơn sau ưu đãi.");
        return new Redemption(requested, REDEEM_STEP_VALUE.multiply(BigDecimal.valueOf(requested / REDEEM_STEP)).setScale(2));
    }

    /** Locks the customer's member card for this transaction (no double spending); false when the card is not activated. */
    public boolean lockActiveCard(int customerId) {
        if (jdbc == null) return false;
        return !jdbc.queryForList("SELECT customer_id FROM member_accounts WITH (UPDLOCK, ROWLOCK) WHERE customer_id = ? AND pin_hash IS NOT NULL",
                Integer.class, customerId).isEmpty();
    }

    public void redeem(int customerId, String transactionNo, Redemption redemption, LocalDateTime at) {
        if (redemption.points() <= 0) return;
        points.save(new LoyaltyPoint(customerId, transactionNo, -redemption.points(),
                "Đổi " + VND.format(redemption.points()) + " điểm, giảm " + VND.format(redemption.value()) + " đ hóa đơn " + transactionNo,
                at, LoyaltyPoint.REDEEM));
    }

    public int balance(int customerId) {
        Long total = points.balanceOf(customerId);
        return total == null ? 0 : Math.toIntExact(total);
    }

    public List<LoyaltyPoint> recent(int customerId) { return points.findTop10ByCustomerIdOrderByCreatedAtDescIdDesc(customerId); }
}
