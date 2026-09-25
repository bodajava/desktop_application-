package com.examhalls.security;

import org.junit.jupiter.api.Test;

import static com.examhalls.security.Permission.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleTypeTest {

    @Test
    void permissionMatrix() {
        assertTrue(RoleType.SCHOOL_ADMIN.has(MANAGE_USERS));
        assertFalse(RoleType.CONTROL_OFFICER.has(MANAGE_USERS));
        assertTrue(RoleType.CONTROL_OFFICER.has(SUBSTITUTE_PROCTOR));
        assertTrue(RoleType.COMMITTEE_HEAD.has(MARK_ATTENDANCE));
        assertFalse(RoleType.COMMITTEE_HEAD.has(ALLOCATE_PROCTORS));
        assertEquals(java.util.Set.of(VIEW_OWN_SCHEDULE), RoleType.TEACHER.permissions());
    }

    @Test
    void unknownDbRoleIsRejectedNotGranted() {
        assertThrows(IllegalArgumentException.class, () -> RoleType.fromDb("SUPERUSER"));
        assertEquals(RoleType.TEACHER, RoleType.fromDb(" teacher "));
    }
}
