package com.examhalls.security;

import java.time.Instant;

/**
 * Immutable identity of the signed-in user (never holds the password hash).
 * {@code teacherId} links TEACHER / COMMITTEE_HEAD logins to their TEACHERS row; {@code studentId}
 * links STUDENT logins to their STUDENTS row. Both are null for office staff.
 */
public record AuthenticatedUser(long userId, String username, String fullName, RoleType role, Long teacherId,
                                Long studentId, boolean mustChangePassword, Instant loginTime) {

    public boolean can(Permission permission) {
        return role.has(permission);
    }

    public AuthenticatedUser withMustChangePassword(boolean value) {
        return new AuthenticatedUser(userId, username, fullName, role, teacherId, studentId, value, loginTime);
    }
}
