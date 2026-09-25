package com.examhalls.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Tracks user activity for the session idle timeout. The UI calls {@link #touch()} on every key
 * press, click or scroll and polls {@link #isExpired()}; a zero timeout disables the check.
 * Pure logic (no JavaFX) so it can be unit-tested with a fixed clock.
 */
public final class IdleTimer {

    private final Duration timeout;
    private final Clock clock;
    private volatile Instant lastActivity;

    public IdleTimer(Duration timeout, Clock clock) {
        this.timeout = timeout;
        this.clock = clock;
        this.lastActivity = clock.instant();
    }

    public void touch() {
        lastActivity = clock.instant();
    }

    public boolean isEnabled() {
        return !timeout.isZero() && !timeout.isNegative();
    }

    public boolean isExpired() {
        return isEnabled() && !clock.instant().isBefore(lastActivity.plus(timeout));
    }

    public Duration timeout() {
        return timeout;
    }
}
