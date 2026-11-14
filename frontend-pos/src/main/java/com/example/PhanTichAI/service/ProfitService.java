package com.example.PhanTichAI.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Gross profit of the counter invoices (the OnlineSales history has no purchase cost):
 * revenue actually received (after offers and points) minus the cost of the exact lots sold — the purchase price of each
 * lot (order_item_lots → stock_lots.unit_cost); invoices from before lots existed use the product's cost price.
 * Gifts (price 0) count in the cost, as they are a real cost of the offer.
 */
@Service
public class ProfitService {
    private static final String LINES = """
            WITH lines AS (
                SELECT o.transaction_no, o.order_date, o.final_amount, i.product_id, i.quantity, i.revenue,
                       COALESCE((SELECT SUM(x.quantity * l.unit_cost) FROM order_item_lots x JOIN stock_lots l ON l.id = x.lot_id
                                 WHERE x.order_item_id = i.id AND l.unit_cost IS NOT NULL),
                                i.quantity * p.cost_price) AS cost
                FROM orders o JOIN order_items i ON i.transaction_no = o.transaction_no JOIN products p ON p.id = i.product_id
                WHERE o.payment_method <> 'ONLINE'),
            invoices AS (
                SELECT transaction_no, MIN(order_date) AS order_date, MIN(final_amount) AS revenue, SUM(cost) AS cost,
                       SUM(CASE WHEN cost IS NULL THEN 1 ELSE 0 END) AS missing
                FROM lines GROUP BY transaction_no)
            """;

    private final JdbcTemplate jdbc;

    public ProfitService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> report() {
        Map<String, Object> summary = jdbc.queryForMap(LINES + """
                SELECT COUNT(*) AS orders, COALESCE(SUM(revenue), 0) AS revenue, COALESCE(SUM(cost), 0) AS cost,
                       COALESCE(SUM(revenue), 0) - COALESCE(SUM(cost), 0) AS profit, COALESCE(SUM(missing), 0) AS linesWithoutCost,
                       MIN(order_date) AS firstOrder, MAX(order_date) AS lastOrder
                FROM invoices""");
        var months = jdbc.queryForList(LINES + """
                SELECT CONVERT(CHAR(7), order_date, 126) AS month, COUNT(*) AS orders, SUM(revenue) AS revenue, SUM(cost) AS cost,
                       SUM(revenue) - SUM(cost) AS profit
                FROM invoices GROUP BY CONVERT(CHAR(7), order_date, 126) ORDER BY month DESC""");
        var products = jdbc.queryForList(LINES + """
                SELECT TOP (10) p.id AS productId, p.product_name AS productName, SUM(l.quantity) AS quantity, SUM(l.revenue) AS revenue,
                       SUM(l.cost) AS cost, SUM(l.revenue) - SUM(l.cost) AS profit
                FROM lines l JOIN products p ON p.id = l.product_id
                GROUP BY p.id, p.product_name ORDER BY SUM(l.revenue) - SUM(l.cost) DESC, p.id""");
        return Map.of("summary", summary, "months", months, "products", products);
    }
}
