package com.examhalls.service;

import com.examhalls.dao.UserDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.User;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.CredentialPolicy;
import com.examhalls.security.LoginAttemptTracker;
import com.examhalls.security.PasswordHasher;
import com.examhalls.security.RoleType;
import com.examhalls.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sign-in / sign-out / password change.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Unknown user and wrong password produce the same error and take the same time
 *       (a dummy BCrypt check runs for unknown users), so usernames cannot be enumerated.</li>
 *   <li>5 consecutive failures lock the username for 5 minutes ({@link LoginAttemptTracker}).</li>
 *   <li>Password char[] arrays are wiped after use; hashes never leave this class.</li>
 *   <li>Input is validated before any database access (username format, password length within
 *       BCrypt's 72-byte limit). Invalid input costs the same time as a wrong password and counts
 *       as a failed attempt. SQL injection is impossible anyway (bind variables only).</li>
 *   <li>Usernames are sanitised before they are written to the log (no forged log lines).</li>
 * </ul>
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt(cost 12) of a random, discarded value — used only to equalise timing. */
    private static final String DUMMY_HASH = "$2a$12$zNXLNywxJNKpJjes1azDC.X.VTArpt7tXbOW4RAGc0vvjQWiyj5Y2";
    private static final char[] DUMMY_PASSWORD = "timing-equaliser-0".toCharArray();

    private final UserDao userDao;
    private final PasswordHasher hasher;
    private final LoginAttemptTracker attempts;
    private final UserSession session;

    public AuthService(UserDao userDao, PasswordHasher hasher, LoginAttemptTracker attempts, UserSession session) {
        this.userDao = userDao;
        this.hasher = hasher;
        this.attempts = attempts;
        this.session = session;
    }

    /**
     * Verifies the credentials and starts the session. The {@code password} array is wiped.
     *
     * @throws AppException INVALID_CREDENTIALS, ACCOUNT_LOCKED or DATABASE_UNAVAILABLE
     */
    public AuthenticatedUser login(String rawUsername, char[] password) {
        String username = rawUsername == null ? "" : rawUsername.strip();
        try {
            Duration locked = attempts.remainingLock(username);
            if (!locked.isZero()) {
                long minutes = Math.max(1, (locked.toSeconds() + 59) / 60);
                throw new AppException(ErrorCode.ACCOUNT_LOCKED, ErrorCode.ACCOUNT_LOCKED.messageKey(),
                        "locked for " + locked.toSeconds() + "s", null, minutes);
            }

            if (!CredentialPolicy.isValidUsername(username) || !CredentialPolicy.isPlausiblePassword(password)) {
                // Malformed input never reaches the database, but costs the same time and counts.
                hasher.verify(DUMMY_PASSWORD, DUMMY_HASH);
                fail(username, "malformed input");
            }

            Optional<User> found = userDao.findByUsernameWithHash(username);
            boolean ok = hasher.verify(password, found.map(User::passwordHash).orElse(DUMMY_HASH))
                    && found.isPresent();

            if (!ok) {
                fail(username, found.isPresent() ? "wrong password" : "unknown user");
            }

            User user = found.get();
            AuthenticatedUser authenticated = new AuthenticatedUser(user.userId(), user.username(), user.fullName(),
                    RoleType.fromDb(user.roleName()), user.teacherId(), user.studentId(), user.mustChangePassword(),
                    Instant.now());
            attempts.recordSuccess(username);
            session.start(authenticated);
            log.info("User '{}' signed in as {}", authenticated.username(), authenticated.role());
            return authenticated;
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    private void fail(String username, String why) {
        attempts.recordFailure(username);
        log.warn("Failed login for '{}' ({})", CredentialPolicy.forLog(username), why);
        throw new AppException(ErrorCode.INVALID_CREDENTIALS);
    }

    public void logout() {
        session.currentUser().ifPresent(u -> log.info("User '{}' signed out", u.username()));
        session.end();
    }

    /**
     * Changes the signed-in user's password. Both arrays are wiped.
     *
     * @throws AppException NOT_AUTHENTICATED, INVALID_CREDENTIALS (wrong current password), WEAK_PASSWORD
     */
    public void changePassword(char[] currentPassword, char[] newPassword) {
        try {
            AuthenticatedUser me = session.requireUser();
            User user = userDao.findByUsernameWithHash(me.username())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_AUTHENTICATED));
            if (!hasher.verify(currentPassword, user.passwordHash())) {
                throw new AppException(ErrorCode.INVALID_CREDENTIALS);
            }
            applyNewPassword(me, newPassword);
        } finally {
            PasswordHasher.wipe(currentPassword);
            PasswordHasher.wipe(newPassword);
        }
    }

    /**
     * For the forced first-login change: the session is already authenticated (that stands in for
     * the current password), so no re-verification is asked of the user.
     */
    public void changePasswordForCurrentSession(char[] newPassword) {
        try {
            applyNewPassword(session.requireUser(), newPassword);
        } finally {
            PasswordHasher.wipe(newPassword);
        }
    }

    private void applyNewPassword(AuthenticatedUser me, char[] newPassword) {
        if (!PasswordHasher.isStrongEnough(newPassword)) {
            throw new AppException(ErrorCode.WEAK_PASSWORD);
        }
        userDao.updatePasswordHash(me.userId(), hasher.hash(newPassword));
        if (me.mustChangePassword()) {
            session.start(me.withMustChangePassword(false));
        }
        log.info("User '{}' changed their password", me.username());
    }
}
