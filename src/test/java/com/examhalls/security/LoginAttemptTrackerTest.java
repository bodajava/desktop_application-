package com.examhalls.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptTrackerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-01-10T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterDuration() {
        LoginAttemptTracker t = new LoginAttemptTracker(3, Duration.ofMinutes(5), clock);
        t.recordFailure("Admin");
        t.recordFailure("admin");
        assertTrue(t.remainingLock("admin").isZero());
        t.recordFailure("ADMIN ");
        assertEquals(Duration.ofMinutes(5), t.remainingLock("admin"));

        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertTrue(t.remainingLock("admin").isZero());
        t.recordFailure("admin");                       // counter restarted after the lock expired
        assertTrue(t.remainingLock("admin").isZero());
    }

    @Test
    void successResetsCounter() {
        LoginAttemptTracker t = new LoginAttemptTracker(2, Duration.ofMinutes(5), clock);
        t.recordFailure("control");
        t.recordSuccess("control");
        t.recordFailure("control");
        assertTrue(t.remainingLock("control").isZero());
    }

    @Test
    void partialFailuresAreForgottenAndPruned() {
        LoginAttemptTracker t = new LoginAttemptTracker(5, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 4; i++) {
            t.recordFailure("head");
        }
        t.recordFailure("someone-else");
        assertEquals(2, t.trackedUsernames());

        now.set(now.get().plus(Duration.ofMinutes(6)));   // quiet period longer than the window
        t.recordFailure("head");                          // prunes both stale entries, starts at 1
        assertEquals(1, t.trackedUsernames());
        for (int i = 0; i < 3; i++) {
            t.recordFailure("head");
        }
        assertTrue(t.remainingLock("head").isZero(), "4 failures after the reset must not lock");
        t.recordFailure("head");
        assertFalse(t.remainingLock("head").isZero());
    }
}
