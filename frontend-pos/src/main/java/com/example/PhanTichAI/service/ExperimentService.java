package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Strategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Does an offer really bring customers back? A holdout experiment (control group):
 * <ol>
 *   <li>when a manager switches a strategy on, every customer currently in that group is assigned at random — fixed by
 *       the customer id — to OFFER (90%) or CONTROL (10%). Control customers do not get the offer at the counter nor
 *       see it on their card;</li>
 *   <li>for 30 days the share of each side that came back and bought at the store is compared (two-proportion z-test,
 *       95% confidence interval of the difference);</li>
 *   <li>with few customers the answer is "not enough data", and the page says how many customers would be needed.</li>
 * </ol>
 * A simulation (clearly labelled, nothing stored) shows what a result looks like with assumed numbers.
 */
@Service
public class ExperimentService {
    public static final int CONTROL_PERCENT = 10;
    public static final int WINDOW_DAYS = 30;
    public static final String OFFER = "OFFER", CONTROL = "CONTROL";
    /** Below this the result is not interpreted. */
    static final int MIN_CONTROL = 30, MIN_RETURNS = 10;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ExperimentService(JdbcTemplate jdbc) { this(jdbc, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))); }
    ExperimentService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    // ---- assignment ----------------------------------------------------------------------------------------------
    /** Fixed random side for a customer in one experiment: SHA-256 of "experiment:customer" mapped to 0..99. */
    static String arm(int experimentId, int customerId, int controlPercent) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((experimentId + ":" + customerId).getBytes(StandardCharsets.UTF_8));
            int bucket = Math.floorMod(((hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff), 100);
            return bucket < controlPercent ? CONTROL : OFFER;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Starts measuring a strategy that was just switched on (inside the caller's transaction). */
    public Integer start(Strategy strategy, String startedBy) {
        if (strategy == null || strategy.getId() == null || strategy.getSegmentId() == null) return null;
        end(strategy.getId());
        LocalDateTime now = now();
        Integer id = jdbc.queryForObject("""
                INSERT INTO campaign_experiments(strategy_id, segment_id, strategy_title, control_percent, window_days, started_at, started_by, cohort_size)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, 0)""", Integer.class,
                strategy.getId(), strategy.getSegmentId(), strategy.getTitle() == null ? "Ưu đãi" : strategy.getTitle(),
                CONTROL_PERCENT, WINDOW_DAYS, Timestamp.valueOf(now), startedBy == null ? null : startedBy.substring(0, Math.min(100, startedBy.length())));
        List<Integer> cohort = jdbc.queryForList("SELECT customer_id FROM customers WHERE segment_id = ? ORDER BY customer_id", Integer.class, strategy.getSegmentId());
        List<Object[]> rows = new ArrayList<>(cohort.size());
        for (Integer customer : cohort) rows.add(new Object[]{id, customer, arm(id, customer, CONTROL_PERCENT), Timestamp.valueOf(now)});
        if (!rows.isEmpty())
            jdbc.batchUpdate("INSERT INTO campaign_assignments(experiment_id, customer_id, arm, assigned_at) VALUES (?, ?, ?, ?)", rows);
        jdbc.update("UPDATE campaign_experiments SET cohort_size = ? WHERE id = ?", cohort.size(), id);
        return id;
    }

    /** Stops measuring (the strategy was switched off or replaced). */
    public void end(int strategyId) {
        jdbc.update("UPDATE campaign_experiments SET ended_at = ? WHERE strategy_id = ? AND ended_at IS NULL", Timestamp.valueOf(now()), strategyId);
    }

    /** True when the customer is in the control group of the running experiment of this (active) strategy. */
    public boolean inControl(int strategyId, int customerId) {
        List<String> arms = jdbc.queryForList("""
                SELECT a.arm FROM campaign_assignments a JOIN campaign_experiments e ON e.id = a.experiment_id
                WHERE e.strategy_id = ? AND e.ended_at IS NULL AND a.customer_id = ?""", String.class, strategyId, customerId);
        return arms.contains(CONTROL);
    }

    /** Experiments whose strategy is no longer on (switched off, or replaced by another clustering) are closed. */
    void closeStale() {
        jdbc.update("""
                UPDATE e SET ended_at = ? FROM campaign_experiments e
                WHERE e.ended_at IS NULL AND NOT EXISTS (
                    SELECT 1 FROM strategies s WHERE s.id = e.strategy_id AND s.is_active = 1 AND s.segment_id = e.segment_id)""",
                Timestamp.valueOf(now()));
    }

    // ---- results -------------------------------------------------------------------------------------------------
    public record Arm(int customers, int returned, BigDecimal spend) {
        double rate() { return customers == 0 ? 0 : (double) returned / customers; }
        Map<String, Object> describe() {
            return Map.of("customers", customers, "returned", returned, "rate", round(rate(), 4), "spend", spend,
                    "spend_per_customer", customers == 0 ? BigDecimal.ZERO : spend.divide(BigDecimal.valueOf(customers), 0, RoundingMode.HALF_UP));
        }
    }

    public List<Map<String, Object>> results() {
        closeStale();
        LocalDateTime now = now();
        List<Map<String, Object>> experiments = jdbc.queryForList("""
                SELECT TOP (20) id, strategy_id AS strategyId, segment_id AS segmentId, strategy_title AS title, control_percent AS controlPercent,
                       window_days AS windowDays, started_at AS startedAt, ended_at AS endedAt, started_by AS startedBy, cohort_size AS cohortSize
                FROM campaign_experiments ORDER BY started_at DESC, id DESC""");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> experiment : experiments) {
            LocalDateTime started = ((Timestamp) experiment.get("startedAt")).toLocalDateTime();
            LocalDateTime ended = experiment.get("endedAt") == null ? null : ((Timestamp) experiment.get("endedAt")).toLocalDateTime();
            int window = ((Number) experiment.get("windowDays")).intValue();
            LocalDateTime windowEnd = started.plusDays(window);
            LocalDateTime measuredUntil = min(min(windowEnd, ended == null ? now : ended), now);
            Map<String, Arm> arms = measure(((Number) experiment.get("id")).intValue(), started, measuredUntil);
            Map<String, Object> row = new LinkedHashMap<>(experiment);
            row.put("running", ended == null);
            row.put("days_measured", Math.max(0, Duration.between(started, measuredUntil).toDays()));
            row.put("window_complete", !measuredUntil.isBefore(windowEnd));
            row.put("offer", arms.get(OFFER).describe());
            row.put("control", arms.get(CONTROL).describe());
            row.put("analysis", compare(arms.get(OFFER), arms.get(CONTROL)));
            result.add(row);
        }
        return result;
    }

    private Map<String, Arm> measure(int experimentId, LocalDateTime from, LocalDateTime until) {
        Map<String, Arm> arms = new HashMap<>(Map.of(OFFER, new Arm(0, 0, BigDecimal.ZERO), CONTROL, new Arm(0, 0, BigDecimal.ZERO)));
        jdbc.query("""
                SELECT a.arm, COUNT(*) AS customers, SUM(CASE WHEN x.orders > 0 THEN 1 ELSE 0 END) AS returned, COALESCE(SUM(x.spend), 0) AS spend
                FROM campaign_assignments a
                OUTER APPLY (SELECT COUNT(*) AS orders, SUM(o.final_amount) AS spend FROM orders o
                             WHERE o.customer_id = a.customer_id AND o.payment_method <> 'ONLINE' AND o.order_date >= ? AND o.order_date < ?) x
                WHERE a.experiment_id = ? GROUP BY a.arm""", row -> {
            arms.put(row.getString("arm"), new Arm(row.getInt("customers"), row.getInt("returned"), row.getBigDecimal("spend")));
        }, Timestamp.valueOf(from), Timestamp.valueOf(until), experimentId);
        return arms;
    }

    /** Two-proportion z-test of "came back to buy" between the two sides, with a plain-language verdict. */
    public static Map<String, Object> compare(Arm offer, Arm control) {
        double p1 = offer.rate(), p2 = control.rate(), difference = p1 - p2;
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("difference", round(difference, 4));
        analysis.put("relative_lift", p2 > 0 ? round(difference / p2, 4) : null);
        int n1 = offer.customers(), n2 = control.customers();
        Double pValue = null;
        if (n1 > 0 && n2 > 0) {
            double pooled = (double) (offer.returned() + control.returned()) / (n1 + n2);
            double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
            pValue = se == 0 ? 1.0 : 2 * (1 - normalCdf(Math.abs(difference) / se));
            double seDiff = Math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2);
            analysis.put("ci_low", round(difference - 1.96 * seDiff, 4));
            analysis.put("ci_high", round(difference + 1.96 * seDiff, 4));
        }
        analysis.put("p_value", pValue == null ? null : round(pValue, 4));
        String verdict, message;
        if (pValue == null || n2 < MIN_CONTROL || n1 < MIN_CONTROL || offer.returned() + control.returned() < MIN_RETURNS) {
            verdict = "INSUFFICIENT";
            message = "Chưa đủ dữ liệu để kết luận: cần ít nhất " + MIN_CONTROL + " khách ở nhóm đối chứng và " + MIN_RETURNS
                    + " lượt khách quay lại ở hai nhóm cộng lại.";
        } else if (pValue < 0.05 && difference > 0) {
            verdict = "EFFECTIVE";
            message = "Ưu đãi có hiệu quả: nhóm nhận ưu đãi quay lại nhiều hơn " + percent(difference) + " (độ tin cậy 95%).";
        } else if (pValue < 0.05) {
            verdict = "NEGATIVE";
            message = "Nhóm nhận ưu đãi quay lại ít hơn nhóm đối chứng — nên xem lại nội dung ưu đãi.";
        } else {
            verdict = "NO_DIFFERENCE";
            message = "Chưa thấy khác biệt rõ ràng giữa hai nhóm (p = " + String.format(Locale.ROOT, "%.2f", pValue) + " ≥ 0,05).";
        }
        analysis.put("verdict", verdict);
        analysis.put("message", message);
        double base = p2 > 0 && p2 < 0.95 ? p2 : 0.10;
        analysis.put("needed_per_arm_for_5_points", sampleSize(base, Math.min(0.99, base + 0.05)));
        analysis.put("needed_base_rate", round(base, 4));
        return analysis;
    }

    /** Customers needed in EACH group to detect p0 → p1 with 95% confidence and 80% power. */
    public static int sampleSize(double p0, double p1) {
        double zAlpha = 1.959964, zBeta = 0.841621, mean = (p0 + p1) / 2;
        double numerator = zAlpha * Math.sqrt(2 * mean * (1 - mean)) + zBeta * Math.sqrt(p0 * (1 - p0) + p1 * (1 - p1));
        return (int) Math.ceil(numerator * numerator / ((p1 - p0) * (p1 - p0)));
    }

    // ---- simulation (nothing is stored) --------------------------------------------------------------------------
    /** A whole group of {@code groupSize} customers split 90/10 like a real experiment, with assumed return rates. */
    public static Map<String, Object> simulate(double baseRate, double upliftPoints, int groupSize, long seed) {
        if (baseRate < 0 || baseRate > 0.9) throw new IllegalArgumentException("Tỉ lệ quay lại của nhóm đối chứng từ 0% đến 90%.");
        if (upliftPoints < -0.5 || upliftPoints > 0.5 || baseRate + upliftPoints < 0 || baseRate + upliftPoints > 1)
            throw new IllegalArgumentException("Mức chênh lệch giả định không hợp lệ.");
        if (groupSize < 20 || groupSize > 1_000_000) throw new IllegalArgumentException("Số khách của nhóm từ 20 đến 1.000.000.");
        Random random = new Random(seed);
        int control = Math.max(1, groupSize * CONTROL_PERCENT / 100), offer = groupSize - control;
        int returnedControl = 0, returnedOffer = 0;
        for (int i = 0; i < control; i++) if (random.nextDouble() < baseRate) returnedControl++;
        for (int i = 0; i < offer; i++) if (random.nextDouble() < baseRate + upliftPoints) returnedOffer++;
        Arm offerArm = new Arm(offer, returnedOffer, BigDecimal.ZERO), controlArm = new Arm(control, returnedControl, BigDecimal.ZERO);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulated", true);
        result.put("assumptions", Map.of("base_rate", baseRate, "uplift", upliftPoints, "group_size", groupSize, "seed", seed));
        result.put("offer", offerArm.describe());
        result.put("control", controlArm.describe());
        result.put("analysis", compare(offerArm, controlArm));
        return result;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------
    /** Standard normal CDF (Abramowitz–Stegun 26.2.17, error < 7.5e-8). */
    static double normalCdf(double z) {
        double t = 1 / (1 + 0.2316419 * Math.abs(z));
        double d = 0.3989422804014327 * Math.exp(-z * z / 2);
        double tail = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return z >= 0 ? 1 - tail : tail;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
    private static double round(double value, int digits) { return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue(); }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f", value * 100).replace('.', ',') + " điểm %"; }
}
