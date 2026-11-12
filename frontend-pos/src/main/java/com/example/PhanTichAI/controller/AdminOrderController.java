package com.example.PhanTichAI.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Orders are filtered and paged in SQL: the store holds tens of thousands of invoices. */
@RestController
@RequestMapping("/api/admin")
public class AdminOrderController {
    private static final int EXPORT_LIMIT = 200_000;
    private final JdbcTemplate jdbc;

    public AdminOrderController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private record Filter(String where, List<Object> args) {}
    /** Words a manager may type in the search box, mapped to the stored payment method. */
    private static final Map<String, String> PAYMENT_WORDS = Map.of(
            "tien mat", "CASH", "cash", "CASH",
            "chuyen khoan", "BANK_TRANSFER", "ck", "BANK_TRANSFER", "qr", "BANK_TRANSFER",
            "the", "CARD", "quet the", "CARD",
            "truc tuyen", "ONLINE", "online", "ONLINE");

    private Filter filter(String search, String payment, String from, String to, Integer shiftId) {
        if (payment != null && payment.isBlank()) payment = null;
        if ((search != null && search.length() > 100) || (payment != null && payment.length() > 30))
            throw new ResponseStatusException(BAD_REQUEST, "Bộ lọc không hợp lệ.");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null && !from.isBlank()) { where.append(" AND o.order_date>=CAST(? AS date)"); args.add(from); }
        if (to != null && !to.isBlank()) { where.append(" AND o.order_date<DATEADD(day,1,CAST(? AS date))"); args.add(to); }
        String keyword = search == null ? "" : com.example.PhanTichAI.service.CustomerLanguage.normalize(search);
        if (PAYMENT_WORDS.containsKey(keyword)) { payment = PAYMENT_WORDS.get(keyword); search = ""; }
        if (payment != null && !payment.isBlank()) {
            if (!PAYMENT_WORDS.containsValue(payment)) throw new ResponseStatusException(BAD_REQUEST, "Phương thức thanh toán không hợp lệ.");
            if ("BANK_TRANSFER".equals(payment)) where.append(" AND o.payment_method IN ('BANK_TRANSFER','TRANSFER','QR')");
            else { where.append(" AND o.payment_method=?"); args.add(payment); }
        }
        if (shiftId != null) { where.append(" AND o.shift_id=?"); args.add(shiftId); }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().replace("~", "~~").replace("%", "~%").replace("_", "~_").replace("[", "~[") + "%";
            where.append(" AND (o.transaction_no LIKE ? ESCAPE '~' OR c.customer_code LIKE ? ESCAPE '~' OR c.full_name LIKE ? ESCAPE '~' OR c.phone LIKE ? ESCAPE '~')");
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
        }
        return new Filter(where.toString(), args);
    }

    private static final String SELECT = """
            SELECT o.transaction_no AS transactionNo,o.order_date AS orderDate,o.customer_id AS customerId,
                   c.customer_code AS customerCode,c.full_name AS customerName,o.total_amount AS totalAmount,
                   o.discount_amount AS discountAmount,o.final_amount AS finalAmount,o.payment_method AS paymentMethod,
                   u.full_name AS cashierName,o.shift_id AS shiftId
            FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN users u ON u.id=o.cashier_id
            """;

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(defaultValue = "") String search, @RequestParam(required = false) String payment,
                                      @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                      @RequestParam(required = false) Integer shiftId,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 200) throw new ResponseStatusException(BAD_REQUEST, "Phân trang không hợp lệ.");
        Filter f = filter(search, payment, from, to, shiftId);
        var totals = jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(o.final_amount),0) AS revenue FROM orders o " +
                "LEFT JOIN customers c ON c.customer_id=o.customer_id" + f.where(), f.args().toArray());
        List<Object> paged = new ArrayList<>(f.args());
        paged.add(page * size); paged.add(size);
        var items = jdbc.queryForList(SELECT + f.where() + " ORDER BY o.order_date DESC,o.transaction_no DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", paged.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", totals.get("total")); result.put("revenue", totals.get("revenue"));
        result.put("page", page); result.put("size", size); result.put("items", items);
        return result;
    }

    @GetMapping("/orders/summary")
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT COUNT(*) AS orders,COALESCE(SUM(final_amount),0) AS revenue,MIN(order_date) AS firstOrder,MAX(order_date) AS lastOrder
                FROM orders"""));
        result.put("paymentMethods", jdbc.queryForList("SELECT DISTINCT payment_method FROM orders ORDER BY payment_method", String.class));
        result.put("products", jdbc.queryForMap("SELECT COUNT(*) AS total,SUM(CASE WHEN is_active=1 THEN 1 ELSE 0 END) AS active FROM products"));
        return result;
    }

    @GetMapping("/orders/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String search, @RequestParam(required = false) String payment,
                                         @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer shiftId) {
        Filter f = filter(search, payment, from, to, shiftId);
        List<Object> args = new ArrayList<>(f.args());
        args.add(EXPORT_LIMIT);
        StringBuilder csv = new StringBuilder("﻿Mã hóa đơn,Thời gian,Mã khách,Tên khách,Tạm tính,Giảm giá,Thực thu,Thanh toán,Thu ngân,Ca\r\n");
        jdbc.query(SELECT + f.where() + " ORDER BY o.order_date DESC,o.transaction_no DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", rs -> {
            csv.append(String.join(",", cell(rs.getString("transactionNo")), cell(rs.getString("orderDate")), cell(rs.getString("customerCode")),
                    cell(rs.getString("customerName")), cell(rs.getString("totalAmount")), cell(rs.getString("discountAmount")),
                    cell(rs.getString("finalAmount")), cell(rs.getString("paymentMethod")), cell(rs.getString("cashierName")),
                    cell(rs.getString("shiftId")))).append("\r\n");
        }, args.toArray());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hoa-don.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        if (text.matches("^[=+@-].*")) text = "'" + text;  // spreadsheet formula injection guard
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
