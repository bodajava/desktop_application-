--------------------------------------------------------------------------------
-- Exam Halls & Proctoring Allocation Management System
-- 02_triggers.sql  —  Soft delete, audit timestamps, integrity guards
--------------------------------------------------------------------------------
-- How soft delete works
--   Oracle cannot turn a DELETE on a *table* into an UPDATE: a BEFORE DELETE row
--   trigger can only let the delete through or raise an error. So:
--     1. DELETE FROM v_users / v_teachers / v_rooms  -> INSTEAD OF trigger sets IS_DELETED = 'Y'
--     2. DELETE FROM users / teachers / rooms        -> blocked (ORA-20001) unless
--        pkg_app_ctx.g_allow_hard_delete is TRUE (maintenance / purge scripts only)
--   Restoring a row: UPDATE <table> SET is_deleted = 'N' WHERE ...
--------------------------------------------------------------------------------

SET DEFINE OFF

-- ============================================================================
-- Session context + application error codes (spec-only package, no body needed)
-- ============================================================================
CREATE OR REPLACE PACKAGE pkg_app_ctx AS
    -- Set to TRUE only inside purge / maintenance scripts, then reset to FALSE.
    g_allow_hard_delete  BOOLEAN := FALSE;

    -- Application error codes (RAISE_APPLICATION_ERROR). The Java layer maps these
    -- to user-friendly messages: SQLException.getErrorCode() returns the positive value.
    e_hard_delete_blocked   CONSTANT PLS_INTEGER := -20001;
    e_has_future_duties     CONSTANT PLS_INTEGER := -20002;
    e_audit_immutable       CONSTANT PLS_INTEGER := -20003;
    e_room_not_usable       CONSTANT PLS_INTEGER := -20004;
    e_teacher_not_active    CONSTANT PLS_INTEGER := -20005;
    e_not_found             CONSTANT PLS_INTEGER := -20010;
    e_insufficient_capacity CONSTANT PLS_INTEGER := -20011;
    e_student_clash         CONSTANT PLS_INTEGER := -20012;
    e_no_students           CONSTANT PLS_INTEGER := -20013;
    e_no_eligible_teacher   CONSTANT PLS_INTEGER := -20020;
    e_teacher_ineligible    CONSTANT PLS_INTEGER := -20021;
    e_invalid_state         CONSTANT PLS_INTEGER := -20022;
    e_invalid_argument      CONSTANT PLS_INTEGER := -20030;
END pkg_app_ctx;
/

-- ============================================================================
-- USERS
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_users_bi
    BEFORE INSERT ON users
    FOR EACH ROW
BEGIN
    :NEW.username   := TRIM(:NEW.username);
    :NEW.created_at := NVL(:NEW.created_at, SYSDATE);
    :NEW.is_deleted := NVL(:NEW.is_deleted, 'N');
    :NEW.updated_at := NULL;
    :NEW.deleted_at := CASE WHEN :NEW.is_deleted = 'Y' THEN SYSDATE END;
END;
/

CREATE OR REPLACE TRIGGER trg_users_bu
    BEFORE UPDATE ON users
    FOR EACH ROW
BEGIN
    :NEW.created_at := :OLD.created_at;          -- creation time is immutable
    :NEW.updated_at := SYSDATE;
    IF :OLD.is_deleted = 'N' AND :NEW.is_deleted = 'Y' THEN
        :NEW.deleted_at := SYSDATE;
    ELSIF :NEW.is_deleted = 'N' THEN
        :NEW.deleted_at := NULL;                 -- restored
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_users_bd
    BEFORE DELETE ON users
    FOR EACH ROW
BEGIN
    IF NOT pkg_app_ctx.g_allow_hard_delete THEN
        RAISE_APPLICATION_ERROR(pkg_app_ctx.e_hard_delete_blocked,
            'Hard delete of USERS is not allowed. Delete through V_USERS (soft delete).');
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_v_users_iod
    INSTEAD OF DELETE ON v_users
    FOR EACH ROW
BEGIN
    UPDATE users SET is_deleted = 'Y' WHERE user_id = :OLD.user_id;
END;
/

-- ============================================================================
-- TEACHERS
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_teachers_bi
    BEFORE INSERT ON teachers
    FOR EACH ROW
