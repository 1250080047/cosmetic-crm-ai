package com.example.PhanTichAI.security;

import com.example.PhanTichAI.security.MemberRateLimiter.Action;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MemberRateLimiterTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test void fiveSignUpsPerDevicePerHour() {
        TestClock clock = new TestClock();
        MemberRateLimiter limiter = new MemberRateLimiter(clock);
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire(Action.REGISTER, "203.0.113.7")).isTrue();
        assertThat(limiter.tryAcquire(Action.REGISTER, "203.0.113.7")).isFalse();
        assertThat(limiter.tryAcquire(Action.REGISTER, "198.51.100.2")).as("another phone").isTrue();
        assertThat(limiter.tryAcquire(Action.LOGIN, "203.0.113.7")).as("signing in is counted separately").isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertThat(limiter.tryAcquire(Action.REGISTER, "203.0.113.7")).as("an hour later").isTrue();
    }

    @Test void twentySignInsPerTenMinutes() {
        TestClock clock = new TestClock();
        MemberRateLimiter limiter = new MemberRateLimiter(clock);
        for (int i = 0; i < 20; i++) assertThat(limiter.tryAcquire(Action.LOGIN, "203.0.113.7")).isTrue();
        assertThat(limiter.tryAcquire(Action.LOGIN, "203.0.113.7")).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(limiter.tryAcquire(Action.LOGIN, "203.0.113.7")).isTrue();
    }
}
