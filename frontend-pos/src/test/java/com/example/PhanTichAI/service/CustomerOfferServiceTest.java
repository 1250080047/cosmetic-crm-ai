package com.example.PhanTichAI.service;

import com.example.PhanTichAI.dto.AiPredictResponse;
import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.entity.Strategy;
import com.example.PhanTichAI.repository.ProductRepository;
import com.example.PhanTichAI.repository.StrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerOfferServiceTest {
    private static final String ACTIVE = "SELECT version FROM dbo.model_versions WHERE status='ACTIVE'";
    private AiIntegrationService ai;
    private StrategyRepository strategies;
    private ProductRepository products;
    private JdbcTemplate jdbc;
    private CustomerOfferService offers;
    private AiPredictResponse prediction;
    @BeforeEach void setup() {
        ai=mock(AiIntegrationService.class);strategies=mock(StrategyRepository.class);products=mock(ProductRepository.class);jdbc=mock(JdbcTemplate.class);
        offers=new CustomerOfferService(ai,strategies,products,jdbc);
        when(jdbc.queryForObject(contains("sp_getapplock"),eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForList(ACTIVE,String.class)).thenReturn(List.of("v6"));
        prediction=new AiPredictResponse();prediction.setActive_version("v6");prediction.setCluster_id(1);
        prediction.setSegment_name("Khách hàng ngủ đông · chi tiêu thấp");prediction.setColor_code("#123456");
        when(ai.predictSegment(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyBoolean())).thenReturn(prediction);
        Strategy stored=new Strategy();stored.setId(42);stored.setSegmentId(1);stored.setDiscountPercent(new BigDecimal("8"));stored.setIsActive(true);
        when(strategies.findBySegmentId(1)).thenReturn(Optional.of(stored));
    }
    @Test void oneDeploymentRemainsLockedAcrossPredictionAndPolicyRead() {
        var result=offers.forCustomer(new Customer());
        assertThat(result.strategyId()).isEqualTo(42);assertThat(result.discountPercent()).isEqualByComparingTo("8");
        assertThat(result.segment()).isEqualTo("Khách hàng ngủ đông · chi tiêu thấp");
        var order=inOrder(jdbc,ai,strategies);
        order.verify(jdbc).queryForObject(contains("sp_getapplock"),eq(Integer.class));
        order.verify(ai).predictSegment(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyBoolean());
        order.verify(strategies).findBySegmentId(1);
    }
    @Test void stalePredictionCannotGrantAnotherClustersDiscount() {
        prediction.setActive_version("v5");var result=offers.forCustomer(new Customer());
        assertThat(result.aiAvailable()).isFalse();assertThat(result.strategyId()).isNull();assertThat(result.discountPercent()).isZero();
        verifyNoInteractions(strategies,products);
    }
    @Test void failedSharedLockDoesNotUseStaleModel() {
        when(jdbc.queryForObject(contains("sp_getapplock"),eq(Integer.class))).thenReturn(-1);
        var result=offers.forCustomer(new Customer());assertThat(result.aiAvailable()).isFalse();verifyNoInteractions(ai,strategies,products);
    }
    @Test void customerWithoutInvoicesIsANewCustomerWithoutOffer() {
        prediction.setCluster_id(-1);prediction.setSegment_name("Chưa mua hàng");
        var result=offers.forCustomer(new Customer());
        assertThat(result.aiAvailable()).isTrue();assertThat(result.strategyId()).isNull();assertThat(result.discountPercent()).isZero();
        verify(ai).predictSegment(0,0,0,0,false);
        verifyNoInteractions(strategies,products);
    }
    @Test void duplicateFriendlyLabelsKeepTheirQualifiers() {
        assertThat(CustomerLanguage.segment("Khách hàng ngủ đông · chi tiêu cao")).isEqualTo("Khách hàng ngủ đông · chi tiêu cao");
        assertThat(CustomerLanguage.segment("Khách hàng có nguy cơ rời bỏ")).isEqualTo("Khách hàng có nguy cơ rời bỏ");
        assertThat(CustomerLanguage.segment("Khách hàng mới")).isEqualTo("Khách hàng mới");
        assertThat(CustomerLanguage.segment("Khách Ngủ Đông / Rời Bỏ")).isEqualTo("Khách hàng ngủ đông");
    }
    @Test void shortNamesOfModelV1ReadAsTheProfessionalNames() {
        assertThat(CustomerLanguage.segment("Lâu chưa ghé")).isEqualTo("Khách hàng ngủ đông");
        assertThat(CustomerLanguage.segment("Mới quen · chi tiêu cao")).isEqualTo("Khách hàng mới · chi tiêu cao");
        assertThat(CustomerLanguage.segment("Mua đều")).isEqualTo("Khách hàng trung thành");
        assertThat(CustomerLanguage.segment("Khách mới")).isEqualTo("Chưa mua hàng");
    }
    @Test void afterPurchaseAddsExactlyThisInvoiceAndNeverLooksUpBenefits() {
        prediction.setCluster_id(4);
        var before=new CustomerOfferService.Lrfm(120,40,2,300000,true);
        assertThat(offers.classifyAfterPurchase(new Customer(),before,new BigDecimal("50000"))).isEqualTo(4);
        verify(ai).predictSegment(120,0,3,350000,true);
        verifyNoInteractions(strategies,products);
    }
    @Test void firstPurchaseStartsTheRelationshipAtDayZero() {
        prediction.setCluster_id(2);
        var none=new CustomerOfferService.Lrfm(0,0,0,0,false);
        assertThat(offers.classifyAfterPurchase(new Customer(),none,new BigDecimal("90000"))).isEqualTo(2);
        verify(ai).predictSegment(0,0,1,90000,true);
    }
    @Test void offlineOrStaleClassificationLeavesCustomerUnassigned() {
        var before=new CustomerOfferService.Lrfm(10,5,1,1000,true);
        prediction.setCluster_id(-100);
        assertThat(offers.classifyAfterPurchase(new Customer(),before,BigDecimal.TEN)).isNull();
        prediction.setCluster_id(4);prediction.setActive_version("outdated");
        assertThat(offers.classifyAfterPurchase(new Customer(),before,BigDecimal.TEN)).isNull();
        verifyNoInteractions(strategies,products);
    }
    @Test void daysAreCountedOnCalendarDates() {
        var today=LocalDate.of(2026,11,11);
        assertThat(CustomerOfferService.days(LocalDateTime.of(2026,11,10,23,59),today)).isEqualTo(1);
        assertThat(CustomerOfferService.days(LocalDateTime.of(2021,1,11,0,0),today)).isEqualTo(2130);
        assertThat(CustomerOfferService.days(null,today)).isZero();
    }
}