BEGIN
    :NEW.teacher_code  := UPPER(TRIM(:NEW.teacher_code));
    :NEW.hours_balance := NVL(:NEW.hours_balance, 0);
    :NEW.is_deleted    := NVL(:NEW.is_deleted, 'N');
    :NEW.updated_at    := NULL;
    :NEW.deleted_at    := CASE WHEN :NEW.is_deleted = 'Y' THEN SYSDATE END;
END;
/

CREATE OR REPLACE TRIGGER trg_teachers_bu
    BEFORE UPDATE ON teachers
    FOR EACH ROW
DECLARE
    v_future_duties PLS_INTEGER;
BEGIN
    :NEW.teacher_code := UPPER(TRIM(:NEW.teacher_code));
    :NEW.updated_at   := SYSDATE;

    IF :OLD.is_deleted = 'N' AND :NEW.is_deleted = 'Y' THEN
        -- Refuse to archive a teacher who still has upcoming confirmed duties.
        SELECT COUNT(*)
        INTO   v_future_duties
        FROM   supervision_roster r
               JOIN exam_schedule e ON e.exam_id = r.exam_id
        WHERE  r.teacher_id        = :OLD.teacher_id
        AND    r.assignment_status = 'CONFIRMED'
        AND    e.exam_date        >= TRUNC(SYSDATE);

        IF v_future_duties > 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_has_future_duties,
                'Teacher ' || :OLD.teacher_code || ' has ' || v_future_duties ||
                ' upcoming supervision duties. Substitute them before deleting.');
        END IF;
        :NEW.deleted_at := SYSDATE;
    ELSIF :NEW.is_deleted = 'N' THEN
        :NEW.deleted_at := NULL;
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_teachers_bd
    BEFORE DELETE ON teachers
    FOR EACH ROW
BEGIN
    IF NOT pkg_app_ctx.g_allow_hard_delete THEN
        RAISE_APPLICATION_ERROR(pkg_app_ctx.e_hard_delete_blocked,
            'Hard delete of TEACHERS is not allowed. Delete through V_TEACHERS (soft delete).');
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_v_teachers_iod
    INSTEAD OF DELETE ON v_teachers
    FOR EACH ROW
BEGIN
    UPDATE teachers SET is_deleted = 'Y' WHERE teacher_id = :OLD.teacher_id;
END;
/

-- ============================================================================
-- ROOMS
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_rooms_bi
    BEFORE INSERT ON rooms
    FOR EACH ROW
BEGIN
    :NEW.room_code   := UPPER(TRIM(:NEW.room_code));
    :NEW.room_status := UPPER(NVL(:NEW.room_status, 'AVAILABLE'));
    :NEW.is_deleted  := NVL(:NEW.is_deleted, 'N');
    :NEW.updated_at  := NULL;
    :NEW.deleted_at  := CASE WHEN :NEW.is_deleted = 'Y' THEN SYSDATE END;
END;
/

CREATE OR REPLACE TRIGGER trg_rooms_bu
    BEFORE UPDATE ON rooms
    FOR EACH ROW
DECLARE
    v_future_use PLS_INTEGER;
BEGIN
    :NEW.room_code   := UPPER(TRIM(:NEW.room_code));
    :NEW.room_status := UPPER(:NEW.room_status);
    :NEW.updated_at  := SYSDATE;

    -- Taking a room out of service (soft delete, or status leaves AVAILABLE, or the
    -- exam capacity shrinks) is refused while future exams still use it.
    IF (:OLD.is_deleted = 'N' AND :NEW.is_deleted = 'Y')
       OR (:OLD.room_status = 'AVAILABLE' AND :NEW.room_status <> 'AVAILABLE')
       OR (:NEW.exam_capacity < :OLD.exam_capacity)
    THEN
        SELECT COUNT(*)
        INTO   v_future_use
        FROM   exam_schedule e
        WHERE  e.exam_date >= TRUNC(SYSDATE)
        AND   (EXISTS (SELECT 1 FROM seating_allocation s
                       WHERE  s.exam_id = e.exam_id AND s.room_id = :OLD.room_id)
            OR EXISTS (SELECT 1 FROM supervision_roster r
                       WHERE  r.exam_id = e.exam_id AND r.room_id = :OLD.room_id
                       AND    r.assignment_status = 'CONFIRMED'));

        IF v_future_use > 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_has_future_duties,
                'Room ' || :OLD.room_code || ' is used by ' || v_future_use ||
                ' upcoming exam(s). Re-generate their seating first.');
        END IF;
    END IF;

    IF :OLD.is_deleted = 'N' AND :NEW.is_deleted = 'Y' THEN
        :NEW.deleted_at := SYSDATE;
    ELSIF :NEW.is_deleted = 'N' THEN
        :NEW.deleted_at := NULL;
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_rooms_bd
    BEFORE DELETE ON rooms
    FOR EACH ROW
