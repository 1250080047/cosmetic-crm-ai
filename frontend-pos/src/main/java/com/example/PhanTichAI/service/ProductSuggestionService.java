package com.example.PhanTichAI.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * "Customers who buy this also buy …" at the counter. Reads the rules found by the analysis service
 * (backend-pos/basket_service.py, table product_associations) and picks, for what is already in the cart, up to three
 * other products that are on sale and in stock, highest confidence first. Each product is suggested once, with the
 * strongest reason.
 */
@Service
public class ProductSuggestionService {
    public static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_CART_PRODUCTS = 60;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductSuggestionService(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public List<Map<String, Object>> forCart(Collection<Integer> cartProductIds) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (cartProductIds != null) cartProductIds.stream().filter(Objects::nonNull).limit(MAX_CART_PRODUCTS).forEach(ids::add);
        if (ids.isEmpty()) return List.of();
        String sql = """
                SELECT TOP (:limit) consequent_id, product_name, unit_price, stock_quantity, image_url, category_id,
                       confidence, lift, together_count, antecedent_id, because_name
                FROM (
                    SELECT r.consequent_id, p.product_name, p.unit_price, s.sellable AS stock_quantity, p.image_url, p.category_id,
                           r.confidence, r.lift, r.together_count, r.antecedent_id, a.product_name AS because_name,
                           ROW_NUMBER() OVER (PARTITION BY r.consequent_id ORDER BY r.confidence DESC, r.lift DESC) AS pick
                    FROM product_associations r
                    JOIN products p ON p.id = r.consequent_id
                    JOIN products a ON a.id = r.antecedent_id
                    JOIN (""" + InventoryService.SELLABLE_SQL + """
                    ) s ON s.product_id = r.consequent_id
                    WHERE r.run_id = (SELECT MAX(id) FROM association_runs)
                      AND r.antecedent_id IN (:ids) AND r.consequent_id NOT IN (:ids)
                      AND p.is_active = 1 AND s.sellable > 0
                ) ranked
                WHERE pick = 1
                ORDER BY confidence DESC, lift DESC, consequent_id""";
        return jdbc.query(sql, new MapSqlParameterSource().addValue("ids", ids).addValue("limit", MAX_SUGGESTIONS), (row, i) -> {
            Map<String, Object> suggestion = new LinkedHashMap<>();
            suggestion.put("product_id", row.getInt("consequent_id"));
            suggestion.put("product_name", row.getString("product_name"));
            suggestion.put("unit_price", row.getBigDecimal("unit_price"));
            suggestion.put("stock_quantity", row.getInt("stock_quantity"));
            suggestion.put("image_url", row.getString("image_url"));
            suggestion.put("category_id", row.getObject("category_id"));
            suggestion.put("confidence", row.getDouble("confidence"));
            suggestion.put("lift", row.getDouble("lift"));
            suggestion.put("together", row.getInt("together_count"));
            suggestion.put("because_id", row.getInt("antecedent_id"));
            suggestion.put("because_name", row.getString("because_name"));
            return suggestion;
        });
    }
}
