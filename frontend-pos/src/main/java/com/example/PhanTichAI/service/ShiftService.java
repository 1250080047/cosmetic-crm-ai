package com.example.PhanTichAI.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cashier shifts: every POS sale belongs to the seller's open shift, and closing a shift produces its report. */
@Service
public class ShiftService {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal MAX_CASH = new BigDecimal("1000000000000");
    private final JdbcTemplate jdbc;

    public ShiftService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** The open shift of a user, or null. Locks the row so a concurrent close waits for the sale. */
    public Integer openShiftId(int userId) {
        var ids = jdbc.queryForList("SELECT id FROM shifts WITH (UPDLOCK,ROWLOCK) WHERE cashier_id=? AND status='OPEN'", Integer.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Integer requireOpenShift(int userId) {
        Integer id = openShiftId(userId);
        if (id == null) throw new IllegalArgumentException("Bạn chưa mở ca. Bấm “Mở ca” trước khi bán hàng.");
        return id;
    }

    public Map<String, Object> current(int userId) {
        var rows = jdbc.queryForList("SELECT id FROM shifts WHERE cashier_id=? AND status='OPEN'", Integer.class, userId);
        return rows.isEmpty() ? null : report(rows.get(0));
    }

    @Transactional
    public Map<String, Object> open(int userId, BigDecimal openingCash) {
        BigDecimal cash = validCash(openingCash == null ? BigDecimal.ZERO : openingCash, "Tiền đầu ca");
        if (openShiftId(userId) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Bạn đang có một ca chưa kết thúc.");
        jdbc.update("INSERT INTO shifts(cashier_id,opened_at,opening_cash,status) VALUES(?,?,?,'OPEN')", userId, now(), cash);
        return report(openShiftId(userId));
    }

    /** Close the caller's own open shift (cashier) or any open shift (manager/admin when shiftId is given). */
    @Transactional
    public Map<String, Object> close(Integer shiftId, int actorId, boolean manager, BigDecimal countedCash, String note) {
        Integer id = shiftId;
        if (id == null) id = openShiftId(actorId);
        if (id == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có ca đang mở để kết thúc.");
        var rows = jdbc.queryForList("SELECT cashier_id,status FROM shifts WITH (UPDLOCK,ROWLOCK) WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        if (!manager && ((Number) rows.get(0).get("cashier_id")).intValue() != actorId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ được kết thúc ca của mình.");
        if (!"OPEN".equals(rows.get(0).get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ca này đã kết thúc.");
        BigDecimal counted = countedCash == null ? null : validCash(countedCash, "Tiền mặt đếm được");
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 500) throw new IllegalArgumentException("Ghi chú tối đa 500 ký tự.");
        jdbc.update("UPDATE shifts SET status='CLOSED',closed_at=?,counted_cash=?,note=?,closed_by=? WHERE id=?",
                now(), counted, cleanNote, actorId, id);
        return report(id);
    }

    public Map<String, Object> reportFor(int shiftId, int actorId, boolean manager) {
        var owners = jdbc.queryForList("SELECT cashier_id FROM shifts WHERE id=?", Integer.class, shiftId);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        if (!manager && owners.get(0) != actorId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ xem được ca của mình.");
        return report(shiftId);
    }

    public List<Map<String, Object>> list(String from, String to, Integer cashierId) {
        return jdbc.queryForList("""
                SELECT s.id,s.status,s.opened_at AS openedAt,s.closed_at AS closedAt,s.opening_cash AS openingCash,
                       s.counted_cash AS countedCash,u.username,u.full_name AS cashierName,
                       COUNT(o.transaction_no) AS orders,COALESCE(SUM(o.final_amount),0) AS revenue,
                       COALESCE(SUM(CASE WHEN o.payment_method='CASH' THEN o.final_amount ELSE 0 END),0) AS cashRevenue
                FROM shifts s JOIN users u ON u.id=s.cashier_id LEFT JOIN orders o ON o.shift_id=s.id
                WHERE (? IS NULL OR s.opened_at>=CAST(? AS date)) AND (? IS NULL OR s.opened_at<DATEADD(day,1,CAST(? AS date)))
                  AND (? IS NULL OR s.cashier_id=?)
                GROUP BY s.id,s.status,s.opened_at,s.closed_at,s.opening_cash,s.counted_cash,u.username,u.full_name
                ORDER BY s.opened_at DESC
                OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY
                """, blank(from), blank(from), blank(to), blank(to), cashierId, cashierId);
    }

    public Map<String, Object> report(int shiftId) {
        var shifts = jdbc.queryForList("""
                SELECT s.id,s.status,s.opened_at AS openedAt,s.closed_at AS closedAt,s.opening_cash AS openingCash,
                       s.counted_cash AS countedCash,s.note,u.id AS cashierId,u.username,u.full_name AS cashierName,
                       c.full_name AS closedByName
                FROM shifts s JOIN users u ON u.id=s.cashier_id LEFT JOIN users c ON c.id=s.closed_by WHERE s.id=?
                """, shiftId);
        if (shifts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        Map<String, Object> shift = shifts.get(0);
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT COUNT(*) AS orders,COALESCE(SUM(total_amount),0) AS subtotal,COALESCE(SUM(discount_amount),0) AS discount,
                       COALESCE(SUM(final_amount),0) AS revenue,COUNT(DISTINCT customer_id) AS members,
                       SUM(CASE WHEN customer_id IS NULL THEN 1 ELSE 0 END) AS walkIns,
                       COALESCE(SUM(CASE WHEN applied_strategy_id IS NOT NULL THEN 1 ELSE 0 END),0) AS ordersWithOffer
                FROM orders WHERE shift_id=?
                """, shiftId);
        var payments = jdbc.queryForList("""
                SELECT CASE WHEN payment_method IN ('TRANSFER','QR') THEN 'BANK_TRANSFER' ELSE payment_method END AS method,COUNT(*) AS orders,SUM(final_amount) AS revenue
                FROM orders WHERE shift_id=? GROUP BY CASE WHEN payment_method IN ('TRANSFER','QR') THEN 'BANK_TRANSFER' ELSE payment_method END ORDER BY revenue DESC
                """, shiftId);
        var gifts = jdbc.queryForList("""
                SELECT p.id AS productId,p.product_name AS name,SUM(i.quantity) AS quantity,
                       SUM(i.quantity*COALESCE(i.list_price,p.unit_price)) AS retailValue,COUNT(DISTINCT i.transaction_no) AS orders
                FROM order_items i JOIN orders o ON o.transaction_no=i.transaction_no JOIN products p ON p.id=i.product_id
                WHERE o.shift_id=? AND i.unit_price=0 GROUP BY p.id,p.product_name ORDER BY quantity DESC,p.id
                """, shiftId);
        var products = jdbc.queryForList("""
                SELECT TOP (20) p.id AS productId,p.product_name AS name,SUM(i.quantity) AS quantity,SUM(i.revenue) AS revenue
                FROM order_items i JOIN orders o ON o.transaction_no=i.transaction_no JOIN products p ON p.id=i.product_id
                WHERE o.shift_id=? AND i.unit_price>0 GROUP BY p.id,p.product_name ORDER BY quantity DESC,revenue DESC
                """, shiftId);
        var orders = jdbc.queryForList("""
                SELECT o.transaction_no AS transactionNo,o.order_date AS orderDate,c.customer_code AS customerCode,
                       c.full_name AS customerName,o.total_amount AS subtotal,o.discount_amount AS discount,
                       o.final_amount AS revenue,o.payment_method AS paymentMethod,
                       (SELECT COALESCE(SUM(quantity),0) FROM order_items WHERE transaction_no=o.transaction_no AND unit_price=0) AS giftUnits
                FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id WHERE o.shift_id=? ORDER BY o.order_date
                """, shiftId);
        BigDecimal opening = decimal(shift.get("openingCash"));
        BigDecimal cash = payments.stream().filter(row -> "CASH".equals(row.get("method"))).map(row -> decimal(row.get("revenue")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = opening.add(cash).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> cashBox = new LinkedHashMap<>();
        cashBox.put("opening", opening);
        cashBox.put("cashSales", cash);
        cashBox.put("expected", expected);
        cashBox.put("counted", shift.get("countedCash"));
        cashBox.put("difference", shift.get("countedCash") == null ? null : decimal(shift.get("countedCash")).subtract(expected));
        Map<String, Object> giftTotals = new LinkedHashMap<>();
        giftTotals.put("units", gifts.stream().mapToLong(row -> ((Number) row.get("quantity")).longValue()).sum());
        giftTotals.put("retailValue", gifts.stream().map(row -> decimal(row.get("retailValue"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shift", shift);
        result.put("summary", summary);
        result.put("payments", payments);
        result.put("cash", cashBox);
        result.put("gifts", gifts);
        result.put("giftTotals", giftTotals);
        result.put("products", products);
        result.put("orders", orders);
        result.put("generatedAt", now());
        return result;
    }

    private static BigDecimal validCash(BigDecimal value, String label) {
        if (value.signum() < 0 || value.compareTo(MAX_CASH) >= 0) throw new IllegalArgumentException(label + " không hợp lệ.");
        return value.setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static LocalDateTime now() { return LocalDateTime.now(STORE_ZONE); }
}
