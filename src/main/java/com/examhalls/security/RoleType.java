package com.examhalls.security;

import com.examhalls.util.Messages;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.examhalls.security.Permission.*;

/**
 * Application roles. Constant names must equal ROLES.ROLE_NAME in the database.
 * Permission matrix:
 * <pre>
 *                      ADMIN  CONTROL  HEAD  TEACHER
 * MANAGE_USERS           x
 * MANAGE_MASTER_DATA     x      x
 * GENERATE_SEATING       x      x
 * ALLOCATE_PROCTORS      x      x
 * SUBSTITUTE_PROCTOR     x      x
 * MARK_ATTENDANCE        x      x       x
 * VIEW_ALL_SCHEDULES     x      x       x
 * VIEW_OWN_SCHEDULE      x      x       x      x
 * VIEW_REPORTS           x      x       x
 * VIEW_AUDIT             x      x
 * </pre>
 */
public enum RoleType {
    SCHOOL_ADMIN(EnumSet.allOf(Permission.class)),
    CONTROL_OFFICER(EnumSet.of(MANAGE_MASTER_DATA, GENERATE_SEATING, ALLOCATE_PROCTORS, SUBSTITUTE_PROCTOR,
            MARK_ATTENDANCE, VIEW_ALL_SCHEDULES, VIEW_OWN_SCHEDULE, VIEW_REPORTS, VIEW_AUDIT)),
    COMMITTEE_HEAD(EnumSet.of(MARK_ATTENDANCE, VIEW_ALL_SCHEDULES, VIEW_OWN_SCHEDULE, VIEW_REPORTS)),
    TEACHER(EnumSet.of(VIEW_OWN_SCHEDULE));

    private final Set<Permission> permissions;

    RoleType(Set<Permission> permissions) {
        this.permissions = EnumSet.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return EnumSet.copyOf(permissions);
    }

    public String displayName(Locale locale) {
        return Messages.get(locale, "role." + name(), null);
    }

    /** Maps ROLES.ROLE_NAME; unknown names fail loudly rather than granting anything. */
    public static RoleType fromDb(String roleName) {
        return RoleType.valueOf(roleName.trim().toUpperCase(Locale.ROOT));
    }
}
