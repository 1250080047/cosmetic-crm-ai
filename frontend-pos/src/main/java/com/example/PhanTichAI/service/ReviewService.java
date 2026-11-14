package com.example.PhanTichAI.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Product reviews by customers (book chapter 10: text mining & sentiment).
 * <ul>
 *   <li>a customer reviews only products they bought at the store (1–5 stars and up to 500 characters), one review per
 *       product, which they can edit;</li>
 *   <li>the text is analysed on this computer (positive / neutral / negative, topics, skin irritation); if the analysis
 *       service is off, the review is kept and analysed later from the admin page;</li>
 *   <li>the first review of a product with at least {@value #BONUS_MIN_CHARS} characters earns {@value #BONUS_POINTS} points
 *       (once per review);</li>
 *   <li>a reported skin reaction (dị ứng, kích ứng, nổi mẩn…) appears at once as an alert for the manager, who can also
 *       hide an unsuitable review.</li>
 * </ul>
 */
@Service
public class ReviewService {
    public static final int BONUS_POINTS = 10, BONUS_MIN_CHARS = 20, MAX_COMMENT = 500;

    private final JdbcTemplate jdbc;
    private final AiIntegrationService ai;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public ReviewService(JdbcTemplate jdbc, AiIntegrationService ai, PlatformTransactionManager transactions) {
        this(jdbc, ai, transactions == null ? null : new TransactionTemplate(transactions), Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    ReviewService(JdbcTemplate jdbc, AiIntegrationService ai, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc; this.ai = ai; this.transaction = transaction; this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    /** Cleans the text: control characters removed, spaces collapsed, at most 500 characters. */
    static String clean(String comment) {
        if (comment == null) return "";
        String text = comment.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > MAX_COMMENT) throw new IllegalArgumentException("Nhận xét tối đa " + MAX_COMMENT + " ký tự.");
        return text;
    }

    // ---- customer side -----------------------------------------------------------------------------------------
    /** Products the customer bought at the store, newest first, with their review if any. */
    public List<Map<String, Object>> forCustomer(int customerId) {
        return jdbc.queryForList("""
                SELECT p.id AS productId, p.product_name AS productName, p.image_url AS imageUrl, MAX(o.order_date) AS lastBought,
                       r.id AS reviewId, r.rating, r.comment, r.status, r.updated_at AS reviewedAt,
                       CASE WHEN EXISTS (SELECT 1 FROM loyalty_points lp WHERE lp.review_id = r.id) THEN 1 ELSE 0 END AS bonusGiven
                FROM orders o JOIN order_items i ON i.transaction_no = o.transaction_no JOIN products p ON p.id = i.product_id
                LEFT JOIN product_reviews r ON r.customer_id = o.customer_id AND r.product_id = p.id
                WHERE o.customer_id = ? AND o.payment_method <> 'ONLINE'
                GROUP BY p.id, p.product_name, p.image_url, r.id, r.rating, r.comment, r.status, r.updated_at
                ORDER BY MAX(o.order_date) DESC, p.id""", customerId);
    }

    public Map<String, Object> save(int customerId, int productId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Chọn từ 1 đến 5 sao.");
        String text = clean(comment);
        Integer bought = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders o JOIN order_items i ON i.transaction_no = o.transaction_no
                WHERE o.customer_id = ? AND o.payment_method <> 'ONLINE' AND i.product_id = ?""", Integer.class, customerId, productId);
        if (bought == null || bought == 0) throw new IllegalArgumentException("Bạn chỉ đánh giá được sản phẩm đã mua tại cửa hàng.");
        Map<String, Object> analysis = ai == null ? null : ai.analyzeReview(text, rating);   // outside the transaction (network call)
        return transaction.execute(status -> store(customerId, productId, rating, text, analysis));
    }

    private Map<String, Object> store(int customerId, int productId, int rating, String text, Map<String, Object> analysis) {
        LocalDateTime now = now();
        List<Integer> existing = jdbc.queryForList(
                "SELECT id FROM product_reviews WITH (UPDLOCK, HOLDLOCK) WHERE customer_id = ? AND product_id = ?", Integer.class, customerId, productId);
        String sentiment = analysis == null ? null : (String) analysis.get("sentiment");
        BigDecimal score = analysis == null || analysis.get("score") == null ? null : new BigDecimal(String.valueOf(analysis.get("score")));
        String topics = analysis == null ? null : topics(analysis.get("topics"));
        boolean irritation = analysis != null && Boolean.TRUE.equals(analysis.get("irritation"));
        Timestamp at = Timestamp.valueOf(now), analyzedAt = analysis == null ? null : at;
        Integer reviewId;
        boolean created = existing.isEmpty();
        if (created) {
            reviewId = jdbc.queryForObject("""
                    INSERT INTO product_reviews(customer_id, product_id, rating, comment, sentiment, sentiment_score, topics, irritation, status,
                                                created_at, updated_at, analyzed_at)
                    OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'VISIBLE', ?, ?, ?)""", Integer.class,
                    customerId, productId, rating, text.isEmpty() ? null : text, sentiment, score, topics, irritation, at, at, analyzedAt);
        } else {
            reviewId = existing.get(0);
            jdbc.update("""
                    UPDATE product_reviews SET rating = ?, comment = ?, sentiment = ?, sentiment_score = ?, topics = ?, irritation = ?,
                           updated_at = ?, analyzed_at = ? WHERE id = ?""",
                    rating, text.isEmpty() ? null : text, sentiment, score, topics, irritation, at, analyzedAt, reviewId);
        }
        int bonus = 0;
        if (text.length() >= BONUS_MIN_CHARS
                && jdbc.queryForObject("SELECT COUNT(*) FROM loyalty_points WHERE review_id = ?", Integer.class, reviewId) == 0) {
            String product = jdbc.queryForObject("SELECT product_name FROM products WHERE id = ?", String.class, productId);
            String note = "Thưởng đánh giá " + (product.length() > 150 ? product.substring(0, 150) : product);
            jdbc.update("INSERT INTO loyalty_points(customer_id, transaction_no, points, note, created_at, kind, review_id) VALUES (?, NULL, ?, ?, ?, 'BONUS', ?)",
                    customerId, BONUS_POINTS, note, at, reviewId);
            bonus = BONUS_POINTS;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("review_id", reviewId);
        result.put("created", created);
        result.put("sentiment", sentiment);
        result.put("bonus_points", bonus);
        result.put("message", (created ? "Cảm ơn bạn đã đánh giá!" : "Đã cập nhật đánh giá.")
                + (bonus > 0 ? " Bạn được cộng " + bonus + " điểm." : text.length() < BONUS_MIN_CHARS && created
                        ? " Viết nhận xét từ " + BONUS_MIN_CHARS + " ký tự để được thưởng " + BONUS_POINTS + " điểm." : ""));
        return result;
    }

    private static String topics(Object value) {
        if (!(value instanceof Collection<?> list) || list.isEmpty()) return null;
        String joined = String.join(", ", list.stream().map(String::valueOf).toList());
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }

    // ---- manager side ------------------------------------------------------------------------------------------
    public Map<String, Object> overview() {
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT COUNT(*) AS reviews, CAST(AVG(CAST(rating AS DECIMAL(4,2))) AS DECIMAL(4,2)) AS averageRating,
                       COALESCE(SUM(CASE WHEN sentiment = 'POSITIVE' THEN 1 ELSE 0 END), 0) AS positive,
                       COALESCE(SUM(CASE WHEN sentiment = 'NEGATIVE' THEN 1 ELSE 0 END), 0) AS negative,
                       COALESCE(SUM(CASE WHEN irritation = 1 THEN 1 ELSE 0 END), 0) AS irritation,
                       COALESCE(SUM(CASE WHEN analyzed_at IS NULL THEN 1 ELSE 0 END), 0) AS pending,
                       COALESCE(SUM(CASE WHEN status = 'HIDDEN' THEN 1 ELSE 0 END), 0) AS hidden
                FROM product_reviews""");
        List<Map<String, Object>> products = jdbc.queryForList("""
                SELECT TOP (100) p.id AS productId, p.product_name AS productName, COUNT(*) AS reviews,
                       CAST(AVG(CAST(r.rating AS DECIMAL(4,2))) AS DECIMAL(4,2)) AS averageRating,
                       SUM(CASE WHEN r.sentiment = 'NEGATIVE' THEN 1 ELSE 0 END) AS negative,
                       SUM(CASE WHEN r.irritation = 1 THEN 1 ELSE 0 END) AS irritation
                FROM product_reviews r JOIN products p ON p.id = r.product_id
                GROUP BY p.id, p.product_name
                ORDER BY SUM(CASE WHEN r.irritation = 1 THEN 1 ELSE 0 END) DESC, SUM(CASE WHEN r.sentiment = 'NEGATIVE' THEN 1 ELSE 0 END) DESC,
                         COUNT(*) DESC, p.id""");
        Map<String, Integer> topicCounts = new LinkedHashMap<>(), negativeTopics = new LinkedHashMap<>();
        jdbc.query("SELECT topics, sentiment FROM product_reviews WHERE topics IS NOT NULL", row -> {
            for (String topic : row.getString("topics").split(",\\s*")) {
                topicCounts.merge(topic, 1, Integer::sum);
                if ("NEGATIVE".equals(row.getString("sentiment"))) negativeTopics.merge(topic, 1, Integer::sum);
            }
        });
        List<Map<String, Object>> topics = new ArrayList<>();
        topicCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> topics.add(Map.of("topic", entry.getKey(), "reviews", entry.getValue(), "negative", negativeTopics.getOrDefault(entry.getKey(), 0))));
        return Map.of("summary", summary, "products", products, "topics", topics,
                "rules", Map.of("bonus_points", BONUS_POINTS, "bonus_min_chars", BONUS_MIN_CHARS));
    }

    public List<Map<String, Object>> list(String filter) {
        String where = switch (filter == null ? "" : filter) {
            case "", "all" -> "1 = 1";
            case "negative" -> "r.sentiment = 'NEGATIVE'";
            case "irritation" -> "r.irritation = 1";
            case "hidden" -> "r.status = 'HIDDEN'";
            case "pending" -> "r.analyzed_at IS NULL";
            default -> throw new IllegalArgumentException("Bộ lọc không hợp lệ.");
        };
        return jdbc.queryForList("""
                SELECT TOP (200) r.id, r.rating, r.comment, r.sentiment, r.sentiment_score AS score, r.topics, r.irritation, r.status,
                       r.created_at AS createdAt, r.updated_at AS updatedAt, p.id AS productId, p.product_name AS productName,
                       c.customer_code AS customerCode
                FROM product_reviews r JOIN products p ON p.id = r.product_id JOIN customers c ON c.customer_id = r.customer_id
                WHERE\s""" + where + " ORDER BY r.irritation DESC, r.updated_at DESC, r.id DESC");
    }

    public Map<String, Object> setStatus(int reviewId, String status, Integer userId) {
        if (!"VISIBLE".equals(status) && !"HIDDEN".equals(status)) throw new IllegalArgumentException("Trạng thái không hợp lệ.");
        int updated = jdbc.update("UPDATE product_reviews SET status = ?, hidden_by = ? WHERE id = ?",
                status, "HIDDEN".equals(status) ? userId : null, reviewId);
        if (updated == 0) throw new IllegalArgumentException("Không tìm thấy đánh giá.");
        return Map.of("success", true, "status", status);
    }

    /** Analyses the reviews kept while the analysis service was off. */
    public Map<String, Object> analyzePending() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT TOP (200) id, rating, comment FROM product_reviews WHERE analyzed_at IS NULL ORDER BY id");
        int done = 0;
        for (Map<String, Object> review : pending) {
            Map<String, Object> analysis = ai == null ? null : ai.analyzeReview((String) review.get("comment"), ((Number) review.get("rating")).intValue());
            if (analysis == null) break;   // service still off
            jdbc.update("UPDATE product_reviews SET sentiment = ?, sentiment_score = ?, topics = ?, irritation = ?, analyzed_at = ? WHERE id = ?",
                    analysis.get("sentiment"), new BigDecimal(String.valueOf(analysis.get("score"))), topics(analysis.get("topics")),
                    Boolean.TRUE.equals(analysis.get("irritation")), Timestamp.valueOf(now()), review.get("id"));
            done++;
        }
        return Map.of("success", true, "analyzed", done, "pending", pending.size() - done);
    }

    /** Average stars of visible reviews per product, for the POS catalogue. */
    public static final String RATINGS_SQL = """
            SELECT product_id, CAST(AVG(CAST(rating AS DECIMAL(4,2))) AS DECIMAL(3,1)) AS rating, COUNT(*) AS reviews
            FROM product_reviews WHERE status = 'VISIBLE' GROUP BY product_id""";
}
