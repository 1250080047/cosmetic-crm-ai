package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A goods receipt is checked before anything is written. */
class InventoryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InventoryService inventory = new InventoryService(jdbc,
            Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static InventoryService.Receipt receipt(InventoryService.Line... lines) {
        return new InventoryService.Receipt("Công ty ABC", "", List.of(lines));
    }

    @Test void aGoodLineIsAccepted() {
        var lines = inventory.validate(receipt(new InventoryService.Line(1, " L2611A ", TODAY.plusMonths(18), 24, new BigDecimal("65000"))));
        assertThat(lines).singleElement().satisfies(line -> assertThat(line.lotNo()).isEqualTo("L2611A"));
    }

    @Test void expiredOrTodayExpiringGoodsAreNotReceived() {
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "L1", TODAY, 5, null))))
                .hasMessageContaining("Dòng 1").hasMessageContaining("hết hạn");
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "L1", TODAY.minusDays(3), 5, null))))
                .hasMessageContaining("hết hạn");
    }

    @Test void lotNumberQuantityAndProductAreRequired() {
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "", TODAY.plusDays(90), 5, null)))).hasMessageContaining("số lô");
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "L 1;DROP", TODAY.plusDays(90), 5, null)))).hasMessageContaining("số lô");
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "L1", TODAY.plusDays(90), 0, null)))).hasMessageContaining("số lượng");
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(0, "L1", TODAY.plusDays(90), 5, null)))).hasMessageContaining("sản phẩm");
        assertThatThrownBy(() -> inventory.validate(receipt(new InventoryService.Line(1, "L1", TODAY.plusDays(90), 5, new BigDecimal("-1"))))).hasMessageContaining("giá nhập");
        assertThatThrownBy(() -> inventory.validate(new InventoryService.Receipt("", "", List.of()))).hasMessageContaining("ít nhất một dòng");
    }

    @Test void nothingIsWrittenForABadReceipt() {
        assertThatThrownBy(() -> inventory.receive(receipt(new InventoryService.Line(1, "L1", TODAY.minusDays(1), 5, null)), 3))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void aLineWithoutExpiryDateIsAllowed() {
        assertThat(inventory.validate(receipt(new InventoryService.Line(1, "L1", null, 5, null)))).hasSize(1);
    }
}
