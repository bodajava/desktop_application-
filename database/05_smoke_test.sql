--------------------------------------------------------------------------------
-- 05_smoke_test.sql — end-to-end check of the business logic on the seed data.
-- Every step prints PASS/FAIL. Everything is ROLLED BACK at the end, so the
-- seed data is left untouched. Run after install.sql.
--------------------------------------------------------------------------------
SET SERVEROUTPUT ON SIZE UNLIMITED
SET DEFINE OFF

DECLARE
    v_fails     PLS_INTEGER := 0;
    v_math10    NUMBER;
    v_chem11    NUMBER;
    v_seated    PLS_INTEGER;
    v_rooms     PLS_INTEGER;
    v_assigned  PLS_INTEGER;
    v_unfilled  PLS_INTEGER;
    v_cnt       PLS_INTEGER;
    v_roster    supervision_roster%ROWTYPE;
    v_sub       NUMBER;
    v_new       NUMBER;
    v_audit     NUMBER;
    v_admin     NUMBER;
    v_hours     NUMBER;
    v_reason    VARCHAR2(4000);
    v_other_room NUMBER;

    PROCEDURE check_that(p_ok IN BOOLEAN, p_msg IN VARCHAR2) IS
    BEGIN
        IF p_ok THEN
            DBMS_OUTPUT.PUT_LINE('PASS  ' || p_msg);
        ELSE
            DBMS_OUTPUT.PUT_LINE('FAIL  ' || p_msg);
            v_fails := v_fails + 1;
        END IF;
    END;

    -- Runs a statement that MUST fail with the given ORA- code.
    PROCEDURE expect_error(p_sql IN VARCHAR2, p_code IN PLS_INTEGER, p_msg IN VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
        check_that(FALSE, p_msg || ' (no error raised)');
    EXCEPTION
        WHEN OTHERS THEN
            check_that(SQLCODE = p_code, p_msg || ' [' || SQLCODE || ']');
    END;
BEGIN
    SAVEPOINT smoke_start;

    SELECT exam_id INTO v_math10 FROM v_exam_slots WHERE course_code = 'MATH-10';
    SELECT exam_id INTO v_chem11 FROM v_exam_slots WHERE course_code = 'CHEM-11';
    SELECT user_id INTO v_admin  FROM users WHERE username = 'admin';

    ---------------------------------------------------------------- seating
    pkg_seating.generate_seating(v_math10, v_seated, v_rooms);
    DBMS_OUTPUT.PUT_LINE('      MATH-10 seated ' || v_seated || ' in ' || v_rooms || ' room(s)');
    check_that(v_seated = 70, 'MATH-10: all 70 Grade 10 students seated');

    SELECT COUNT(*) INTO v_cnt
    FROM   seating_allocation sa JOIN rooms r ON r.room_id = sa.room_id
    WHERE  sa.exam_id = v_math10 AND r.room_status <> 'AVAILABLE';
    check_that(v_cnt = 0, 'No seats in the MAINTENANCE room');

    SELECT COUNT(*) INTO v_cnt
    FROM   seating_allocation sa JOIN students s ON s.student_id = sa.student_id
    WHERE  sa.exam_id = v_math10 AND s.has_special_needs = 'Y' AND sa.seat_number > '004';
    check_that(v_cnt = 0, 'Special-needs students got the front seats (001-004)');

    pkg_seating.generate_seating(v_chem11, v_seated, v_rooms);
    DBMS_OUTPUT.PUT_LINE('      CHEM-11 (same slot) seated ' || v_seated || ' in ' || v_rooms || ' room(s)');
    check_that(v_seated = 70, 'CHEM-11: all 70 Grade 11 students seated in the same slot');

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT sa.room_id, SUM(1) AS used, MAX(r.exam_capacity) AS cap
        FROM   seating_allocation sa JOIN rooms r ON r.room_id = sa.room_id
        WHERE  sa.exam_id IN (v_math10, v_chem11)
        GROUP  BY sa.room_id
        HAVING SUM(1) > MAX(r.exam_capacity));
    check_that(v_cnt = 0, 'Shared slot never exceeds any room''s exam capacity');

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT room_id, seat_number FROM seating_allocation
        WHERE  exam_id IN (v_math10, v_chem11)
        GROUP  BY room_id, seat_number HAVING COUNT(*) > 1);
    check_that(v_cnt = 0, 'No physical seat is double-booked in shared rooms');

    ---------------------------------------------------------------- allocation
    pkg_supervision.allocate_proctors(v_math10, v_assigned, v_unfilled);
    DBMS_OUTPUT.PUT_LINE('      MATH-10 staff assigned: ' || v_assigned);
    pkg_supervision.allocate_proctors(v_chem11, v_assigned, v_unfilled);
    DBMS_OUTPUT.PUT_LINE('      CHEM-11 staff assigned: ' || v_assigned);
    check_that(v_unfilled = 0, 'All positions filled');

    -- Subject rule is per room: a teacher never supervises a room in which a course
    -- of their own department is being examined (a Chemistry teacher may watch a
    -- Math-only room during the shared slot).
    SELECT COUNT(*) INTO v_cnt
    FROM   supervision_roster r
           JOIN teachers t ON t.teacher_id = r.teacher_id
    WHERE  r.exam_id IN (v_math10, v_chem11) AND r.assignment_status = 'CONFIRMED'
    AND    EXISTS (SELECT 1
                   FROM   seating_allocation sa
                          JOIN v_exam_slots s ON s.exam_id = sa.exam_id
                   WHERE  sa.room_id = r.room_id
                   AND    sa.exam_id IN (v_math10, v_chem11)
                   AND    s.course_dept_id = t.dept_id);
    check_that(v_cnt = 0, 'No teacher supervises a room where their own department''s course is examined');

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT teacher_id FROM supervision_roster
        WHERE  exam_id IN (v_math10, v_chem11) AND assignment_status = 'CONFIRMED'
        GROUP  BY teacher_id HAVING COUNT(*) > 1);
    check_that(v_cnt = 0, 'No teacher is in two rooms at the same time');

    SELECT COUNT(*) INTO v_cnt
    FROM   supervision_roster r
           JOIN teacher_student_relations tsr ON tsr.teacher_id = r.teacher_id
           JOIN seating_allocation sa ON sa.student_id = tsr.student_id
    WHERE  r.exam_id IN (v_math10, v_chem11) AND r.assignment_status = 'CONFIRMED'
    AND    sa.exam_id IN (v_math10, v_chem11);
    check_that(v_cnt = 0, 'No teacher supervises a session in which a relative sits (any room)');

    SELECT teacher_id INTO v_sub FROM teachers WHERE teacher_code = 'T-ENGL-01';   -- son in Grade 10
    SELECT MIN(sa.room_id) INTO v_other_room                                      -- a room WITHOUT the son
    FROM   seating_allocation sa
    WHERE  sa.exam_id = v_math10
    AND    sa.room_id NOT IN (SELECT x.room_id FROM seating_allocation x
                              JOIN students st ON st.student_id = x.student_id
                              WHERE x.exam_id = v_math10 AND st.student_code = 'STU-10-001');
    v_reason := pkg_supervision.check_eligibility(v_sub, v_math10, v_other_room);
    check_that(v_reason LIKE 'RELATIVE_IN_SESSION%', 'Parent barred from every room of the session: ' || v_reason);

    SELECT COUNT(*) INTO v_cnt FROM (
        SELECT room_id FROM supervision_roster
        WHERE  exam_id IN (v_math10, v_chem11) AND assignment_status = 'CONFIRMED'
        AND    role_type = 'HEAD_OF_COMMITTEE'
        GROUP  BY room_id HAVING COUNT(*) <> 1);
    check_that(v_cnt = 0, 'Exactly one head of committee per used room');

    ---------------------------------------------------------------- eligibility codes
    SELECT r.* INTO v_roster FROM supervision_roster r
    WHERE  r.exam_id = v_math10 AND r.assignment_status = 'CONFIRMED' AND ROWNUM = 1;

    SELECT teacher_id INTO v_sub FROM teachers WHERE teacher_code = 'T-MATH-01';
    v_reason := pkg_supervision.check_eligibility(v_sub, v_math10, v_roster.room_id);
    check_that(v_reason LIKE 'SUBJECT_CONFLICT%', 'Math teacher rejected for Math exam: ' || v_reason);

    v_reason := pkg_supervision.check_eligibility(v_roster.teacher_id, v_chem11, v_roster.room_id);
    check_that(v_reason IS NOT NULL, 'Busy teacher rejected for overlapping slot: ' || v_reason);

    ---------------------------------------------------------------- substitution (1-click)
    SELECT hours_balance INTO v_hours FROM teachers WHERE teacher_id = v_roster.teacher_id;
    v_sub := NULL;
    pkg_supervision.replace_proctor(v_roster.roster_id, v_admin, 'Sick leave (smoke test)', v_sub, v_new, v_audit);
    DBMS_OUTPUT.PUT_LINE('      Substitute teacher ' || v_sub || ', new roster ' || v_new || ', audit ' || v_audit);

    SELECT COUNT(*) INTO v_cnt FROM supervision_roster WHERE roster_id = v_roster.roster_id AND assignment_status = 'REPLACED';
    check_that(v_cnt = 1, 'Original roster row marked REPLACED');
    SELECT COUNT(*) INTO v_cnt FROM supervision_audit
    WHERE  audit_id = v_audit AND roster_id = v_roster.roster_id AND substitute_teacher_id = v_sub;
    check_that(v_cnt = 1, 'Substitution logged in SUPERVISION_AUDIT');
    SELECT COUNT(*) INTO v_cnt FROM teachers
    WHERE  teacher_id = v_roster.teacher_id AND hours_balance = GREATEST(0, v_hours - 2);
    check_that(v_cnt = 1, 'Hours refunded to the replaced teacher (2h slot)');

    expect_error('DECLARE s NUMBER; n NUMBER; a NUMBER; BEGIN pkg_supervision.replace_proctor(' ||
                 v_roster.roster_id || ', ' || v_admin || ', ''again'', s, n, a); END;',
                 -20022, 'Cannot replace an already REPLACED row');

    expect_error('UPDATE supervision_audit SET reason = ''tamper'' WHERE audit_id = ' || v_audit,
                 -20003, 'Audit log is immutable');

    ---------------------------------------------------------------- guards
    expect_error('BEGIN DECLARE a PLS_INTEGER; b PLS_INTEGER; BEGIN pkg_seating.generate_seating(' ||
                 v_math10 || ', a, b); END; END;', -20022, 'Re-seating refused while proctors are allocated');

    expect_error('DELETE FROM teachers WHERE teacher_code = ''T-ARAB-03''', -20001, 'Hard delete on TEACHERS blocked');

    expect_error('DELETE FROM v_teachers WHERE teacher_id = ' || v_sub, -20002,
                 'Soft delete refused for a teacher with upcoming duties');

    DELETE FROM v_rooms WHERE room_code = 'A104';            -- maintenance room, unused
    SELECT COUNT(*) INTO v_cnt FROM rooms WHERE room_code = 'A104' AND is_deleted = 'Y' AND deleted_at IS NOT NULL;
    check_that(v_cnt = 1, 'DELETE on V_ROOMS performs a soft delete');
    SELECT COUNT(*) INTO v_cnt FROM v_rooms WHERE room_code = 'A104';
    check_that(v_cnt = 0, 'Soft-deleted room hidden from V_ROOMS');

    ---------------------------------------------------------------- cancel
    pkg_supervision.cancel_allocation(v_chem11, v_cnt);
    check_that(v_cnt > 0, 'cancel_allocation cancelled ' || v_cnt || ' CHEM-11 assignment(s)');

    ROLLBACK TO smoke_start;
    DBMS_OUTPUT.PUT_LINE('----------------------------------------------');
    DBMS_OUTPUT.PUT_LINE(CASE WHEN v_fails = 0 THEN 'ALL CHECKS PASSED'
                              ELSE v_fails || ' CHECK(S) FAILED' END || ' (changes rolled back)');
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK TO smoke_start;
        DBMS_OUTPUT.PUT_LINE('ABORTED: ' || SQLERRM);
        DBMS_OUTPUT.PUT_LINE(DBMS_UTILITY.FORMAT_ERROR_BACKTRACE);
        RAISE;
END;
/
