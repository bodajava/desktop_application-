package com.examhalls.model;

import com.examhalls.security.RoleType;

import java.time.LocalDateTime;

/** A user as shown on the users screen (never carries the password hash). */
public record UserAccount(long userId, String username, String fullName, RoleType role, Long teacherId,
                          String teacherLabel, LocalDateTime createdAt) {
}
