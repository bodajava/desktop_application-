package com.examhalls.exception;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every failure the UI may show. Codes 20001-20030 mirror PKG_APP_CTX in 02_triggers.sql;
 * the rest map standard Oracle errors or application-level conditions.
 * Localised text lives in {@code i18n/messages*.properties} under {@code error.<NAME>}.
 */
public enum ErrorCode {
    // ---- PL/SQL application errors (RAISE_APPLICATION_ERROR) ----
    HARD_DELETE_BLOCKED(20001),
    HAS_FUTURE_DUTIES(20002),
    AUDIT_IMMUTABLE(20003),
    ROOM_NOT_USABLE(20004),
    TEACHER_NOT_ACTIVE(20005),
    NOT_FOUND(20010),
    INSUFFICIENT_CAPACITY(20011),
    STUDENT_CLASH(20012),
    NO_STUDENTS(20013),
    NO_ELIGIBLE_TEACHER(20020),
    TEACHER_INELIGIBLE(20021),
    INVALID_STATE(20022),
    INVALID_ARGUMENT(20030),

    // ---- Standard Oracle errors ----
    DUPLICATE_VALUE(1),          // ORA-00001 unique constraint
    RECORD_LOCKED(54),           // ORA-00054 / ORA-30006 resource busy
    NOT_NULL_VIOLATION(1400),    // ORA-01400
    CHECK_VIOLATION(2290),       // ORA-02290
    PARENT_NOT_FOUND(2291),      // ORA-02291 FK parent missing
    CHILD_RECORDS_EXIST(2292),   // ORA-02292 FK child exists
    VALUE_TOO_LARGE(12899),      // ORA-12899

    // ---- Application-level ----
    DATABASE_UNAVAILABLE(-1),
    DATABASE_LOGIN_FAILED(-14),
    INVALID_CREDENTIALS(-2),
    ACCOUNT_LOCKED(-3),
    ACCESS_DENIED(-4),
    NOT_AUTHENTICATED(-5),
    WEAK_PASSWORD(-6),
    VALIDATION_FAILED(-7),
    REPORT_FONT_MISSING(-8),
    EXPORT_FAILED(-9),
    CANNOT_DELETE_SELF(-10),
    LAST_ADMIN(-11),
    TEACHER_LINK_REQUIRED(-12),
    EXAM_HAS_SEATING(-13),
    UNEXPECTED(-99);

    private final int oracleCode;

    ErrorCode(int oracleCode) {
        this.oracleCode = oracleCode;
    }

    public int oracleCode() {
        return oracleCode;
    }

    public String messageKey() {
        return "error." + name();
    }

    public static Optional<ErrorCode> fromOracleCode(int code) {
        int normalized = code == 30006 ? 54 : code;   // WAIT n timeout == resource busy
        return Arrays.stream(values())
                .filter(e -> e.oracleCode > 0 && e.oracleCode == normalized)
                .findFirst();
    }
}
