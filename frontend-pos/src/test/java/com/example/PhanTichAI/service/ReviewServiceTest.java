package com.example.PhanTichAI.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    @Test void commentsAreCleanedAndLimited() {
        assertThat(ReviewService.clean("  Thơm\u0007   lâu,\t\tmịn  ")).isEqualTo("Thơm lâu, mịn");
        assertThat(ReviewService.clean(null)).isEmpty();
        assertThat(ReviewService.clean("a\n\n\n\nb")).isEqualTo("a\n\nb");
        assertThatThrownBy(() -> ReviewService.clean("x".repeat(501))).hasMessageContaining("500");
    }

    @Test void onlyProductsBoughtAtTheStoreCanBeReviewed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        AiIntegrationService ai = mock(AiIntegrationService.class);
        ReviewService reviews = new ReviewService(jdbc, ai, null, Clock.systemUTC());
        assertThatThrownBy(() -> reviews.save(33627, 5, 4, "Rất thích")).hasMessageContaining("đã mua tại cửa hàng");
        verifyNoInteractions(ai);
        assertThatThrownBy(() -> reviews.save(33627, 5, 6, "")).hasMessageContaining("1 đến 5 sao");
    }
}