BEGIN
    IF NOT pkg_app_ctx.g_allow_hard_delete THEN
        RAISE_APPLICATION_ERROR(pkg_app_ctx.e_hard_delete_blocked,
            'Hard delete of ROOMS is not allowed. Delete through V_ROOMS (soft delete).');
    END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_v_rooms_iod
    INSTEAD OF DELETE ON v_rooms
    FOR EACH ROW
BEGIN
    UPDATE rooms SET is_deleted = 'Y' WHERE room_id = :OLD.room_id;
END;
/

-- ============================================================================
-- SEATING_ALLOCATION — a seat can only be placed in an active, available room
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_seating_biu
    BEFORE INSERT OR UPDATE OF room_id ON seating_allocation
    FOR EACH ROW
DECLARE
    v_status  rooms.room_status%TYPE;
    v_deleted rooms.is_deleted%TYPE;
BEGIN
    SELECT room_status, is_deleted
    INTO   v_status, v_deleted
    FROM   rooms
    WHERE  room_id = :NEW.room_id;

    IF v_deleted = 'Y' OR v_status <> 'AVAILABLE' THEN
        RAISE_APPLICATION_ERROR(pkg_app_ctx.e_room_not_usable,
            'Room ' || :NEW.room_id || ' is not available for seating (status ' ||
            v_status || ', deleted ' || v_deleted || ').');
    END IF;
    :NEW.attendance_status := UPPER(NVL(:NEW.attendance_status, 'ABSENT_PENDING'));
END;
/

-- ============================================================================
-- SUPERVISION_ROSTER — only active teachers and usable rooms can be assigned
-- (the full business rules live in PKG_SUPERVISION; this is the last line of defence
--  for rows inserted by hand or by other tools)
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_roster_biu
    BEFORE INSERT OR UPDATE OF teacher_id, room_id ON supervision_roster
    FOR EACH ROW
DECLARE
    v_teacher_deleted teachers.is_deleted%TYPE;
    v_room_status     rooms.room_status%TYPE;
    v_room_deleted    rooms.is_deleted%TYPE;
BEGIN
    IF :NEW.assignment_status = 'CONFIRMED' THEN
        SELECT is_deleted INTO v_teacher_deleted FROM teachers WHERE teacher_id = :NEW.teacher_id;
        IF v_teacher_deleted = 'Y' THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_teacher_not_active,
                'Teacher ' || :NEW.teacher_id || ' is deleted and cannot be assigned.');
        END IF;

        SELECT room_status, is_deleted INTO v_room_status, v_room_deleted
        FROM   rooms WHERE room_id = :NEW.room_id;
        IF v_room_deleted = 'Y' OR v_room_status <> 'AVAILABLE' THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_room_not_usable,
                'Room ' || :NEW.room_id || ' is not available for supervision.');
        END IF;
    END IF;
END;
/

-- ============================================================================
-- SUPERVISION_AUDIT — server-side timestamp, append-only
-- ============================================================================
CREATE OR REPLACE TRIGGER trg_audit_bi
    BEFORE INSERT ON supervision_audit
    FOR EACH ROW
BEGIN
    :NEW.audit_timestamp := SYSTIMESTAMP;        -- never trust a client-supplied time
END;
/

CREATE OR REPLACE TRIGGER trg_audit_bud
    BEFORE UPDATE OR DELETE ON supervision_audit
    FOR EACH ROW
BEGIN
    IF NOT pkg_app_ctx.g_allow_hard_delete THEN
        RAISE_APPLICATION_ERROR(pkg_app_ctx.e_audit_immutable,
            'SUPERVISION_AUDIT is append-only; records cannot be modified or deleted.');
    END IF;
END;
/
