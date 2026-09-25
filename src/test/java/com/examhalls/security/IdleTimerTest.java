package com.examhalls.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IdleTimerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-01-10T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void expiresAfterTimeoutWithoutActivity() {
        IdleTimer t = new IdleTimer(Duration.ofMinutes(15), clock);
        assertTrue(t.isEnabled());
        advance(Duration.ofMinutes(14).plusSeconds(59));
        assertFalse(t.isExpired());
        advance(Duration.ofSeconds(1));
        assertTrue(t.isExpired());
    }

    @Test
    void activityRestartsTheCountdown() {
        IdleTimer t = new IdleTimer(Duration.ofMinutes(15), clock);
        advance(Duration.ofMinutes(10));
        t.touch();
        advance(Duration.ofMinutes(10));
        assertFalse(t.isExpired());
        advance(Duration.ofMinutes(5));
        assertTrue(t.isExpired());
    }

    @Test
    void zeroDisablesTheTimeout() {
        IdleTimer t = new IdleTimer(Duration.ZERO, clock);
        advance(Duration.ofDays(1));
        assertFalse(t.isEnabled());
        assertFalse(t.isExpired());
    }
}
