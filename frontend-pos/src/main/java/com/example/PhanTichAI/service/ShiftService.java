package com.example.PhanTichAI.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Cashier shifts: every POS sale belongs to the seller's open shift, and closing a shift produces its report.
 *
 * <p>Cash reconciliation at the end of a shift (as in real stores):</p>
 * <ul>
 *   <li>cash that should be in the drawer = opening cash + cash sales + cash brought in − cash taken out during the
 *       shift (cash movements with a reason, e.g. buying water, handing cash to the main safe);</li>
 *   <li>the cashier must count the drawer by denomination; a difference within ±{@value #TOLERANCE_VND} đ (small change)
 *       closes the shift normally;</li>
 *   <li>beyond that the cashier must write a reason and the shift waits for a manager or an admin, who may fix a payment
 *       method entered by mistake (history kept) and records the decision: the cashier repays / the store bears the
 *       shortage, a surplus goes to the fund, or "corrected" when no difference is left.</li>
 * </ul>
 */
@Service
public class ShiftService {
    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final long TOLERANCE_VND = 10_000;
    public static final BigDecimal TOLERANCE = BigDecimal.valueOf(TOLERANCE_VND);
    public static final List<Integer> DENOMINATIONS = List.of(500_000, 200_000, 100_000, 50_000, 20_000, 10_000, 5_000, 2_000, 1_000, 500);
    public static final String PENDING = "PENDING", RESOLVED = "RESOLVED";
    public static final Map<String, String> DECISIONS = Map.of(
            "STAFF_REPAY", "Nhân viên bồi hoàn phần thiếu", "STORE_EXPENSE", "Cửa hàng chịu phần thiếu (ghi chi phí)",
            "ADD_TO_FUND", "Nộp phần dư vào quỹ (thu khác)", "CORRECTED", "Đã tìm ra nguyên nhân và sửa, không còn lệch");
    private static final Set<String> METHODS = Set.of("CASH", "BANK_TRANSFER", "CARD");
    private static final BigDecimal MAX_CASH = new BigDecimal("1000000000000");
    private static final BigDecimal MAX_MOVEMENT = new BigDecimal("100000000");
    private static final NumberFormat VND = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
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

    // ---- counting the drawer -----------------------------------------------------------------------------------
    public record CashCount(BigDecimal total, String json) {}

    /** Notes counted per denomination ("500000" → 2 …): the total and a compact JSON kept with the shift. */
    public static CashCount countCash(Map<String, Integer> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) return null;
        Map<Integer, Integer> counted = new TreeMap<>(Comparator.reverseOrder());
        breakdown.forEach((key, value) -> {
            int denomination;
            try { denomination = Integer.parseInt(key.trim()); } catch (NumberFormatException bad) { throw new IllegalArgumentException("Mệnh giá không hợp lệ: " + key); }
            if (!DENOMINATIONS.contains(denomination)) throw new IllegalArgumentException("Mệnh giá không hợp lệ: " + key);
            int pieces = value == null ? 0 : value;
            if (pieces < 0 || pieces > 100_000) throw new IllegalArgumentException("Số tờ mệnh giá " + VND.format(denomination) + " không hợp lệ.");
            if (pieces > 0) counted.merge(denomination, pieces, Integer::sum);
        });
        BigDecimal total = BigDecimal.ZERO;
        StringJoiner json = new StringJoiner(",", "{", "}");
        for (var entry : counted.entrySet()) {
            total = total.add(BigDecimal.valueOf((long) entry.getKey() * entry.getValue()));
            json.add("\"" + entry.getKey() + "\":" + entry.getValue());
        }
        return new CashCount(total.setScale(2, RoundingMode.HALF_UP), json.toString());
    }

    /** Opening cash + cash sales + cash brought in − cash taken out. */
    public BigDecimal expectedCash(int shiftId) {
        BigDecimal expected = jdbc.queryForObject("""
                SELECT s.opening_cash
                     + COALESCE((SELECT SUM(o.final_amount) FROM orders o WHERE o.shift_id=s.id AND o.payment_method='CASH'),0)
                     + COALESCE((SELECT SUM(m.amount) FROM shift_cash_movements m WHERE m.shift_id=s.id AND m.kind='IN'),0)
                     - COALESCE((SELECT SUM(m.amount) FROM shift_cash_movements m WHERE m.shift_id=s.id AND m.kind='OUT'),0)
                FROM shifts s WHERE s.id=?""", BigDecimal.class, shiftId);
        return (expected == null ? BigDecimal.ZERO : expected).setScale(2, RoundingMode.HALF_UP);
    }

    /** Close the caller's own open shift (cashier) or any open shift (manager/admin when shiftId is given). */
    @Transactional
    public Map<String, Object> close(Integer shiftId, int actorId, boolean manager, BigDecimal countedCash, String note) {
        return close(shiftId, actorId, manager, countedCash, null, note, note);
    }

    @Transactional
    public Map<String, Object> close(Integer shiftId, int actorId, boolean manager, BigDecimal countedCash,
                                     Map<String, Integer> breakdown, String note, String reason) {
        Integer id = shiftId;
        if (id == null) id = openShiftId(actorId);
        if (id == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có ca đang mở để kết thúc.");
        var rows = jdbc.queryForList("SELECT cashier_id,status FROM shifts WITH (UPDLOCK,ROWLOCK) WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        if (!manager && ((Number) rows.get(0).get("cashier_id")).intValue() != actorId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ được kết thúc ca của mình.");
        if (!"OPEN".equals(rows.get(0).get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ca này đã kết thúc.");
        CashCount count = countCash(breakdown);
        BigDecimal counted = count != null ? count.total() : countedCash == null ? null : validCash(countedCash, "Tiền mặt đếm được");
        if (counted == null) throw new IllegalArgumentException("Hãy đếm tiền mặt trong két theo từng mệnh giá trước khi kết ca.");
        String cleanNote = text(note, 500, "Ghi chú");
        String why = text(reason, 500, "Lý do chênh lệch");
        BigDecimal expected = expectedCash(id), difference = counted.subtract(expected);
        boolean outside = difference.abs().compareTo(TOLERANCE) > 0;
        if (outside && (why == null || why.length() < 10))
            throw new IllegalArgumentException("Tiền mặt " + (difference.signum() < 0 ? "thiếu " : "dư ") + VND.format(difference.abs()) + " đ (quá ±"
                    + VND.format(TOLERANCE) + " đ). Ghi lý do (ít nhất 10 ký tự); ca sẽ chờ quản lý xác nhận.");
        jdbc.update("""
                UPDATE shifts SET status='CLOSED',closed_at=?,counted_cash=?,cash_breakdown=?,expected_cash=?,cash_difference=?,
                       discrepancy_reason=?,review_status=?,note=?,closed_by=? WHERE id=?""",
                now(), counted, count == null ? null : count.json(), expected, difference, outside ? why : null,
                outside ? PENDING : null, cleanNote, actorId, id);
        return report(id);
    }

    // ---- cash brought in / taken out during the shift ----------------------------------------------------------
    @Transactional
    public Map<String, Object> addMovement(int actorId, String kind, BigDecimal amount, String reason) {
        Integer id = openShiftId(actorId);
        if (id == null) throw new IllegalArgumentException("Bạn chưa mở ca.");
        if (!"IN".equals(kind) && !"OUT".equals(kind)) throw new IllegalArgumentException("Chọn thu vào hoặc chi ra.");
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_MOVEMENT) > 0)
            throw new IllegalArgumentException("Số tiền phải lớn hơn 0 và không quá 100.000.000 đ.");
        String why = text(reason, 200, "Lý do");
        if (why == null || why.length() < 3) throw new IllegalArgumentException("Ghi lý do thu / chi (VD: Chi mua nước, Nộp tiền về két chính).");
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        if ("OUT".equals(kind)) {
            BigDecimal inDrawer = expectedCash(id);
            if (value.compareTo(inDrawer) > 0) throw new IllegalArgumentException("Trong két chỉ có " + VND.format(inDrawer) + " đ.");
        }
        jdbc.update("INSERT INTO shift_cash_movements(shift_id,kind,amount,reason,created_by,created_at) VALUES(?,?,?,?,?,?)",
                id, kind, value, why, actorId, now());
        return report(id);
    }

    // ---- manager / admin review --------------------------------------------------------------------------------
    /** Decisions that fit the current difference of a shift waiting for review. */
    public static List<String> allowedDecisions(BigDecimal difference) {
        if (difference == null) return List.of("CORRECTED", "STAFF_REPAY", "STORE_EXPENSE", "ADD_TO_FUND");
        if (difference.abs().compareTo(TOLERANCE) <= 0) return List.of("CORRECTED");
        return difference.signum() < 0 ? List.of("STAFF_REPAY", "STORE_EXPENSE") : List.of("ADD_TO_FUND");
    }

    @Transactional
    public Map<String, Object> review(int shiftId, int actorId, String decision, String note) {
        var rows = jdbc.queryForList("SELECT status,review_status,counted_cash FROM shifts WITH (UPDLOCK,ROWLOCK) WHERE id=?", shiftId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        if (!"CLOSED".equals(rows.get(0).get("status")) || !PENDING.equals(rows.get(0).get("review_status")))
            throw new IllegalArgumentException("Ca này không chờ xác nhận chênh lệch.");
        BigDecimal counted = rows.get(0).get("counted_cash") == null ? null : decimal(rows.get(0).get("counted_cash"));
        BigDecimal expected = expectedCash(shiftId), difference = counted == null ? null : counted.subtract(expected);
        if (!allowedDecisions(difference).contains(decision))
            throw new IllegalArgumentException("Cách xử lý không phù hợp với chênh lệch hiện tại"
                    + (difference == null ? "." : " (" + (difference.signum() < 0 ? "thiếu " : "dư ") + VND.format(difference.abs()) + " đ)."));
        String why = text(note, 500, "Ghi chú xác nhận");
        if (why == null || why.length() < 5) throw new IllegalArgumentException("Ghi rõ đã kiểm tra gì và quyết định thế nào (ít nhất 5 ký tự).");
        jdbc.update("""
                UPDATE shifts SET review_status=?,review_decision=?,review_note=?,reviewed_by=?,reviewed_at=?,expected_cash=?,cash_difference=?
                WHERE id=?""", RESOLVED, decision, why, actorId, now(), expected, difference, shiftId);
        return report(shiftId);
    }

    /** Fixes a payment method entered by mistake in a shift waiting for review; the change is kept in a history. */
    @Transactional
    public Map<String, Object> correctPayment(String transactionNo, String newMethod, String reason, int actorId) {
        var rows = jdbc.queryForList("""
                SELECT o.shift_id,o.payment_method,s.review_status FROM orders o WITH (UPDLOCK,ROWLOCK)
                LEFT JOIN shifts s ON s.id=o.shift_id WHERE o.transaction_no=?""", transactionNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("Không tìm thấy hóa đơn.");
        Object shift = rows.get(0).get("shift_id");
        if (shift == null || !PENDING.equals(rows.get(0).get("review_status")))
            throw new IllegalArgumentException("Chỉ sửa phương thức thanh toán của hóa đơn thuộc ca đang chờ xác nhận chênh lệch.");
        String old = String.valueOf(rows.get(0).get("payment_method"));
        if (!METHODS.contains(newMethod)) throw new IllegalArgumentException("Phương thức thanh toán không hợp lệ.");
        if (newMethod.equals(old)) throw new IllegalArgumentException("Hóa đơn đang dùng phương thức này.");
        String why = text(reason, 300, "Lý do sửa");
        if (why == null || why.length() < 5) throw new IllegalArgumentException("Ghi lý do sửa (VD: Khách chuyển khoản, thu ngân bấm nhầm tiền mặt).");
        int shiftId = ((Number) shift).intValue();
        jdbc.update("INSERT INTO order_payment_changes(transaction_no,shift_id,old_method,new_method,reason,changed_by,changed_at) VALUES(?,?,?,?,?,?,?)",
                transactionNo, shiftId, old, newMethod, why, actorId, now());
        jdbc.update("UPDATE orders SET payment_method=? WHERE transaction_no=?", newMethod, transactionNo);
        jdbc.update("""
                UPDATE shifts SET expected_cash=?, cash_difference=CASE WHEN counted_cash IS NULL THEN NULL ELSE counted_cash-? END WHERE id=?""",
                expectedCash(shiftId), expectedCash(shiftId), shiftId);
        return report(shiftId);
    }

    // ---- lists and reports -------------------------------------------------------------------------------------
    public Map<String, Object> reportFor(int shiftId, int actorId, boolean manager) {
        var owners = jdbc.queryForList("SELECT cashier_id FROM shifts WHERE id=?", Integer.class, shiftId);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ca làm việc.");
        if (!manager && owners.get(0) != actorId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ xem được ca của mình.");
        return report(shiftId);
    }

    public List<Map<String, Object>> list(String from, String to, Integer cashierId) { return list(from, to, cashierId, null); }

    public List<Map<String, Object>> list(String from, String to, Integer cashierId, String review) {
        String reviewFilter = "PENDING".equals(review) ? PENDING : "RESOLVED".equals(review) ? RESOLVED : null;
        return jdbc.queryForList("""
                SELECT s.id,s.status,s.opened_at AS openedAt,s.closed_at AS closedAt,s.opening_cash AS openingCash,
                       s.counted_cash AS countedCash,s.expected_cash AS expectedCash,s.cash_difference AS cashDifference,
                       s.review_status AS reviewStatus,s.review_decision AS reviewDecision,u.username,u.full_name AS cashierName,
                       COUNT(o.transaction_no) AS orders,COALESCE(SUM(o.final_amount),0) AS revenue,
                       COALESCE(SUM(CASE WHEN o.payment_method='CASH' THEN o.final_amount ELSE 0 END),0) AS cashRevenue
                FROM shifts s JOIN users u ON u.id=s.cashier_id LEFT JOIN orders o ON o.shift_id=s.id
                WHERE (? IS NULL OR s.opened_at>=CAST(? AS date)) AND (? IS NULL OR s.opened_at<DATEADD(day,1,CAST(? AS date)))
                  AND (? IS NULL OR s.cashier_id=?) AND (? IS NULL OR s.review_status=?)
                GROUP BY s.id,s.status,s.opened_at,s.closed_at,s.opening_cash,s.counted_cash,s.expected_cash,s.cash_difference,
                         s.review_status,s.review_decision,u.username,u.full_name
                ORDER BY CASE WHEN s.review_status='PENDING' THEN 0 ELSE 1 END, s.opened_at DESC
                OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY
                """, blank(from), blank(from), blank(to), blank(to), cashierId, cashierId, reviewFilter, reviewFilter);
    }

    /** Per cashier: shifts closed, shifts outside the tolerance, total shortage / surplus, shifts still waiting. */
    public List<Map<String, Object>> discrepancies(String from, String to) {
        return jdbc.queryForList("""
                SELECT u.id AS cashierId,u.full_name AS cashierName,u.username,COUNT(*) AS shifts,
                       SUM(CASE WHEN ABS(COALESCE(s.cash_difference,0))>? THEN 1 ELSE 0 END) AS outsideTolerance,
                       COALESCE(SUM(CASE WHEN s.cash_difference<0 THEN s.cash_difference ELSE 0 END),0) AS shortage,
                       COALESCE(SUM(CASE WHEN s.cash_difference>0 THEN s.cash_difference ELSE 0 END),0) AS surplus,
                       SUM(CASE WHEN s.review_status='PENDING' THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN s.review_decision='STAFF_REPAY' THEN 1 ELSE 0 END) AS repaid
                FROM shifts s JOIN users u ON u.id=s.cashier_id
                WHERE s.status='CLOSED' AND (? IS NULL OR s.opened_at>=CAST(? AS date)) AND (? IS NULL OR s.opened_at<DATEADD(day,1,CAST(? AS date)))
                GROUP BY u.id,u.full_name,u.username
                ORDER BY SUM(CASE WHEN ABS(COALESCE(s.cash_difference,0))>? THEN 1 ELSE 0 END) DESC, u.full_name
                """, TOLERANCE, blank(from), blank(from), blank(to), blank(to), TOLERANCE);
    }

    public Map<String, Object> report(int shiftId) {
        var shifts = jdbc.queryForList("""
                SELECT s.id,s.status,s.opened_at AS openedAt,s.closed_at AS closedAt,s.opening_cash AS openingCash,
                       s.counted_cash AS countedCash,s.note,s.cash_breakdown AS cashBreakdown,s.discrepancy_reason AS discrepancyReason,
                       s.review_status AS reviewStatus,s.review_decision AS reviewDecision,s.review_note AS reviewNote,s.reviewed_at AS reviewedAt,
                       u.id AS cashierId,u.username,u.full_name AS cashierName,c.full_name AS closedByName,r.full_name AS reviewedByName
                FROM shifts s JOIN users u ON u.id=s.cashier_id LEFT JOIN users c ON c.id=s.closed_by LEFT JOIN users r ON r.id=s.reviewed_by
                WHERE s.id=?
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
        var movements = jdbc.queryForList("""
                SELECT m.id,m.kind,m.amount,m.reason,m.created_at AS createdAt,u.full_name AS createdBy
                FROM shift_cash_movements m JOIN users u ON u.id=m.created_by WHERE m.shift_id=? ORDER BY m.created_at,m.id
                """, shiftId);
        var paymentChanges = jdbc.queryForList("""
                SELECT p.transaction_no AS transactionNo,p.old_method AS oldMethod,p.new_method AS newMethod,p.reason,
                       p.changed_at AS changedAt,u.full_name AS changedBy
                FROM order_payment_changes p LEFT JOIN users u ON u.id=p.changed_by WHERE p.shift_id=? ORDER BY p.changed_at,p.id
                """, shiftId);
        BigDecimal opening = decimal(shift.get("openingCash"));
        BigDecimal cash = payments.stream().filter(row -> "CASH".equals(row.get("method"))).map(row -> decimal(row.get("revenue")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashIn = movements.stream().filter(row -> "IN".equals(row.get("kind"))).map(row -> decimal(row.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashOut = movements.stream().filter(row -> "OUT".equals(row.get("kind"))).map(row -> decimal(row.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = opening.add(cash).add(cashIn).subtract(cashOut).setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = shift.get("countedCash") == null ? null : decimal(shift.get("countedCash")).subtract(expected);
        Map<String, Object> cashBox = new LinkedHashMap<>();
        cashBox.put("opening", opening);
        cashBox.put("cashSales", cash);
        cashBox.put("cashIn", cashIn);
        cashBox.put("cashOut", cashOut);
        cashBox.put("expected", expected);
        cashBox.put("counted", shift.get("countedCash"));
        cashBox.put("difference", difference);
        cashBox.put("tolerance", TOLERANCE);
        cashBox.put("withinTolerance", difference != null && difference.abs().compareTo(TOLERANCE) <= 0);
        cashBox.put("breakdown", shift.get("cashBreakdown"));
        cashBox.put("denominations", DENOMINATIONS);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", shift.get("reviewStatus"));
        review.put("reason", shift.get("discrepancyReason"));
        review.put("decision", shift.get("reviewDecision"));
        review.put("decisionLabel", shift.get("reviewDecision") == null ? null : DECISIONS.get(String.valueOf(shift.get("reviewDecision"))));
        review.put("note", shift.get("reviewNote"));
        review.put("reviewedBy", shift.get("reviewedByName"));
        review.put("reviewedAt", shift.get("reviewedAt"));
        if (PENDING.equals(shift.get("reviewStatus"))) {
            List<Map<String, String>> options = new ArrayList<>();
            for (String decision : allowedDecisions(difference)) options.add(Map.of("value", decision, "label", DECISIONS.get(decision)));
            review.put("options", options);
        }
        Map<String, Object> giftTotals = new LinkedHashMap<>();
        giftTotals.put("units", gifts.stream().mapToLong(row -> ((Number) row.get("quantity")).longValue()).sum());
        giftTotals.put("retailValue", gifts.stream().map(row -> decimal(row.get("retailValue"))).reduce(BigDecimal.ZERO, BigDecimal::add));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shift", shift);
        result.put("summary", summary);
        result.put("payments", payments);
        result.put("cash", cashBox);
        result.put("movements", movements);
        result.put("review", review);
        result.put("paymentChanges", paymentChanges);
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
    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() > max) throw new IllegalArgumentException(label + " tối đa " + max + " ký tự.");
        return clean;
    }
    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static LocalDateTime now() { return LocalDateTime.now(STORE_ZONE); }
}
