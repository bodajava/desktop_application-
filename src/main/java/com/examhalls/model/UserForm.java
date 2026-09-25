package com.examhalls.model;

import com.examhalls.security.RoleType;

/** Editable user fields (create / update). {@code teacherId} is required for TEACHER accounts. */
public record UserForm(String username, String fullName, RoleType role, Long teacherId) {
}
