package com.example.PhanTichAI.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class RevenueService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public RevenueService(JdbcTemplate jdbc) { this(jdbc, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))); }
    RevenueService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public record Window(LocalDateTime start, LocalDateTime end, LocalDateTime previousStart,
                         LocalDateTime previousEnd, boolean partial, boolean future) {}
    public record Totals(long orders, BigDecimal gross, BigDecimal discount, BigDecimal revenue, long customers, long anonymousOrders) {
        public BigDecimal average() { return orders == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(orders), 8, RoundingMode.HALF_UP); }
        public BigDecimal discountRate() { return gross.signum() == 0 ? BigDecimal.ZERO : discount.multiply(BigDecimal.valueOf(100)).divide(gross, 4, RoundingMode.HALF_UP); }
    }
    public record Comparison(BigDecimal revenueDelta, BigDecimal revenueChangePercent,
                             BigDecimal orderEffect, BigDecimal basketEffect, BigDecimal discountRateChange) {}

    public static Window window(String granularity, int year, int month, String comparison, LocalDateTime now) {
        if (!Set.of("month", "year").contains(granularity) || !Set.of("previous", "year").contains(comparison)
                || year < 1900 || year > 9998 || month < 1 || month > 12)
            throw new ResponseStatusException(BAD_REQUEST, "Kỳ thống kê không hợp lệ.");
        boolean monthly = granularity.equals("month");
        LocalDateTime start = LocalDate.of(year, monthly ? month : 1, 1).atStartOfDay();
        LocalDateTime nominalEnd = monthly ? start.plusMonths(1) : start.plusYears(1);
        boolean previousMonth = monthly && comparison.equals("previous");
        LocalDateTime previousStart = previousMonth ? start.minusMonths(1) : start.minusYears(1);
        LocalDateTime previousEnd = previousMonth ? start : nominalEnd.minusYears(1);
        boolean partial = !now.isBefore(start) && now.isBefore(nominalEnd);
        boolean future = now.isBefore(start);
        LocalDateTime end = partial ? now : future ? start : nominalEnd;
        if (partial) previousEnd = previousMonth ? now.minusMonths(1) : now.minusYears(1);
        if (future) previousEnd = previousStart;
        return new Window(start, end, previousStart, previousEnd, partial, future);
    }
    public static Comparison compare(Totals current, Totals previous) {
        BigDecimal delta = current.revenue().subtract(previous.revenue());
        BigDecimal percent = previous.revenue().signum() == 0 ? null : delta.multiply(BigDecimal.valueOf(100)).divide(previous.revenue(), 4, RoundingMode.HALF_UP);
        BigDecimal orderEffect = BigDecimal.valueOf(current.orders() - previous.orders()).multiply(previous.average());
        // The residual keeps the two effects exactly equal to the observed revenue change.
        BigDecimal basketEffect = delta.subtract(orderEffect);
        return new Comparison(delta, percent, orderEffect, basketEffect, current.discountRate().subtract(previous.discountRate()));
    }
    public Map<String, Object> options() {
        var bounds = jdbc.queryForMap("SELECT MIN(YEAR(order_date)) AS firstYear,MAX(YEAR(order_date)) AS lastYear,MAX(order_date) AS lastOrder FROM orders");
        int current = LocalDate.now(clock).getYear();
        int first = bounds.get("firstYear") instanceof Number n ? n.intValue() : current;
        int last = bounds.get("lastYear") instanceof Number n ? n.intValue() : current;
        List<Integer> years = new ArrayList<>();
        for (int y = Math.max(current, last); y >= Math.min(current, first); y--) years.add(y);
        Map<String, Object> result = new LinkedHashMap<>(bounds); result.put("years", years); result.put("currentYear", current);
        return result;
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public Map<String, Object> report(String granularity, int year, int month, String comparison) {
        LocalDateTime now = LocalDateTime.now(clock);
        Window w = window(granularity, year, month, comparison, now);
        Totals current = totals(w.start(), w.end()), previous = totals(w.previousStart(), w.previousEnd());
        Comparison changes = compare(current, previous);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", w); result.put("current", present(current)); result.put("previous", present(previous)); result.put("comparison", changes);
        List<Map<String, Object>> trend = new ArrayList<>();
        // One grouped query per year; no order/item join can multiply the order totals.
        var selectedMonths = months(year, now); var priorMonths = months(year - 1, now.minusYears(1));
        for (int m = 1; m <= 12; m++) {
            Map<String, Object> point = new LinkedHashMap<>(); point.put("month", m);
            point.put("current", selectedMonths.getOrDefault(m, BigDecimal.ZERO)); point.put("previous", priorMonths.getOrDefault(m, BigDecimal.ZERO));
            point.put("future", LocalDate.of(year, m, 1).isAfter(now.toLocalDate()));
            trend.add(point);
        }
        result.put("trend", trend); result.put("year", year);
        var currentCategories = categories(w.start(), w.end()); var previousCategories = categories(w.previousStart(), w.previousEnd());
        Set<String> keys = new LinkedHashSet<>(currentCategories.keySet()); keys.addAll(previousCategories.keySet());
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (String name : keys) {
            BigDecimal a = currentCategories.getOrDefault(name, BigDecimal.ZERO), b = previousCategories.getOrDefault(name, BigDecimal.ZERO);
            contributions.add(Map.of("name", name, "current", a, "previous", b, "delta", a.subtract(b)));
        }
        contributions.sort((a, b) -> ((BigDecimal)b.get("delta")).abs().compareTo(((BigDecimal)a.get("delta")).abs()));
        result.put("categories", contributions);
        result.put("payments", jdbc.queryForList("""
                SELECT COALESCE(CASE WHEN payment_method IN ('TRANSFER','QR') THEN 'BANK_TRANSFER' ELSE payment_method END,'Chưa ghi nhận') AS method, COUNT(*) AS orders,
                COALESCE(SUM(final_amount),0) AS revenue FROM orders
                WHERE order_date>=? AND order_date<? GROUP BY CASE WHEN payment_method IN ('TRANSFER','QR') THEN 'BANK_TRANSFER' ELSE payment_method END ORDER BY revenue DESC
                """, w.start(), w.end()));
        result.put("topProducts", jdbc.queryForList("""
                SELECT TOP 10 p.id,p.product_name AS name,SUM(i.quantity) AS quantity,
                SUM(CASE WHEN i.unit_price=0 THEN i.quantity ELSE 0 END) AS freeQuantity,
                SUM(i.revenue) AS grossRevenue
                FROM order_items i JOIN orders o ON o.transaction_no=i.transaction_no
                JOIN products p ON p.id=i.product_id WHERE o.order_date>=? AND o.order_date<?
                GROUP BY p.id,p.product_name ORDER BY grossRevenue DESC,p.id
                """, w.start(), w.end()));
        List<String> notes = new ArrayList<>();
        if (w.partial()) notes.add("Kỳ đang diễn ra: hai kỳ chỉ so đến cùng mốc ngày/giờ theo lịch, múi giờ Việt Nam; ngày cuối tháng được chặn theo độ dài tháng.");
        if (w.future()) notes.add("Kỳ đã chọn chưa bắt đầu. Chưa có số liệu để so sánh.");
        if (current.orders() == 0) notes.add("Kỳ đã chọn chưa có hóa đơn; số 0 không chứng minh cửa hàng ngừng hoạt động.");
        if (previous.orders() == 0) notes.add("Kỳ đối chiếu chưa có hóa đơn; không tính phần trăm tăng trưởng khi doanh thu gốc bằng 0.");
        int missing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders o WHERE o.order_date>=? AND o.order_date<? AND NOT EXISTS
                (SELECT 1 FROM order_items i WHERE i.transaction_no=o.transaction_no)
                """, Integer.class, w.start(), w.end());
        if (missing > 0) notes.add(missing + " hóa đơn thiếu chi tiết; doanh thu vẫn tính từ orders, phân tích hàng hóa có thể thiếu.");
        int unmatched = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders o WHERE o.order_date>=? AND o.order_date<?
                AND EXISTS(SELECT 1 FROM order_items i WHERE i.transaction_no=o.transaction_no)
                AND ABS(o.total_amount-(SELECT COALESCE(SUM(i.revenue),0) FROM order_items i WHERE i.transaction_no=o.transaction_no))>0.01
                """, Integer.class, w.start(), w.end());
        if (unmatched > 0) notes.add(unmatched + " hóa đơn có tổng chi tiết lệch tạm tính; doanh thu nhóm hàng được phân bổ theo tỷ trọng doanh số dòng hàng.");
        notes.add("Doanh thu = tổng orders.final_amount. Schema chưa có trạng thái thanh toán/hoàn trả hoặc giá vốn lịch sử; báo cáo chưa phản ánh công nợ, hoàn tiền và lợi nhuận.");
        notes.add("Nhóm hàng được phân bổ tiền sau giảm giá theo tỷ trọng order_items.revenue; nhóm hiện tại của sản phẩm có thể khác thời điểm bán. Đây là phân tích đóng góp, chưa xác nhận quan hệ nhân quả.");
        result.put("notes", notes); result.put("insights", insights(current, previous, changes, contributions));
        result.put("generatedAt", now);
        return result;
    }
    private Map<String, Object> present(Totals t) {
        return Map.of("orders", t.orders(), "gross", t.gross(), "discount", t.discount(), "revenue", t.revenue(),
                "customers", t.customers(), "anonymousOrders", t.anonymousOrders(), "average", t.average(), "discountRate", t.discountRate());
    }
    private Totals totals(LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForObject("""
                SELECT COUNT_BIG(*) AS orders,COALESCE(SUM(total_amount),0) AS gross,
                COALESCE(SUM(discount_amount),0) AS discount,COALESCE(SUM(final_amount),0) AS revenue,
                COUNT(DISTINCT customer_id) AS customers,COALESCE(SUM(CASE WHEN customer_id IS NULL THEN 1 ELSE 0 END),0) AS anonymous
                FROM orders WHERE order_date>=? AND order_date<?
                """, (rs,n) -> new Totals(rs.getLong("orders"), rs.getBigDecimal("gross"), rs.getBigDecimal("discount"), rs.getBigDecimal("revenue"), rs.getLong("customers"), rs.getLong("anonymous")), start, end);
    }
    private Map<Integer, BigDecimal> months(int year, LocalDateTime cutoff) {
        LocalDateTime start = LocalDate.of(year,1,1).atStartOfDay(), end = start.plusYears(1);
        if (cutoff.isBefore(end)) end = cutoff.isBefore(start) ? start : cutoff;
        Map<Integer,BigDecimal> values = new HashMap<>();
        jdbc.query("SELECT MONTH(order_date) AS month,SUM(final_amount) AS revenue FROM orders WHERE order_date>=? AND order_date<? GROUP BY MONTH(order_date)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(rs.getInt("month"), rs.getBigDecimal("revenue")), start,end);
        return values;
    }
    private Map<String, BigDecimal> categories(LocalDateTime start, LocalDateTime end) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        jdbc.query("""
                WITH lines AS (
                    SELECT i.transaction_no,i.product_id,i.revenue,
                    SUM(i.revenue) OVER(PARTITION BY i.transaction_no) AS line_total
                    FROM order_items i JOIN orders o ON o.transaction_no=i.transaction_no
                    WHERE o.order_date>=? AND o.order_date<?
                )
                SELECT COALESCE(c.name,N'Chưa phân loại') AS name,
                SUM(CAST(o.final_amount AS decimal(28,8))*(i.revenue/NULLIF(i.line_total,0))) AS revenue
                FROM lines i JOIN orders o ON o.transaction_no=i.transaction_no
                LEFT JOIN products p ON p.id=i.product_id LEFT JOIN categories c ON c.id=p.category_id
                GROUP BY c.name
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.merge(rs.getString("name"),
                Optional.ofNullable(rs.getBigDecimal("revenue")).orElse(BigDecimal.ZERO), BigDecimal::add), start,end);
        BigDecimal allocated = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainder = totals(start,end).revenue().subtract(allocated);
        if (remainder.abs().compareTo(new BigDecimal("0.01")) > 0) values.merge("Chưa phân bổ / thiếu chi tiết", remainder, BigDecimal::add);
        return values;
    }
    private List<Map<String,String>> insights(Totals current, Totals previous, Comparison change, List<Map<String,Object>> categories) {
        List<Map<String,String>> insights = new ArrayList<>();
        if (current.orders() == 0 || previous.orders() == 0) {
            insights.add(Map.of("title", "Chưa đủ dữ liệu so sánh", "evidence", "Một trong hai kỳ chưa có hóa đơn.", "action", "Kiểm tra khoảng thời gian và mức độ đầy đủ của dữ liệu trước khi chọn chiến lược."));
            return insights;
        }
        insights.add(Map.of("title", change.orderEffect().signum() < 0 ? "Số hóa đơn giảm" : "Đóng góp từ số hóa đơn",
                "evidence", previous.orders() + " → " + current.orders() + " đơn; đóng góp vào chênh lệch doanh thu: " + change.orderEffect().setScale(0,RoundingMode.HALF_UP) + " đ, giữ giá trị đơn ở mức kỳ gốc.",
                "action", change.orderEffect().signum() < 0 ? "Kiểm tra ngày mở cửa và nguồn khách; thử nhắc mua lại cho nhóm lâu chưa ghé và theo dõi số đơn sau chiến dịch." : "Kiểm tra nhóm khách tạo thêm đơn để duy trì nguồn khách và chuẩn bị hàng phù hợp."));
        insights.add(Map.of("title", change.basketEffect().signum() < 0 ? "Giá trị đơn trung bình giảm" : "Đóng góp từ giá trị đơn",
                "evidence", previous.average().setScale(0,RoundingMode.HALF_UP) + " → " + current.average().setScale(0,RoundingMode.HALF_UP) + " đ/đơn; đóng góp còn lại: " + change.basketEffect().setScale(0,RoundingMode.HALF_UP) + " đ.",
                "action", "Đối chiếu cơ cấu nhóm hàng và mức giảm giá; thử bộ sản phẩm hoặc bán kèm theo nhu cầu, rồi đo lại giá trị đơn."));
        if (change.discountRateChange().compareTo(new BigDecimal("1")) > 0)
            insights.add(Map.of("title", "Tỷ lệ giảm giá tăng", "evidence", "Tăng " + change.discountRateChange().setScale(2,RoundingMode.HALF_UP) + " điểm phần trăm trên tạm tính.", "action", "Rà soát ưu đãi theo phân khúc; thử giới hạn giảm giá và đo cả doanh thu lẫn số đơn. Chưa thể kết luận hiệu quả lợi nhuận khi thiếu giá vốn lịch sử."));
        if (!categories.isEmpty()) {
            var top = categories.get(0);
            insights.add(Map.of("title", "Nhóm hàng biến động lớn nhất", "evidence", top.get("name") + ": chênh lệch doanh thu phân bổ " + ((BigDecimal)top.get("delta")).setScale(0,RoundingMode.HALF_UP) + " đ.", "action", "Kiểm tra sản phẩm trong nhóm và tồn kho hiện tại. Tồn kho hiện tại không chứng minh đã thiếu hàng trong kỳ trước; cần đối chiếu lịch sử nhập/xuất để xác nhận."));
        }
        return insights;
    }
}
