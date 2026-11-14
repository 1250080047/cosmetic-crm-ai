package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Control-group split and the statistics behind "does the offer bring customers back?". */
class ExperimentServiceTest {
    private static ExperimentService.Arm arm(int customers, int returned) { return new ExperimentService.Arm(customers, returned, BigDecimal.ZERO); }

    @Test void aboutOneCustomerInTenIsKeptAsControlAndTheSideNeverChanges() {
        int control = 0;
        for (int customer = 1; customer <= 10_000; customer++) {
            String side = ExperimentService.arm(7, customer, 10);
            assertThat(ExperimentService.arm(7, customer, 10)).isEqualTo(side);
            if (ExperimentService.CONTROL.equals(side)) control++;
        }
        assertThat(control).isBetween(900, 1100);
        assertThat(ExperimentService.arm(8, 33627, 10)).as("another experiment draws again")
                .isIn(ExperimentService.OFFER, ExperimentService.CONTROL);
    }

    @Test void clearUpliftIsReportedAsEffective() {
        Map<String, Object> result = ExperimentService.compare(arm(2700, 405), arm(300, 30));   // 15% vs 10%
        assertThat(result.get("verdict")).isEqualTo("EFFECTIVE");
        assertThat((Double) result.get("p_value")).isBetween(0.01, 0.03);
        assertThat((Double) result.get("difference")).isEqualTo(0.05);
        assertThat((Double) result.get("ci_low")).isPositive();
    }

    @Test void sameRatesShowNoDifferenceAndLowerRatesAreFlagged() {
        assertThat(ExperimentService.compare(arm(2700, 270), arm(300, 30)).get("verdict")).isEqualTo("NO_DIFFERENCE");
        assertThat(ExperimentService.compare(arm(2700, 135), arm(300, 45)).get("verdict")).isEqualTo("NEGATIVE");
    }

    @Test void aFewCustomersAreNotEnoughToConclude() {
        Map<String, Object> result = ExperimentService.compare(arm(3, 1), arm(0, 0));
        assertThat(result.get("verdict")).isEqualTo("INSUFFICIENT");
        assertThat(result.get("p_value")).isNull();
        assertThat(ExperimentService.compare(arm(2700, 4), arm(300, 1)).get("verdict")).as("too few returns").isEqualTo("INSUFFICIENT");
    }

    @Test void sampleSizeAndNormalDistributionMatchTextbookValues() {
        assertThat(ExperimentService.sampleSize(0.10, 0.15)).isBetween(670, 700);   // ≈ 686 per group
        assertThat(ExperimentService.normalCdf(1.959964)).isCloseTo(0.975, within(1e-6));
        assertThat(ExperimentService.normalCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(ExperimentService.normalCdf(-1)).isCloseTo(0.158655, within(1e-6));
    }

    @Test void simulationIsLabelledSplitsNinetyTenAndRepeatsWithTheSameSeed() {
        Map<String, Object> first = ExperimentService.simulate(0.10, 0.05, 3000, 42), again = ExperimentService.simulate(0.10, 0.05, 3000, 42);
        assertThat(first.get("simulated")).isEqualTo(true);
        assertThat(first).isEqualTo(again);
        assertThat(((Map<?, ?>) first.get("control")).get("customers")).isEqualTo(300);
        assertThat(((Map<?, ?>) first.get("offer")).get("customers")).isEqualTo(2700);
        assertThatThrownBy(() -> ExperimentService.simulate(0.95, 0.05, 3000, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExperimentService.simulate(0.10, 0.05, 5, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
