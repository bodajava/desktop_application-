package com.examhalls.model;

import java.time.LocalDateTime;

/**
 * A system user as read from V_USERS. {@code passwordHash} is only populated by
 * {@code UserDao.findByUsernameWithHash}; toString never prints it.
 */
public record User(Long userId, String username, String passwordHash, String fullName,
                   Long roleId, String roleName, Long teacherId, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public User withoutHash() {
        return new User(userId, username, null, fullName, roleId, roleName, teacherId, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "User[userId=" + userId + ", username=" + username + ", fullName=" + fullName
                + ", roleName=" + roleName + ", teacherId=" + teacherId + ", passwordHash=" + (passwordHash == null ? "null" : "***") + "]";
    }
}
