package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Counting the drawer by denomination and the decisions allowed for a cash difference. */
class ShiftCashTest {
    @Test void notesAreAddedUpByDenomination() {
        var count = ShiftService.countCash(Map.of("500000", 2, "50000", 3, "1000", 0, "500", 4));
        assertThat(count.total()).isEqualByComparingTo("1152000");
        assertThat(count.json()).isEqualTo("{\"500000\":2,\"50000\":3,\"500\":4}");
        assertThat(ShiftService.countCash(Map.of())).isNull();
    }

    @Test void unknownDenominationsAndNegativeCountsAreRefused() {
        assertThatThrownBy(() -> ShiftService.countCash(Map.of("300000", 1))).hasMessageContaining("Mệnh giá");
        assertThatThrownBy(() -> ShiftService.countCash(Map.of("abc", 1))).hasMessageContaining("Mệnh giá");
        assertThatThrownBy(() -> ShiftService.countCash(Map.of("10000", -1))).hasMessageContaining("không hợp lệ");
    }

    @Test void decisionsFollowTheDifference() {
        assertThat(ShiftService.allowedDecisions(new BigDecimal("-50000"))).containsExactly("STAFF_REPAY", "STORE_EXPENSE");
        assertThat(ShiftService.allowedDecisions(new BigDecimal("30000"))).containsExactly("ADD_TO_FUND");
        assertThat(ShiftService.allowedDecisions(new BigDecimal("-10000"))).as("within ±10.000 đ").containsExactly("CORRECTED");
        assertThat(ShiftService.allowedDecisions(null)).hasSize(4);
    }
}
