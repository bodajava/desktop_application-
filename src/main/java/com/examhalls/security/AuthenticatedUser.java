package com.examhalls.security;

import java.time.Instant;

/**
 * Immutable identity of the signed-in user (never holds the password hash).
 * {@code teacherId} links TEACHER / COMMITTEE_HEAD logins to their TEACHERS row; null for office staff.
 */
public record AuthenticatedUser(long userId, String username, String fullName, RoleType role, Long teacherId,
                                Instant loginTime) {

    public boolean can(Permission permission) {
        return role.has(permission);
    }
}
