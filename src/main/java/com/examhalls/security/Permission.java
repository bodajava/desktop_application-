package com.examhalls.security;

/** Fine-grained actions checked by services before touching data. */
public enum Permission {
    MANAGE_USERS,
    MANAGE_MASTER_DATA,      // rooms, teachers, courses, students, exam schedule
    GENERATE_SEATING,
    ALLOCATE_PROCTORS,
    SUBSTITUTE_PROCTOR,
    MARK_ATTENDANCE,
    VIEW_ALL_SCHEDULES,
    VIEW_OWN_SCHEDULE,
    VIEW_REPORTS,
    VIEW_AUDIT
}
