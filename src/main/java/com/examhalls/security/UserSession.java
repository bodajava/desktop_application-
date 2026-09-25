package com.examhalls.security;

import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently signed-in user for this desktop instance. Thread-safe: background
 * JavaFX Tasks read the same session the UI thread wrote.
 */
public final class UserSession {

    private static final UserSession INSTANCE = new UserSession();

    private final AtomicReference<AuthenticatedUser> current = new AtomicReference<>();

    private UserSession() {
    }

    public static UserSession get() {
        return INSTANCE;
    }

    /** Called by AuthService after a successful login. */
    public void start(AuthenticatedUser user) {
        current.set(user);
    }

    public void end() {
        current.set(null);
    }

    public Optional<AuthenticatedUser> currentUser() {
        return Optional.ofNullable(current.get());
    }

    public boolean isAuthenticated() {
        return current.get() != null;
    }

    /** @throws AppException NOT_AUTHENTICATED if nobody is signed in */
    public AuthenticatedUser requireUser() {
        AuthenticatedUser user = current.get();
        if (user == null) {
            throw new AppException(ErrorCode.NOT_AUTHENTICATED);
        }
        return user;
    }

    /** @throws AppException NOT_AUTHENTICATED / ACCESS_DENIED */
    public AuthenticatedUser require(Permission permission) {
        AuthenticatedUser user = requireUser();
        if (!user.can(permission)) {
            throw new AppException(ErrorCode.ACCESS_DENIED,
                    user.username() + " (" + user.role() + ") lacks " + permission);
        }
        return user;
    }
}
