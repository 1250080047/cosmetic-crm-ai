package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Product;
import com.example.PhanTichAI.entity.Strategy;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class GiftMatcherTest {
    private Product product(String name) {
        Product result = new Product();
        result.setProductName(name);
        result.setIsActive(true);
        return result;
    }

    @Test
    void matchingRespectsBrandTypeAndSize() {
        Product cetaphil = product("Sữa rửa mặt Cetaphil tạo bọt kiềm dầu ngừa mụn 237ml");
        assertThat(GiftMatcher.matches("Tặng 1 sữa rửa mặt", cetaphil)).isTrue();
        assertThat(GiftMatcher.matches("Tặng 1 sữa rửa mặt Cetaphil", cetaphil)).isTrue();
        assertThat(GiftMatcher.matches("Tặng 1 sữa rửa mặt Aveeno", cetaphil)).isFalse();
        assertThat(GiftMatcher.matches("Tặng sữa rửa mặt Cetaphil 100ml", cetaphil)).isFalse();
        assertThat(GiftMatcher.matches("Mặt nạ dưỡng ẩm", cetaphil)).isFalse();
    }

    @Test
    void voucherAndSampleCannotBecomeAnArbitraryFullSizeProduct() {
        Product cleanser = product("Sữa rửa mặt Cetaphil 237ml");
        assertThat(GiftMatcher.matches("Voucher mua sữa rửa mặt", cleanser)).isFalse();
        assertThat(GiftMatcher.matches("Sample sữa rửa mặt", cleanser)).isFalse();
        cleanser.setIsActive(false);
        assertThat(GiftMatcher.matches("Sữa rửa mặt", cleanser)).isFalse();
    }

    @Test
    void explicitQuantityIsRespectedWithoutReadingTheBottleSizeAsQuantity() {
        assertThat(GiftMatcher.quantityLimit("Tặng 2 sữa rửa mặt 237ml")).isEqualTo(2);
        assertThat(GiftMatcher.quantityLimit("Sữa rửa mặt 237ml")).isEqualTo(1);
    }

    @Test
    void customerSpeechNeverReadsInternalSegmentNames() {
        String speech = CustomerLanguage.cashierScript(
                "Dạ em chào chị! Ưu đãi đặc quyền của nhóm Khách Ngủ Đông / Rời Bỏ ạ!",
                new BigDecimal("8"), "Mặt nạ dưỡng ẩm");
        assertThat(speech).contains("anh/chị", "8%", "Mặt nạ dưỡng ẩm");
        assertThat(CustomerLanguage.normalize(speech)).doesNotContain("ngu dong", "roi bo", "phan khuc", "nhom");
        assertThat(CustomerLanguage.segment("Khách Ngủ Đông / Rời Bỏ")).isEqualTo("Khách hàng ngủ đông");
        assertThat(CustomerLanguage.segment("VIP / Champions")).isEqualTo("Khách hàng VIP");
    }

    @Test
    void presentingAnExistingStrategyDoesNotModifyItsStoredTextOrAmounts() {
        Strategy stored = new Strategy();
        stored.setId(9);
        stored.setSegmentId(3);
        stored.setTitle("Chiến dịch ưu đãi - Khách Ngủ Đông / Rời Bỏ");
        stored.setDiscountPercent(new BigDecimal("8"));
        stored.setGiftDescription("Mặt nạ dưỡng ẩm");
        stored.setCashierScript("Dạ em chào chị! Ưu đãi của nhóm Khách Ngủ Đông / Rời Bỏ ạ!");
        Strategy view = CustomerLanguage.presentStrategy(stored);
        assertThat(view).isNotSameAs(stored);
        assertThat(view.getTitle()).contains("Khách hàng ngủ đông").doesNotContain("Ngủ Đông / Rời Bỏ");
        assertThat(view.getDiscountPercent()).isEqualByComparingTo(stored.getDiscountPercent());
        assertThat(view.getSegmentId()).isEqualTo(stored.getSegmentId());
        assertThat(stored.getTitle()).contains("Ngủ Đông");
        assertThat(stored.getCashierScript()).contains("Ngủ Đông");
    }
}
