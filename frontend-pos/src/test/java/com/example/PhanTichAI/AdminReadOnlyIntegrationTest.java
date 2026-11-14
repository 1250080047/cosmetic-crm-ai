package com.example.PhanTichAI;

import com.example.PhanTichAI.security.*;
import com.example.PhanTichAI.service.RevenueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: exercises the actual SQL Server through SELECT-only HTTP requests.
 * Authentication is mocked only in this test context; it never creates SQL accounts. */
@EnabledIfEnvironmentVariable(named="POS_READ_ONLY_QA", matches="1")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false", "logging.level.root=WARN", "debug=false",
    "member.public-port=-1"
})
class AdminReadOnlyIntegrationTest {
    @LocalServerPort int port;
    @MockBean AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RevenueService revenue;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void sharedDeploymentLockHasAnActualSqlTransactionAndCleansUp() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        var result = transaction.execute(status -> jdbc.queryForMap("""
            SET NOCOUNT ON;
            DECLARE @transaction_anchor int;
            SELECT TOP (1) @transaction_anchor=id FROM dbo.roles;
            DECLARE @lock int;
            EXEC @lock=sys.sp_getapplock @Resource=N'cosmetic-crm:cluster-model',
                @LockMode='Shared',@LockOwner='Transaction',@LockTimeout=15000;
            SELECT @lock AS lock_status,@@TRANCOUNT AS transaction_count;
            """));
        assertThat(((Number)result.get("lock_status")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(((Number)result.get("transaction_count")).intValue()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT @@TRANCOUNT",Integer.class)).isZero();
    }

    @Test void rendersAndReadsActualDatabaseWithoutChangingAnyRows() throws Exception {
        var before=fingerprint();
        String password=UUID.randomUUID().toString(),hash=encoder.encode(password);
        when(accounts.loadUserByUsername("readonly-preview")).thenAnswer(invocation->new StoreUser(-7,"readonly-preview",hash,"Kiểm tra chỉ đọc","ADMIN",true));
        when(accounts.needsSetup()).thenReturn(true);
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        var client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String base="http://127.0.0.1:"+port;
        var login=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var token=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.body());assertThat(token.find()).isTrue();
        String body="username=readonly-preview&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)+"&_csrf="+URLEncoder.encode(token.group(1),StandardCharsets.UTF_8);
        var auth=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(auth.statusCode()).isEqualTo(302);assertThat(auth.headers().firstValue("location").orElseThrow()).endsWith("/admin");
        for(String path:List.of("/admin","/pos","/setup","/api/auth/me","/api/admin/revenue/options","/api/admin/products","/api/admin/categories","/api/admin/inventory","/api/admin/segments","/api/admin/customers?search=%25&page=0","/api/admin/customers?page=0","/api/admin/orders")) {
            var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path+" response "+response.body().substring(0,Math.min(response.body().length(),200))).isEqualTo(200);
        }
        for(String query:List.of("granularity=year&year=2020","granularity=month&year=2026&month=11&comparison=year","granularity=month&year=2020&month=1&comparison=previous","granularity=month&year=2020&month=2&comparison=previous","granularity=month&year=2027&month=1&comparison=year")) {
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/revenue?"+query)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(query+" "+response.body().substring(0,Math.min(response.body().length(),300))).isEqualTo(200);
            var data=mapper.readTree(response.body());
            if(data.path("current").path("orders").asLong()>0 && data.path("previous").path("orders").asLong()>0)
                assertThat(data.path("insights").size()).isGreaterThanOrEqualTo(3);
            BigDecimal total=data.path("current").path("revenue").decimalValue();
            BigDecimal allocated=BigDecimal.ZERO;for(var category:data.path("categories"))allocated=allocated.add(category.path("current").decimalValue());
            assertThat(allocated).isCloseTo(total,within(new BigDecimal("0.02")));
        }
        var orderIds=jdbc.queryForList("SELECT TOP 1 transaction_no FROM orders ORDER BY order_date DESC",String.class);
        var activeStateExists=jdbc.queryForObject("SELECT COUNT(*) FROM model_versions WHERE status='ACTIVE'",Integer.class);
        if(Integer.valueOf(1).equals(activeStateExists)) {
            // A base customer last bought in 2021: the POS must place it in the same group as the daily re-scoring.
            var exampleCustomers=jdbc.queryForList("SELECT TOP 1 phone,segment_id FROM customers WHERE phone IS NOT NULL AND segment_id IS NOT NULL AND CAST(segment_scored_at AS date)=CAST(GETDATE() AS date) ORDER BY customer_id");
            if(!exampleCustomers.isEmpty()) {
                var example=exampleCustomers.get(0);
                var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/pos/customer-lookup?phone="+URLEncoder.encode(String.valueOf(example.get("phone")),StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var data=mapper.readTree(response.body());
                assertThat(data.path("found").asBoolean()).isTrue();
                assertThat(data.path("ai_available").asBoolean()).isTrue();
                assertThat(data.path("cluster_id").asInt()).isEqualTo(((Number)example.get("segment_id")).intValue());
            }
        }
        if(!orderIds.isEmpty()) {
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/orders/"+URLEncoder.encode(orderIds.get(0),StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        if("1".equals(System.getenv("POS_BROWSER_QA"))) {
            var builder=new ProcessBuilder("node",Path.of("src/test/browser/admin-readonly.mjs").toAbsolutePath().toString());
            builder.environment().put("POS_QA_URL",base);builder.environment().put("POS_QA_PASSWORD",password);
            builder.inheritIO();var process=builder.start();assertThat(process.waitFor()).isZero();
        }
        assertThat(fingerprint()).isEqualTo(before);
        verify(accounts,never()).save(any(),any(),any());verify(accounts,never()).setup(any());
    }
    private Map<String,Object> fingerprint() {
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("roles","users","customer_segments","customers","strategies","categories","products","orders","order_items","model_versions"))
            result.put(table,jdbc.queryForMap("SELECT COUNT(*) AS rows,CHECKSUM_AGG(BINARY_CHECKSUM(*)) AS digest FROM "+table));
        return result;
    }
}
