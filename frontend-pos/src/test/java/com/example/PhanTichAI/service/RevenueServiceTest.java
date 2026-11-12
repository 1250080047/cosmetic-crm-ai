package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class RevenueServiceTest {
    private RevenueService.Totals totals(long orders,String gross,String discount,String net) {
        return new RevenueService.Totals(orders,new BigDecimal(gross),new BigDecimal(discount),new BigDecimal(net),0,0);
    }
    @Test void historicalMonthUsesExclusiveEndAndCrossYearBaseline() {
        var w=RevenueService.window("month",2025,1,"previous",LocalDateTime.of(2026,11,8,10,0));
        assertThat(w.start()).isEqualTo("2025-01-01T00:00:00");
        assertThat(w.end()).isEqualTo("2025-02-01T00:00:00");
        assertThat(w.previousStart()).isEqualTo("2024-12-01T00:00:00");
        assertThat(w.previousEnd()).isEqualTo("2025-01-01T00:00:00");assertThat(w.partial()).isFalse();
    }
    @Test void partialMonthComparesOnlyThroughSameCalendarTime() {
        var now=LocalDateTime.of(2026,11,8,14,30);
        var w=RevenueService.window("month",2026,11,"year",now);
        assertThat(w.partial()).isTrue();assertThat(w.end()).isEqualTo(now);
        assertThat(w.previousEnd()).isEqualTo(now.minusYears(1));
    }
    @Test void shortPreviousMonthClampsCalendarDay() {
        var w=RevenueService.window("month",2024,3,"previous",LocalDateTime.of(2024,3,31,12,0));
        assertThat(w.previousEnd()).isEqualTo("2024-02-29T12:00:00");
    }
    @Test void partialYearHandlesLeapDay() {
        var w=RevenueService.window("year",2024,1,"previous",LocalDateTime.of(2024,2,29,12,0));
        assertThat(w.previousStart()).isEqualTo("2023-01-01T00:00:00");
        assertThat(w.previousEnd()).isEqualTo("2023-02-28T12:00:00");
    }
    @Test void futurePeriodIsEmptyAndFlagged() {
        var w=RevenueService.window("month",2027,1,"year",LocalDateTime.of(2026,11,8,10,0));
        assertThat(w.future()).isTrue();assertThat(w.end()).isEqualTo(w.start());assertThat(w.previousEnd()).isEqualTo(w.previousStart());
    }
    @Test void zeroBaselineDoesNotInventGrowthPercentage() {
        var c=RevenueService.compare(totals(2,"200","0","200"),totals(0,"0","0","0"));
        assertThat(c.revenueChangePercent()).isNull();assertThat(c.orderEffect().add(c.basketEffect())).isEqualByComparingTo(c.revenueDelta());
    }
    @Test void fewerOrdersAndHigherBasketExplainNetChangeExactly() {
        var c=RevenueService.compare(totals(8,"1200","200","1000"),totals(10,"1100","100","1000"));
        assertThat(c.orderEffect()).isEqualByComparingTo("-200");assertThat(c.basketEffect()).isEqualByComparingTo("200");
        assertThat(c.revenueChangePercent()).isEqualByComparingTo("0");assertThat(c.discountRateChange()).isPositive();
    }
    @Test void attributionReconcilesEvenWithRepeatingAverage() {
        var c=RevenueService.compare(totals(7,"150","0","150"),totals(3,"100","0","100"));
        assertThat(c.orderEffect().add(c.basketEffect())).isEqualByComparingTo("50");
    }
    @Test void invalidPeriodsAreRejected() {
        assertThatThrownBy(()->RevenueService.window("day",2026,1,"year",LocalDateTime.now())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->RevenueService.window("month",2026,13,"year",LocalDateTime.now())).isInstanceOf(ResponseStatusException.class);
    }
}
