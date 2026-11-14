package com.example.PhanTichAI.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits how often one device may try to register, sign in or activate a card. Once the member page is on the
 * Internet, this stops someone from creating thousands of fake members or guessing PINs across many phone numbers
 * (each account is also locked after 5 wrong PINs, see MemberService).
 */
@Component
public class MemberRateLimiter {
    public enum Action {
        REGISTER(5, Duration.ofHours(1)),
        LOGIN(20, Duration.ofMinutes(10)),
        ACTIVATE(10, Duration.ofMinutes(10));

        final int limit;
        final Duration window;
        Action(int limit, Duration window) { this.limit = limit; this.window = window; }
    }

    private static final int MAX_TRACKED = 20_000;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    @Autowired public MemberRateLimiter() { this(Clock.systemUTC()); }
    MemberRateLimiter(Clock clock) { this.clock = clock; }

    /** Records an attempt; false when this device already used up its attempts for the window. */
    public synchronized boolean tryAcquire(Action action, String device) {
        Instant now = clock.instant();
        if (attempts.size() > MAX_TRACKED) attempts.values().removeIf(times -> times.isEmpty() || times.peekLast().isBefore(now.minus(Duration.ofHours(1))));
        Deque<Instant> times = attempts.computeIfAbsent(action + "|" + device, key -> new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(action.window))) times.pollFirst();
        if (times.size() >= action.limit) return false;
        times.addLast(now);
        return true;
    }
}
