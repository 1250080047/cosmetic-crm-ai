package com.example.PhanTichAI;

import com.example.PhanTichAI.dto.CheckoutRequest;
import com.example.PhanTichAI.security.AccountService;
import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.PosCheckoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Opt-in (POS_READ_ONLY_QA=1): the features added for store growth against the actual SQL Server and analysis service.
 * Staff sign-in is mocked in this test context only (no account is created). Requests are read-only; checks that write
 * run inside a test transaction that is rolled back, so the store data is never changed.
 */
@EnabledIfEnvironmentVariable(named = "POS_READ_ONLY_QA", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=none", "logging.level.root=WARN", "member.public-port=-1"})
class GrowthIntegrationTest {
    @LocalServerPort int port;
    @MockBean AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired PosCheckoutService checkout;
    private HttpClient client;

    @BeforeEach void signIn() throws Exception {
        String password = UUID.randomUUID().toString(), hash = encoder.encode(password);
        when(accounts.loadUserByUsername("growth-preview")).thenAnswer(i -> new StoreUser(-8, "growth-preview", hash, "Kiểm tra tính năng mới", "ADMIN", true));
        when(accounts.needsSetup()).thenReturn(false);
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
        var login = client.send(HttpRequest.newBuilder(URI.create(base() + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());
        assertThat(token.find()).isTrue();
        String form = "username=growth-preview&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8) + "&_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8);
        var auth = client.send(HttpRequest.newBuilder(URI.create(base() + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(auth.statusCode()).isEqualTo(302);
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private JsonNode get(String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " → " + response.body().substring(0, Math.min(300, response.body().length()))).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    // ---- 1. bought together -----------------------------------------------------------------------
    @Test void cashierGetsInStockSuggestionsForTheCart() throws Exception {
        var top = jdbc.queryForList("""
                SELECT TOP 1 r.antecedent_id FROM product_associations r JOIN products p ON p.id = r.consequent_id
                WHERE r.run_id = (SELECT MAX(id) FROM association_runs) AND p.is_active = 1 AND p.stock_quantity > 0
                ORDER BY r.confidence DESC""", Integer.class);
        assertThat(top).as("rules exist (FastAPI computes them at first start)").isNotEmpty();
        int inCart = top.get(0);
        JsonNode suggestions = get("/api/pos/suggestions?ids=" + inCart);
        assertThat(suggestions.size()).isBetween(1, 3);
        Set<Integer> seen = new HashSet<>();
        for (JsonNode s : suggestions) {
            assertThat(s.path("product_id").asInt()).isNotEqualTo(inCart);
            assertThat(seen.add(s.path("product_id").asInt())).as("each product suggested once").isTrue();
            assertThat(s.path("stock_quantity").asInt()).isPositive();
            assertThat(s.path("confidence").asDouble()).isBetween(0.0, 1.0);
            assertThat(s.path("lift").asDouble()).isGreaterThan(1.0);
            assertThat(s.path("because_id").asInt()).isEqualTo(inCart);
        }
        double previous = 2;
        for (JsonNode s : suggestions) { assertThat(s.path("confidence").asDouble()).isLessThanOrEqualTo(previous); previous = s.path("confidence").asDouble(); }
        assertThat(get("/api/pos/suggestions").size()).as("empty cart").isZero();
    }

    @Test void adminSeesTheRulesAndHowOftenSuggestionsWereUsed() throws Exception {
        JsonNode data = get("/api/admin/ai/associations");
        assertThat(data.path("has_rules").asBoolean()).isTrue();
        assertThat(data.path("rules").size()).isPositive();
        assertThat(data.path("usage").has("invoices_with_suggestion")).isTrue();
    }

    // ---- 2. points and tiers ----------------------------------------------------------------------
    @Test @Transactional void memberUsesPointsAndEarnsOnARealInvoice() {
        Integer customer = jdbc.queryForObject("""
                SELECT TOP 1 customer_id FROM customers WHERE customer_id NOT IN (SELECT customer_id FROM member_accounts)
                ORDER BY customer_id""", Integer.class);
        jdbc.update("INSERT INTO member_accounts(customer_id, pin_hash) VALUES (?, 'test-only')", customer);       // rolled back
        jdbc.update("INSERT INTO loyalty_points(customer_id, transaction_no, points, note, created_at, kind) VALUES (?, NULL, 300, N'Thử', SYSDATETIME(), 'ADJUST')", customer);
        Integer product = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND stock_quantity > 5 AND unit_price >= 50000 ORDER BY id", Integer.class);
        CheckoutRequest request = new CheckoutRequest();
        CheckoutRequest.ItemDto item = new CheckoutRequest.ItemDto();
        item.setProductId(product); item.setQuantity(2);
        request.setItems(List.of(item));
        request.setCustomerId(customer);
        request.setRedeemPoints(100);
        request.setPaymentMethod("CASH");
        Map<String, Object> result = checkout.checkout(request);
        String invoice = (String) result.get("transaction_no");
        assertThat(result.get("points_redeemed")).isEqualTo(100);
        var ledger = jdbc.queryForList("SELECT kind, points FROM loyalty_points WHERE transaction_no = ? ORDER BY kind", invoice);
        assertThat(ledger).anySatisfy(row -> { assertThat(row.get("kind")).isEqualTo("REDEEM"); assertThat(row.get("points")).isEqualTo(-100); });
        assertThat(ledger).anySatisfy(row -> assertThat(row.get("kind")).isEqualTo("EARN"));
        assertThat(jdbc.queryForObject("SELECT points_redeemed FROM orders WHERE transaction_no = ?", Integer.class, invoice)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT points_discount FROM orders WHERE transaction_no = ?", java.math.BigDecimal.class, invoice))
                .isEqualByComparingTo("10000");
        int balance = jdbc.queryForObject("SELECT SUM(points) FROM loyalty_points WHERE customer_id = ?", Integer.class, customer);
        assertThat(balance).isEqualTo(300 - 100 + (Integer) result.get("points_earned"));
    }

    @Test void lookupShowsTierAndHidesPointsUseWithoutAScan() throws Exception {
        String phone = jdbc.queryForObject("SELECT TOP 1 phone FROM customers WHERE phone IS NOT NULL ORDER BY customer_id", String.class);
        JsonNode customer = get("/api/pos/customer-lookup?q=" + URLEncoder.encode(phone, StandardCharsets.UTF_8));
        assertThat(customer.path("found").asBoolean()).isTrue();
        assertThat(customer.path("tier_label").asText()).isIn("Thành viên", "Bạc", "Vàng");
        assertThat(customer.path("can_redeem").asBoolean()).as("typed phone number, not a scan").isFalse();
    }

    @Test @Transactional void productAddedFromASuggestionIsMarkedOnTheInvoice() {
        Integer product = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND stock_quantity > 5 ORDER BY id", Integer.class);
        CheckoutRequest request = new CheckoutRequest();
        CheckoutRequest.ItemDto suggested = new CheckoutRequest.ItemDto();
        suggested.setProductId(product); suggested.setQuantity(1); suggested.setFromSuggestion(true);
        request.setItems(List.of(suggested));
        request.setPaymentMethod("CASH");
        Map<String, Object> result = checkout.checkout(request);   // rolled back after the test
        Boolean flag = jdbc.queryForObject("SELECT from_suggestion FROM order_items WHERE transaction_no = ?", Boolean.class, result.get("transaction_no"));
        assertThat(flag).isTrue();
    }
}
