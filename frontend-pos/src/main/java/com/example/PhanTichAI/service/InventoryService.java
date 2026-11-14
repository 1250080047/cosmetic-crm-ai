package com.example.PhanTichAI.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Stock by lot and expiry date (cosmetics expire).
 * <ul>
 *   <li>products.stock_quantity always equals the sum of the remaining quantity of its lots (expired ones included until
 *       they are disposed of);</li>
 *   <li>what can be sold is only the lots that have not expired (or have no expiry date);</li>
 *   <li>a sale takes from the lot that expires first (FEFO) and records which lots each invoice line came from;</li>
 *   <li>alerts: lots expiring within {@value #EXPIRY_WARNING_DAYS} days, expired lots still in stock, products with at
 *       most {@value #LOW_STOCK} sellable units.</li>
 * </ul>
 */
@Service
public class InventoryService {
    public static final int EXPIRY_WARNING_DAYS = 60;
    public static final int LOW_STOCK = 10;
    private static final int MAX_LINES = 50;
    /** Sellable units per product: lots that have not expired. */
    public static final String SELLABLE_SQL = """
            SELECT product_id, SUM(quantity_remaining) AS sellable FROM stock_lots
            WHERE quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= CAST(SYSDATETIME() AS DATE))
            GROUP BY product_id""";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryService(JdbcTemplate jdbc) { this(jdbc, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))); }
    InventoryService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    private LocalDate today() { return LocalDate.now(clock); }

    public record Line(int productId, String lotNo, LocalDate expiryDate, int quantity, BigDecimal unitCost) {}
    public record Receipt(String supplier, String note, List<Line> lines) {}
    public record Allocation(int lotId, String lotNo, LocalDate expiryDate, int quantity) {}

    // ---- what can be sold --------------------------------------------------------------------------------------
    public Map<Integer, Integer> sellable() {
        Map<Integer, Integer> result = new HashMap<>();
        jdbc.query(SELLABLE_SQL, row -> { result.put(row.getInt("product_id"), row.getInt("sellable")); });
        return result;
    }

    /**
     * Takes {@code quantity} units of a product from its lots, earliest expiry first, skipping expired lots, and links
     * them to the invoice line. Runs inside the checkout transaction; the lots are locked (UPDLOCK) until it ends.
     */
    public List<Allocation> allocate(long orderItemId, int productId, int quantity, String productName) {
        List<Allocation> lots = jdbc.query("""
                SELECT id, lot_no, expiry_date, quantity_remaining FROM stock_lots WITH (UPDLOCK, ROWLOCK)
                WHERE product_id = ? AND quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= ?)
                ORDER BY CASE WHEN expiry_date IS NULL THEN 1 ELSE 0 END, expiry_date, id""",
                (row, i) -> new Allocation(row.getInt("id"), row.getString("lot_no"),
                        row.getDate("expiry_date") == null ? null : row.getDate("expiry_date").toLocalDate(), row.getInt("quantity_remaining")),
                productId, Date.valueOf(today()));
        int available = lots.stream().mapToInt(Allocation::quantity).sum();
        if (available < quantity)
            throw new IllegalArgumentException("Không đủ hàng còn hạn sử dụng: " + productName + " (còn " + available + ").");
        List<Allocation> taken = new ArrayList<>();
        int left = quantity;
        for (Allocation lot : lots) {
            if (left == 0) break;
            int take = Math.min(left, lot.quantity());
            jdbc.update("UPDATE stock_lots SET quantity_remaining = quantity_remaining - ? WHERE id = ?", take, lot.lotId());
            jdbc.update("INSERT INTO order_item_lots(order_item_id, lot_id, quantity) VALUES (?, ?, ?)", orderItemId, lot.lotId(), take);
            taken.add(new Allocation(lot.lotId(), lot.lotNo(), lot.expiryDate(), take));
            left -= take;
        }
        return taken;
    }

    // ---- receiving goods ---------------------------------------------------------------------------------------
    /** Checks a goods receipt before anything is written; the message tells the manager what to fix. */
    public List<Line> validate(Receipt receipt) {
        if (receipt == null || receipt.lines() == null || receipt.lines().isEmpty())
            throw new IllegalArgumentException("Phiếu nhập cần ít nhất một dòng hàng.");
        if (receipt.lines().size() > MAX_LINES) throw new IllegalArgumentException("Mỗi phiếu nhập tối đa " + MAX_LINES + " dòng.");
        if (receipt.supplier() != null && receipt.supplier().length() > 150) throw new IllegalArgumentException("Tên nhà cung cấp tối đa 150 ký tự.");
        if (receipt.note() != null && receipt.note().length() > 300) throw new IllegalArgumentException("Ghi chú tối đa 300 ký tự.");
        List<Line> clean = new ArrayList<>();
        int number = 0;
        for (Line line : receipt.lines()) {
            number++;
            String where = "Dòng " + number + ": ";
            if (line == null || line.productId() <= 0) throw new IllegalArgumentException(where + "chọn sản phẩm.");
            String lot = line.lotNo() == null ? "" : line.lotNo().trim();
            if (lot.isEmpty() || lot.length() > 50 || !lot.matches("[\\p{L}\\p{N}._/-]+"))
                throw new IllegalArgumentException(where + "số lô bắt buộc, tối đa 50 ký tự (chữ, số, . _ / -).");
            if (line.quantity() <= 0 || line.quantity() > 100_000) throw new IllegalArgumentException(where + "số lượng từ 1 đến 100.000.");
            if (line.expiryDate() != null && !line.expiryDate().isAfter(today()))
                throw new IllegalArgumentException(where + "không nhập hàng đã hết hạn (hạn dùng phải sau hôm nay).");
            if (line.unitCost() != null && (line.unitCost().signum() < 0 || line.unitCost().compareTo(new BigDecimal("9999999999")) > 0))
                throw new IllegalArgumentException(where + "giá nhập không hợp lệ.");
            clean.add(new Line(line.productId(), lot, line.expiryDate(), line.quantity(), line.unitCost()));
        }
        return clean;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> receive(Receipt receipt, Integer userId) {
        List<Line> lines = validate(receipt);
        LocalDateTime now = LocalDateTime.now(clock);
        String number = "PN" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + String.format("%04d", random.nextInt(10_000));
        Integer receiptId = jdbc.queryForObject("""
                INSERT INTO stock_receipts(receipt_no, supplier, note, created_by, created_at) OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?)""",
                Integer.class, number, blankToNull(receipt.supplier()), blankToNull(receipt.note()), userId, now);
        int units = 0;
        for (Line line : lines) {
            // the product row is locked by the update, in the same order as the lines
            int updated = jdbc.update("UPDATE products SET stock_quantity = stock_quantity + ? WHERE id = ?", line.quantity(), line.productId());
            if (updated == 0) throw new IllegalArgumentException("Không tìm thấy sản phẩm #" + line.productId() + ".");
            jdbc.update("""
                    INSERT INTO stock_lots(product_id, receipt_id, lot_no, expiry_date, quantity_received, quantity_remaining, unit_cost, is_demo, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)""", line.productId(), receiptId, line.lotNo(),
                    line.expiryDate() == null ? null : Date.valueOf(line.expiryDate()), line.quantity(), line.quantity(), line.unitCost(), now);
            units += line.quantity();
        }
        return Map.of("success", true, "receipt_no", number, "lines", lines.size(), "units", units);
    }

    /** Takes what is left of a lot out of stock (expired, damaged); the reason is kept on the lot. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispose(int lotId, String reason, Integer userId) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 200) throw new IllegalArgumentException("Ghi lý do xuất hủy (tối đa 200 ký tự).");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT product_id, quantity_remaining FROM stock_lots WITH (UPDLOCK, ROWLOCK) WHERE id = ?", lotId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Không tìm thấy lô hàng.");
        int quantity = ((Number) rows.get(0).get("quantity_remaining")).intValue();
        if (quantity <= 0) throw new IllegalArgumentException("Lô này đã hết hàng.");
        int product = ((Number) rows.get(0).get("product_id")).intValue();
        jdbc.update("""
                UPDATE stock_lots SET disposed_quantity = disposed_quantity + quantity_remaining, quantity_remaining = 0,
                       disposed_at = ?, disposed_by = ?, dispose_reason = ? WHERE id = ?""", LocalDateTime.now(clock), userId, why, lotId);
        jdbc.update("UPDATE products SET stock_quantity = stock_quantity - ? WHERE id = ?", quantity, product);
        return Map.of("success", true, "disposed", quantity);
    }

    // ---- what the manager sees ---------------------------------------------------------------------------------
    public Map<String, Object> overview() {
        Date today = Date.valueOf(today()), warn = Date.valueOf(today().plusDays(EXPIRY_WARNING_DAYS));
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT
                  COALESCE(SUM(CASE WHEN l.quantity_remaining > 0 AND l.expiry_date >= ? AND l.expiry_date <= ? THEN 1 ELSE 0 END), 0) AS expiringLots,
                  COALESCE(SUM(CASE WHEN l.quantity_remaining > 0 AND l.expiry_date >= ? AND l.expiry_date <= ? THEN l.quantity_remaining ELSE 0 END), 0) AS expiringUnits,
                  COALESCE(SUM(CASE WHEN l.quantity_remaining > 0 AND l.expiry_date < ? THEN 1 ELSE 0 END), 0) AS expiredLots,
                  COALESCE(SUM(CASE WHEN l.quantity_remaining > 0 AND l.expiry_date < ? THEN l.quantity_remaining ELSE 0 END), 0) AS expiredUnits,
                  COALESCE(SUM(l.quantity_remaining * COALESCE(l.unit_cost, 0)), 0) AS stockValue,
                  COALESCE(SUM(CASE WHEN l.is_demo = 1 AND l.quantity_remaining > 0 THEN 1 ELSE 0 END), 0) AS demoLots
                FROM stock_lots l""", today, warn, today, warn, today, today);
        List<Map<String, Object>> expiring = jdbc.queryForList("""
                SELECT TOP (200) l.id, l.lot_no AS lotNo, l.expiry_date AS expiryDate, l.quantity_remaining AS remaining, l.is_demo AS demo,
                       p.id AS productId, p.product_name AS productName, p.barcode, DATEDIFF(day, ?, l.expiry_date) AS daysLeft
                FROM stock_lots l JOIN products p ON p.id = l.product_id
                WHERE l.quantity_remaining > 0 AND l.expiry_date IS NOT NULL AND l.expiry_date <= ?
                ORDER BY l.expiry_date, l.id""", today, warn);
        List<Map<String, Object>> low = jdbc.queryForList("""
                SELECT p.id AS productId, p.product_name AS productName, p.barcode, p.stock_quantity AS stock,
                       COALESCE(s.sellable, 0) AS sellable
                FROM products p LEFT JOIN (""" + SELLABLE_SQL + """
                ) s ON s.product_id = p.id
                WHERE p.is_active = 1 AND COALESCE(s.sellable, 0) <= ?
                ORDER BY COALESCE(s.sellable, 0), p.id""", LOW_STOCK);
        summary.put("lowStockProducts", low.size());
        return Map.of("summary", summary, "expiring", expiring, "lowStock", low,
                "rules", Map.of("expiry_warning_days", EXPIRY_WARNING_DAYS, "low_stock", LOW_STOCK));
    }

    public List<Map<String, Object>> lots(String search) {
        String text = search == null ? "" : search.trim();
        if (text.length() > 100) throw new IllegalArgumentException("Từ khóa tối đa 100 ký tự.");
        String pattern = "%" + text.replace("~", "~~").replace("%", "~%").replace("_", "~_").replace("[", "~[") + "%";
        return jdbc.queryForList("""
                SELECT TOP (300) l.id, l.lot_no AS lotNo, l.expiry_date AS expiryDate, l.quantity_received AS received,
                       l.quantity_remaining AS remaining, l.disposed_quantity AS disposed, l.unit_cost AS unitCost, l.is_demo AS demo,
                       l.received_at AS receivedAt, r.receipt_no AS receiptNo, p.id AS productId, p.product_name AS productName,
                       p.barcode, DATEDIFF(day, CAST(SYSDATETIME() AS DATE), l.expiry_date) AS daysLeft
                FROM stock_lots l JOIN products p ON p.id = l.product_id LEFT JOIN stock_receipts r ON r.id = l.receipt_id
                WHERE (? = '' OR p.product_name LIKE ? ESCAPE '~' OR p.barcode LIKE ? ESCAPE '~' OR l.lot_no LIKE ? ESCAPE '~')
                ORDER BY CASE WHEN l.quantity_remaining > 0 THEN 0 ELSE 1 END, CASE WHEN l.expiry_date IS NULL THEN 1 ELSE 0 END,
                         l.expiry_date, l.id""", text, pattern, pattern, pattern);
    }

    public List<Map<String, Object>> receipts() {
        return jdbc.queryForList("""
                SELECT TOP (20) r.id, r.receipt_no AS receiptNo, r.supplier, r.note, r.created_at AS createdAt,
                       u.full_name AS createdBy, COUNT(l.id) AS lines, COALESCE(SUM(l.quantity_received), 0) AS units,
                       COALESCE(SUM(l.quantity_received * COALESCE(l.unit_cost, 0)), 0) AS value
                FROM stock_receipts r LEFT JOIN users u ON u.id = r.created_by LEFT JOIN stock_lots l ON l.receipt_id = r.id
                GROUP BY r.id, r.receipt_no, r.supplier, r.note, r.created_at, u.full_name
                ORDER BY r.created_at DESC, r.id DESC""");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
