package com.examhalls.service;

import com.examhalls.dao.UserDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.User;
import com.examhalls.security.LoginAttemptTracker;
import com.examhalls.security.PasswordHasher;
import com.examhalls.security.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sign-in hardening without a database: a fake {@link UserDao} records every lookup, so the tests
 * can prove that malformed input is rejected before any query is made.
 */
class AuthServiceSecurityTest {

    private static final PasswordHasher HASHER = new PasswordHasher(10);
    private static String controlHash;

    private final List<String> lookups = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-01-10T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final UserSession session = UserSession.get();
    private final AuthService auth = new AuthService(fakeDao(), HASHER,
            new LoginAttemptTracker(5, Duration.ofMinutes(5), clock), session);

    @BeforeAll
    static void hashSeedPassword() {
        controlHash = HASHER.hash("Control@2026".toCharArray());
    }

    @AfterEach
    void endSession() {
        session.end();
    }

    private UserDao fakeDao() {
        return (UserDao) Proxy.newProxyInstance(UserDao.class.getClassLoader(), new Class<?>[]{UserDao.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUsernameWithHash" -> {
                        lookups.add((String) args[0]);
                        yield "control".equalsIgnoreCase((String) args[0])
                                ? Optional.of(new User(2L, "control", controlHash, "Control Officer", 2L,
                                        "CONTROL_OFFICER", null, null, false, null, null))
                                : Optional.empty();
                    }
                    case "updatePasswordHash" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ErrorCode loginError(String username, String password) {
        AppException e = assertThrows(AppException.class,
                () -> auth.login(username, password == null ? null : password.toCharArray()));
        return e.code();
    }

    @ParameterizedTest
    @ValueSource(strings = {"' OR '1'='1", "admin'--", "control' OR 1=1 --", "x'; DROP TABLE users; --",
            "control\u0000", "con trol", "   ", "أحمد", "a"})
    void malformedUsernamesAreRejectedWithoutTouchingTheDatabase(String username) {
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError(username, "Control@2026"));
        assertTrue(lookups.isEmpty(), "no query for " + username);
    }

    @Test
    void emptyAndNullInputIsRejectedWithoutQuery() {
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError(null, "Control@2026"));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("", "Control@2026"));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", ""));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", null));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void overlongPasswordsFailCleanlyInsteadOfCrashingBcrypt() {
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", "a".repeat(100)));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", "س".repeat(40)));   // 80 bytes
        assertTrue(lookups.isEmpty());
    }

    @Test
    void surroundingWhitespaceInTheUsernameIsTrimmed() {
        assertEquals("control", auth.login("  control\t", "Control@2026".toCharArray()).username());
        assertEquals(List.of("control"), lookups);
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameError() {
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", "wrong-password1"));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("nobody", "Control@2026"));
    }

    @Test
    void fifthFailureLocksEvenTheCorrectPasswordUntilTheLockExpires() {
        for (int i = 0; i < 4; i++) {
            assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("control", "wrong-password1"));
        }
        assertEquals(ErrorCode.INVALID_CREDENTIALS, loginError("CONTROL", "a".repeat(100)));  // malformed counts too
        int queriesBefore = lookups.size();
        assertEquals(ErrorCode.ACCOUNT_LOCKED, loginError("control", "Control@2026"));
        assertEquals(queriesBefore, lookups.size(), "a locked username is not even looked up");

        now.set(now.get().plus(Duration.ofMinutes(5)));
        assertNotNull(auth.login("control", "Control@2026".toCharArray()));
        assertTrue(session.isAuthenticated());
    }

    @Test
    void passwordArraysAreWipedOnSuccessAndFailure() {
        char[] good = "Control@2026".toCharArray();
        auth.login("control", good);
        assertArrayEquals(new char[good.length], good);

        char[] bad = "wrong-password1".toCharArray();
        assertThrows(AppException.class, () -> auth.login("control", bad));
        assertArrayEquals(new char[bad.length], bad);

        char[] malformed = "a".repeat(100).toCharArray();
        assertThrows(AppException.class, () -> auth.login("' OR 1=1", malformed));
        assertArrayEquals(new char[malformed.length], malformed);
    }

    @Test
    void logoutClearsTheSessionCompletely() {
        auth.login("control", "Control@2026".toCharArray());
        assertTrue(session.currentUser().isPresent());
        auth.logout();
        assertFalse(session.isAuthenticated());
        assertTrue(session.currentUser().isEmpty());
        assertEquals(ErrorCode.NOT_AUTHENTICATED, assertThrows(AppException.class, session::requireUser).code());
    }

    @Test
    void newPasswordsAreBoundedBeforeHashing() {
        auth.login("control", "Control@2026".toCharArray());
        AppException e = assertThrows(AppException.class,
                () -> auth.changePassword("Control@2026".toCharArray(), ("Ab1" + "x".repeat(97)).toCharArray()));
        assertEquals(ErrorCode.WEAK_PASSWORD, e.code());
    }
}
