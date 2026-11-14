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
    private String password;

    @BeforeEach void signIn() throws Exception {
        password = UUID.randomUUID().toString();
        String hash = encoder.encode(password);
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

    // ---- 3. stock by lot and expiry date ----------------------------------------------------------
    @Autowired com.example.PhanTichAI.service.InventoryService inventory;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test void everyProductStockEqualsItsLots() {
        Integer mismatched = jdbc.queryForObject("""
                SELECT COUNT(*) FROM products p
                WHERE p.stock_quantity <> COALESCE((SELECT SUM(quantity_remaining) FROM stock_lots l WHERE l.product_id = p.id), 0)""", Integer.class);
        assertThat(mismatched).isZero();
    }

    /** An expired lot of 5 units on the shelf (inside the test transaction, rolled back): stock stays equal to its lots. */
    private int productWithAnExpiredLot() {
        Integer product = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND stock_quantity >= 10 ORDER BY id", Integer.class);
        jdbc.update("""
                INSERT INTO stock_lots(product_id, lot_no, expiry_date, quantity_received, quantity_remaining, unit_cost, received_at)
                VALUES (?, 'TEST-EXPIRED', DATEADD(day, -3, CAST(SYSDATETIME() AS DATE)), 5, 5, 50000, SYSDATETIME())""", product);
        jdbc.update("UPDATE products SET stock_quantity = stock_quantity + 5 WHERE id = ?", product);
        return product;
    }

    @Test @Transactional void saleTakesTheLotThatExpiresFirstAndSkipsExpiredLots() {
        int product = productWithAnExpiredLot();
        int stockBefore = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product);
        int sellableBefore = inventory.sellable().getOrDefault(product, 0);
        assertThat(sellableBefore).as("expired units are not sellable").isLessThan(stockBefore);
        // a new lot that expires in 5 days must be sold before the older lot that expires later
        var received = inventory.receive(new com.example.PhanTichAI.service.InventoryService.Receipt("Kiểm tra", null, List.of(
                new com.example.PhanTichAI.service.InventoryService.Line(product, "TEST-EARLY", java.time.LocalDate.now().plusDays(5), 3, null))), null);
        assertThat(received.get("units")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product)).isEqualTo(stockBefore + 3);

        CheckoutRequest request = new CheckoutRequest();
        CheckoutRequest.ItemDto item = new CheckoutRequest.ItemDto();
        item.setProductId(product); item.setQuantity(2);
        request.setItems(List.of(item)); request.setPaymentMethod("CASH");
        String invoice = (String) checkout.checkout(request).get("transaction_no");
        entityManager.flush();   // the stock change of the product entity is written at commit in real use
        var taken = jdbc.queryForList("""
                SELECT l.lot_no, x.quantity FROM order_item_lots x JOIN order_items i ON i.id = x.order_item_id JOIN stock_lots l ON l.id = x.lot_id
                WHERE i.transaction_no = ?""", invoice);
        assertThat(taken).singleElement().satisfies(row -> {
            assertThat(row.get("lot_no")).isEqualTo("TEST-EARLY");
            assertThat(row.get("quantity")).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product)).isEqualTo(stockBefore + 1);
        assertThat(inventory.sellable().get(product)).isEqualTo(sellableBefore + 1);

        // more than the sellable units: refused even though the expired lot is still on the shelf
        CheckoutRequest tooMuch = new CheckoutRequest();
        CheckoutRequest.ItemDto all = new CheckoutRequest.ItemDto();
        all.setProductId(product); all.setQuantity(sellableBefore + 2);
        tooMuch.setItems(List.of(all)); tooMuch.setPaymentMethod("CASH");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> checkout.checkout(tooMuch)).hasMessageContaining("còn hạn sử dụng");
    }

    @Test @Transactional void disposingAnExpiredLotTakesItOutOfStock() {
        productWithAnExpiredLot();
        var lot = jdbc.queryForMap("""
                SELECT TOP 1 id, product_id, quantity_remaining FROM stock_lots
                WHERE quantity_remaining > 0 AND expiry_date < CAST(SYSDATETIME() AS DATE) ORDER BY id""");
        int product = ((Number) lot.get("product_id")).intValue(), quantity = ((Number) lot.get("quantity_remaining")).intValue();
        int before = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product);
        inventory.dispose(((Number) lot.get("id")).intValue(), "Hết hạn sử dụng", null);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product)).isEqualTo(before - quantity);
        assertThat(jdbc.queryForObject("SELECT disposed_quantity FROM stock_lots WHERE id = ?", Integer.class, lot.get("id"))).isEqualTo(quantity);
    }

    @Test void managerSeesStockAlertsLotsAndReceipts() throws Exception {
        JsonNode overview = get("/api/admin/stock/overview");
        assertThat(overview.path("summary").path("expiredLots").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(overview.path("rules").path("expiry_warning_days").asInt()).isEqualTo(60);
        for (JsonNode lot : overview.path("expiring")) assertThat(lot.path("daysLeft").asInt()).isLessThanOrEqualTo(60);
        assertThat(get("/api/admin/stock/lots?search=DK").size()).isPositive();
        assertThat(get("/api/admin/stock/receipts").isArray()).isTrue();
        // the POS only offers units that have not expired
        JsonNode products = get("/api/pos/products");
        var sellable = inventory.sellable();
        for (JsonNode product : products)
            assertThat(product.path("stockQuantity").asInt()).isEqualTo(sellable.getOrDefault(product.path("id").asInt(), 0));
    }

    // ---- 4. offers measured against a control group --------------------------------------------------
    @Autowired com.example.PhanTichAI.service.ExperimentService experiments;
    @Autowired com.example.PhanTichAI.service.CustomerOfferService offers;
    @Autowired com.example.PhanTichAI.service.MemberService members;
    @Autowired com.example.PhanTichAI.repository.StrategyRepository strategyRepository;
    @Autowired com.example.PhanTichAI.repository.CustomerRepository customerRepository;

    @Test @Transactional void switchingAnOfferOnKeepsTenPercentWithoutItAndStopsWhenSwitchedOff() {
        var strategy = strategyRepository.findAll().stream()
                .filter(s -> s.getSegmentId() != null && jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE segment_id = ?", Integer.class, s.getSegmentId()) >= 100)
                .findFirst().orElseThrow();
        jdbc.update("UPDATE strategies SET is_active = 1, discount_percent = 7 WHERE id = ?", strategy.getId());   // rolled back
        entityManager.clear();
        strategy = strategyRepository.findById(strategy.getId()).orElseThrow();
        Integer experiment = experiments.start(strategy, "kiểm tra");
        int cohort = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE segment_id = ?", Integer.class, strategy.getSegmentId());
        int assigned = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_assignments WHERE experiment_id = ?", Integer.class, experiment);
        int control = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_assignments WHERE experiment_id = ? AND arm = 'CONTROL'", Integer.class, experiment);
        assertThat(assigned).isEqualTo(cohort);
        assertThat((double) control / cohort).isBetween(0.05, 0.15);

        Integer controlCustomer = jdbc.queryForObject("SELECT TOP 1 customer_id FROM campaign_assignments WHERE experiment_id = ? AND arm = 'CONTROL' ORDER BY customer_id", Integer.class, experiment);
        Integer offerCustomer = jdbc.queryForObject("SELECT TOP 1 customer_id FROM campaign_assignments WHERE experiment_id = ? AND arm = 'OFFER' ORDER BY customer_id", Integer.class, experiment);
        var withoutOffer = offers.forCustomer(customerRepository.findById(controlCustomer).orElseThrow());
        var withOffer = offers.forCustomer(customerRepository.findById(offerCustomer).orElseThrow());
        if (withoutOffer.clusterId() == strategy.getSegmentId()) {   // the model places the customer in the same group today
            assertThat(withoutOffer.controlGroup()).isTrue();
            assertThat(withoutOffer.strategyId()).isNull();
            assertThat(withoutOffer.discountPercent()).isEqualByComparingTo("0");
        }
        if (withOffer.clusterId() == strategy.getSegmentId()) {
            assertThat(withOffer.controlGroup()).isFalse();
            assertThat(withOffer.discountPercent()).isEqualByComparingTo("7");
        }
        assertThat(members.card(controlCustomer).get("offer")).as("the control customer's card shows no offer").isNull();
        assertThat(members.card(offerCustomer).get("offer")).isNotNull();

        var results = experiments.results();
        assertThat(results).first().satisfies(row -> {
            assertThat(row.get("running")).isEqualTo(true);
            assertThat(((Map<?, ?>) row.get("analysis")).get("verdict")).isEqualTo("INSUFFICIENT");   // nobody came back yet
        });
        experiments.end(strategy.getId());
        assertThat(experiments.inControl(strategy.getId(), controlCustomer)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_experiments WHERE id = ? AND ended_at IS NOT NULL", Integer.class, experiment)).isEqualTo(1);
    }

    @Test void managerSeesExperimentResultsAndASimulation() throws Exception {
        assertThat(get("/api/admin/experiments").path("experiments").isArray()).isTrue();
        String body = "{\"groupSize\":3000,\"baseRate\":0.1,\"uplift\":0.05,\"seed\":42}";
        var login = client.send(HttpRequest.newBuilder(URI.create(base() + "/admin")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var token = Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(login.body());
        assertThat(token.find()).isTrue();
        var response = client.send(HttpRequest.newBuilder(URI.create(base() + "/api/admin/experiments/simulate"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token.group(1))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode simulation = mapper.readTree(response.body());
        assertThat(simulation.path("simulated").asBoolean()).isTrue();
        assertThat(simulation.path("control").path("customers").asInt()).isEqualTo(300);
    }

    // ---- 5. product reviews and sentiment -------------------------------------------------------------
    @Autowired com.example.PhanTichAI.service.ReviewService reviewService;

    @Test @Transactional void customerReviewsAProductBoughtAtTheStoreAndEarnsTheBonusOnce() {
        Integer customer = jdbc.queryForObject("SELECT TOP 1 customer_id FROM customers ORDER BY customer_id", Integer.class);
        Integer product = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND stock_quantity > 5 ORDER BY id DESC", Integer.class);
        Integer other = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND id <> ? ORDER BY id", Integer.class, product);
        jdbc.update("DELETE FROM product_reviews WHERE customer_id = ?", customer);   // rolled back
        CheckoutRequest request = new CheckoutRequest();
        CheckoutRequest.ItemDto item = new CheckoutRequest.ItemDto();
        item.setProductId(product); item.setQuantity(1);
        request.setItems(List.of(item)); request.setCustomerId(customer); request.setPaymentMethod("CASH");
        checkout.checkout(request);
        entityManager.flush();
        int pointsBefore = jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM loyalty_points WHERE customer_id = ?", Integer.class, customer);

        assertThat(reviewService.forCustomer(customer)).anySatisfy(row -> assertThat(row.get("productId")).isEqualTo(product));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviewService.save(customer, other, 5, "Không mua mà vẫn đánh giá"))
                .hasMessageContaining("đã mua tại cửa hàng");

        var first = reviewService.save(customer, product, 2, "Bôi lên bị nổi mẩn đỏ và ngứa, thất vọng");
        assertThat(first.get("bonus_points")).isEqualTo(10);
        var stored = jdbc.queryForMap("SELECT sentiment, irritation, topics FROM product_reviews WHERE id = ?", first.get("review_id"));
        assertThat(stored.get("sentiment")).as("analysed by FastAPI on this computer").isEqualTo("NEGATIVE");
        assertThat(stored.get("irritation")).isEqualTo(true);
        assertThat((String) stored.get("topics")).contains("Kích ứng da");

        var edited = reviewService.save(customer, product, 4, "Dùng lại thấy ổn hơn, thơm nhẹ, không bết");
        assertThat(edited.get("review_id")).isEqualTo(first.get("review_id"));
        assertThat(edited.get("bonus_points")).as("the bonus is given once per review").isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT sentiment FROM product_reviews WHERE id = ?", String.class, first.get("review_id"))).isEqualTo("POSITIVE");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM loyalty_points WHERE customer_id = ?", Integer.class, customer))
                .isEqualTo(pointsBefore + 10);

        var ratings = jdbc.queryForList(com.example.PhanTichAI.service.ReviewService.RATINGS_SQL);
        assertThat(ratings).anySatisfy(row -> assertThat(((Number) row.get("product_id")).intValue()).isEqualTo(product));
        reviewService.setStatus((Integer) first.get("review_id"), "HIDDEN", null);
        assertThat(jdbc.queryForList(com.example.PhanTichAI.service.ReviewService.RATINGS_SQL))
                .noneSatisfy(row -> assertThat(((Number) row.get("product_id")).intValue()).isEqualTo(product));
        assertThat(reviewService.overview().get("summary")).isNotNull();
    }

    /** POS_BROWSER_QA=1: the new admin tabs and the POS suggestions in headless Chrome (screenshots in target/growth-ui-qa). */
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "POS_BROWSER_QA", matches = "1")
    void newScreensRenderWithoutErrors() throws Exception {
        var builder = new ProcessBuilder("node", java.nio.file.Path.of("src/test/browser/growth-ui.mjs").toAbsolutePath().toString());
        builder.environment().put("POS_QA_URL", base());
        builder.environment().put("POS_QA_USER", "growth-preview");
        builder.environment().put("POS_QA_PASSWORD", password);
        builder.inheritIO();
        assertThat(builder.start().waitFor()).isZero();
    }

    @Test void grossProfitIsRevenueMinusTheCostOfTheLotsSold() throws Exception {
        JsonNode report = get("/api/admin/profit");
        JsonNode summary = report.path("summary");
        assertThat(summary.path("orders").asInt()).isPositive();
        assertThat(summary.path("profit").decimalValue())
                .isEqualByComparingTo(summary.path("revenue").decimalValue().subtract(summary.path("cost").decimalValue()));
        assertThat(summary.path("linesWithoutCost").asInt()).as("every product on sale has a cost price").isZero();
        assertThat(report.path("months").size()).isPositive();
        assertThat(report.path("products").size()).isBetween(1, 10);
    }

    @Test void stockOnSaleIsInDateAndPricedAtCost() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE is_active = 1 AND cost_price IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_lots WHERE quantity_remaining > 0 AND unit_cost IS NULL", Integer.class)).isZero();
    }

    // ---- cash reconciliation at the end of a shift ---------------------------------------------------
    @Autowired com.example.PhanTichAI.service.ShiftService shiftService;

    private int cashierWithoutOpenShift() {
        return jdbc.queryForObject("""
                SELECT TOP 1 u.id FROM users u JOIN roles r ON r.id = u.role_id
                WHERE r.name = 'CASHIER' AND NOT EXISTS (SELECT 1 FROM shifts s WHERE s.cashier_id = u.id AND s.status = 'OPEN') ORDER BY u.id""", Integer.class);
    }

    @Test @Transactional void cashShortBeyondTenThousandNeedsAReasonAndAManagerDecision() {
        int cashier = cashierWithoutOpenShift();
        int shift = ((Number) ((Map<?, ?>) shiftService.open(cashier, new java.math.BigDecimal("500000")).get("shift")).get("id")).intValue();
        Integer product = jdbc.queryForObject("SELECT TOP 1 id FROM products WHERE is_active = 1 AND stock_quantity > 5 ORDER BY id", Integer.class);
        CheckoutRequest sale = new CheckoutRequest();
        CheckoutRequest.ItemDto item = new CheckoutRequest.ItemDto();
        item.setProductId(product); item.setQuantity(1);
        sale.setItems(List.of(item)); sale.setPaymentMethod("CASH");
        var receipt = checkout.checkout(sale, cashier);
        java.math.BigDecimal paid = (java.math.BigDecimal) receipt.get("final_amount");
        entityManager.flush();
        shiftService.addMovement(cashier, "OUT", new java.math.BigDecimal("20000"), "Chi mua nước uống");
        assertThat(shiftService.expectedCash(shift)).isEqualByComparingTo(new java.math.BigDecimal("480000").add(paid));

        // the drawer holds 500.000 đ: short by (paid − 20.000) đ, more than 10.000 đ
        var notes = Map.of("500000", 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> shiftService.close(null, cashier, false, null, notes, null, null))
                .hasMessageContaining("thiếu").hasMessageContaining("lý do");
        var closed = shiftService.close(null, cashier, false, null, notes, null, "Thối nhầm tiền cho khách lúc 15h");
        assertThat(((Map<?, ?>) closed.get("review")).get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT cash_breakdown FROM shifts WHERE id = ?", String.class, shift)).isEqualTo("{\"500000\":1}");

        // the manager finds the sale was paid by transfer: the drawer is now 20.000 đ over → only "add to the fund" fits
        String invoice = (String) receipt.get("transaction_no");
        var corrected = shiftService.correctPayment(invoice, "BANK_TRANSFER", "Khách chuyển khoản, bấm nhầm tiền mặt", cashier);
        assertThat(((Map<?, ?>) corrected.get("cash")).get("difference")).isEqualTo(new java.math.BigDecimal("20000.00"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_changes WHERE transaction_no = ?", Integer.class, invoice)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> shiftService.review(shift, cashier, "STAFF_REPAY", "Nhân viên bồi hoàn"))
                .hasMessageContaining("không phù hợp");
        var reviewed = shiftService.review(shift, cashier, "ADD_TO_FUND", "Đã kiểm tra, nộp 20.000 đ dư vào quỹ");
        assertThat(((Map<?, ?>) reviewed.get("review")).get("status")).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT cash_difference FROM shifts WHERE id = ?", java.math.BigDecimal.class, shift)).isEqualByComparingTo("20000");
    }

    @Test @Transactional void smallChangeWithinTenThousandClosesNormally() {
        int cashier = cashierWithoutOpenShift();
        shiftService.open(cashier, new java.math.BigDecimal("505000"));
        var closed = shiftService.close(null, cashier, false, null, Map.of("500000", 1), null, null);   // 5.000 đ short
        assertThat(((Map<?, ?>) closed.get("review")).get("status")).isNull();
        assertThat(((Map<?, ?>) closed.get("cash")).get("withinTolerance")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> shiftService.close(null, cashier, false, null, null, null, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);   // nothing open any more
    }

    @Test void managerSeesShiftsWithDifferencesAndPendingReviews() throws Exception {
        JsonNode shifts = get("/api/admin/shifts");
        assertThat(shifts.path("tolerance").asInt()).isEqualTo(10000);
        assertThat(shifts.has("pending")).isTrue();
        assertThat(shifts.path("discrepancies").isArray()).isTrue();
    }

    @Test void managerSeesReviewOverviewAndList() throws Exception {
        assertThat(get("/api/admin/reviews/overview").path("summary").has("reviews")).isTrue();
        assertThat(get("/api/admin/reviews?filter=irritation").isArray()).isTrue();
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
