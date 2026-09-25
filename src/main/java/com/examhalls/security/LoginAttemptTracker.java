package com.examhalls.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection: after {@code maxAttempts} consecutive failures a username is
 * locked for {@code lockDuration}. A partial run of failures is forgotten after the same duration
 * without a new failure, and expired entries are pruned, so the map stays small. Per application
 * instance (a desktop app has one user at a time); usernames are compared case-insensitively.
 */
public final class LoginAttemptTracker {

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration lockDuration;
    private final Clock clock;

    public LoginAttemptTracker(int maxAttempts, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public LoginAttemptTracker() {
        this(5, Duration.ofMinutes(5), Clock.systemUTC());
    }

    /** Remaining lock time, or {@link Duration#ZERO} if the user may try to log in. */
    public Duration remainingLock(String username) {
        Attempts a = attempts.get(key(username));
        if (a == null || a.lockedUntil() == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), a.lockedUntil());
        return left.isNegative() ? Duration.ZERO : left;
    }

    public void recordFailure(String username) {
        prune();
        attempts.compute(key(username), (k, a) -> {
            int failures = (a == null || isExpired(a) ? 0 : a.failures()) + 1;
            Instant now = clock.instant();
            Instant lockedUntil = failures >= maxAttempts ? now.plus(lockDuration) : null;
            return new Attempts(failures, now, lockedUntil);
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /** Number of usernames currently tracked (failures or lock). */
    public int trackedUsernames() {
        return attempts.size();
    }

    /** Drops expired locks so the map cannot grow without bound. */
    private void prune() {
        attempts.values().removeIf(this::isExpired);
    }

    private boolean isExpired(Attempts a) {
        Instant end = a.lockedUntil() != null ? a.lockedUntil() : a.lastFailure().plus(lockDuration);
        return !clock.instant().isBefore(end);
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
