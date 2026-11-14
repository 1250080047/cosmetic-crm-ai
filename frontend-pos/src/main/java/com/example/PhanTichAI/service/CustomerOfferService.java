package com.example.PhanTichAI.service;

import com.example.PhanTichAI.dto.AiPredictResponse;
import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.entity.Product;
import com.example.PhanTichAI.entity.Strategy;
import com.example.PhanTichAI.repository.ProductRepository;
import com.example.PhanTichAI.repository.StrategyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class CustomerOfferService {
    /** F and M count the invoices of the last 365 days, the same window the model was trained with. */
    public static final int WINDOW_DAYS = 365;

    private final AiIntegrationService aiService;
    private final StrategyRepository strategies;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final ExperimentService experiments;

    public CustomerOfferService(AiIntegrationService aiService, StrategyRepository strategies, ProductRepository products) {
        this(aiService, strategies, products, null, null);
    }

    public CustomerOfferService(AiIntegrationService aiService, StrategyRepository strategies, ProductRepository products, JdbcTemplate jdbc) {
        this(aiService, strategies, products, jdbc, null);
    }

    @Autowired
    public CustomerOfferService(AiIntegrationService aiService, StrategyRepository strategies, ProductRepository products, JdbcTemplate jdbc,
                                ExperimentService experiments) {
        this.aiService = aiService;
        this.strategies = strategies;
        this.products = products;
        this.jdbc = jdbc;
        this.experiments = experiments;
    }

    /** L = days since first purchase, R = days since last purchase, F/M = invoices/money in the last 365 days. */
    public record Lrfm(double length, double recency, double frequency, double monetary, boolean hasPurchase) {
        public Lrfm afterPurchase(BigDecimal amount) {
            return new Lrfm(hasPurchase ? length : 0, 0, frequency + 1,
                    monetary + (amount == null ? 0 : amount.doubleValue()), true);
        }
    }

    /** {@code controlGroup}: the customer is in the 10% kept without the offer to measure whether it works. */
    public record Offer(Integer strategyId, int clusterId, String segment, String color,
                        BigDecimal discountPercent, String giftDescription, String cashierScript,
                        List<Product> giftProducts, int giftQuantityLimit, boolean aiAvailable, boolean controlGroup) {
        public Offer(Integer strategyId, int clusterId, String segment, String color, BigDecimal discountPercent, String giftDescription,
                     String cashierScript, List<Product> giftProducts, int giftQuantityLimit, boolean aiAvailable) {
            this(strategyId, clusterId, segment, color, discountPercent, giftDescription, cashierScript, giftProducts, giftQuantityLimit, aiAvailable, false);
        }
    }

    @Transactional(readOnly = true)
    public Offer forCustomer(Customer customer) {
        AiPredictResponse ai = predict(measure(customer));
        if (ai == null) return unavailable();
        Strategy stored = ai.getCluster_id() >= 0
                ? strategies.findBySegmentId(ai.getCluster_id()).filter(s -> Boolean.TRUE.equals(s.getIsActive())).orElse(null)
                : null;
        // Control group of the running experiment: no discount, no gift — that is how the offer's effect is measured.
        boolean control = stored != null && experiments != null && customer.getCustomerId() != null
                && experiments.inControl(stored.getId(), customer.getCustomerId());
        if (control) stored = null;
        BigDecimal discount = stored != null && stored.getDiscountPercent() != null ? stored.getDiscountPercent() : BigDecimal.ZERO;
        if (discount.signum() < 0 || discount.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Mức giảm trong chiến lược chưa hợp lệ. Vui lòng kiểm tra trang quản trị.");
        String gift = stored != null && stored.getGiftDescription() != null ? stored.getGiftDescription() : "";
        List<Product> eligible = gift.isBlank() ? List.of() : products.findByIsActiveTrueOrderByIdAsc().stream()
                .filter(p -> p.getStockQuantity() != null && p.getStockQuantity() > 0 && GiftMatcher.matches(gift, p)).toList();
        String script = CustomerLanguage.cashierScript(stored == null ? "" : stored.getCashierScript(), discount, gift);
        return new Offer(stored == null ? null : stored.getId(), ai.getCluster_id(),
                CustomerLanguage.segment(ai.getSegment_name()), ai.getColor_code(), discount, gift, script,
                eligible, GiftMatcher.quantityLimit(gift), ai.getCluster_id() != -100, control);
    }

    /** Group after a sale, computed from the values measured before the sale plus this invoice. No benefit lookup. */
    @Transactional(readOnly = true)
    public Integer classifyAfterPurchase(Customer customer, Lrfm before, BigDecimal amount) {
        Lrfm measured = before == null ? measure(customer) : before;
        AiPredictResponse prediction = predict(measured.afterPurchase(amount));
        return prediction == null || prediction.getCluster_id() < 0 ? null : prediction.getCluster_id();
    }

    /** Measures the customer today from the invoices in SQL (falls back to the stored totals without a database). */
    public Lrfm measure(Customer customer) {
        LocalDate today = LocalDate.now(ShiftService.STORE_ZONE);
        if (jdbc == null || customer.getCustomerId() == null) {
            boolean bought = customer.getLastPurchase() != null;
            return new Lrfm(days(customer.getFirstPurchase(), today), days(customer.getLastPurchase(), today),
                    customer.getTotalOrders() == null ? 0 : customer.getTotalOrders(),
                    customer.getTotalSpent() == null ? 0 : customer.getTotalSpent().doubleValue(), bought);
        }
        LocalDateTime start = today.minusDays(WINDOW_DAYS - 1).atStartOfDay(), end = today.plusDays(1).atStartOfDay();
        return jdbc.queryForObject("""
                SELECT MIN(order_date) AS first_purchase, MAX(order_date) AS last_purchase,
                       COALESCE(SUM(CASE WHEN order_date >= ? THEN 1 ELSE 0 END),0) AS frequency,
                       COALESCE(SUM(CASE WHEN order_date >= ? THEN final_amount ELSE 0 END),0) AS monetary
                FROM orders WHERE customer_id=? AND order_date < ?
                """, (rs, row) -> {
            Timestamp first = rs.getTimestamp("first_purchase"), last = rs.getTimestamp("last_purchase");
            return new Lrfm(days(first == null ? null : first.toLocalDateTime(), today),
                    days(last == null ? null : last.toLocalDateTime(), today),
                    rs.getDouble("frequency"), rs.getBigDecimal("monetary").doubleValue(), last != null);
        }, Timestamp.valueOf(start), Timestamp.valueOf(start), customer.getCustomerId(), Timestamp.valueOf(end));
    }

    public static int days(LocalDateTime moment, LocalDate today) {
        if (moment == null) return 0;
        return (int) Math.max(0, Math.min(ChronoUnit.DAYS.between(moment.toLocalDate(), today), 100_000));
    }

    private AiPredictResponse predict(Lrfm values) {
        if (jdbc != null && !lockCurrentModel()) return null;
        AiPredictResponse prediction = aiService.predictSegment(values.length(), values.recency(), values.frequency(),
                values.monetary(), values.hasPurchase());
        return jdbc != null && prediction.getCluster_id() != -100 && !sameActiveVersion(prediction.getActive_version()) ? null : prediction;
    }

    private boolean lockCurrentModel() {
        Integer status = jdbc.queryForObject("""
                SET NOCOUNT ON;
                DECLARE @transaction_anchor int;
                SELECT TOP (1) @transaction_anchor=id FROM dbo.roles;
                DECLARE @result int;
                EXEC @result = sys.sp_getapplock @Resource=N'cosmetic-crm:cluster-model',
                    @LockMode='Shared',@LockOwner='Transaction',@LockTimeout=15000;
                SELECT @result;
                """, Integer.class);
        // NOCOUNT stays on for the pooled connection otherwise: Hibernate would then read -1 updated rows
        // for the checkout's UPDATE statements and abort the sale as an optimistic-locking failure.
        jdbc.execute("SET NOCOUNT OFF");
        return status != null && status >= 0;
    }

    private boolean sameActiveVersion(String predictedVersion) {
        var versions = jdbc.queryForList("SELECT version FROM dbo.model_versions WHERE status='ACTIVE'", String.class);
        return versions.size() == 1 && versions.get(0).equals(predictedVersion);
    }

    private Offer unavailable() {
        return new Offer(null, -100, "Chưa xác định", "#64748b", BigDecimal.ZERO, "",
                CustomerLanguage.cashierScript("", BigDecimal.ZERO, ""), List.of(), 0, false);
    }
}
