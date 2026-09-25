--------------------------------------------------------------------------------
-- 07_student_exams.sql — read-only view for a student's own exam schedule + attendance.
-- Run once, as EXAM_ADMIN, after 06_student_accounts.sql:
--   sqlplus EXAM_ADMIN/<pwd>@//localhost:1521/XEPDB1 @07_student_exams.sql
--------------------------------------------------------------------------------
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
SET ECHO OFF FEEDBACK ON SERVEROUTPUT ON

PROMPT === v_student_exams ===
CREATE OR REPLACE VIEW v_student_exams AS
    SELECT sa.seating_id, sa.exam_id, s.exam_date, s.period_name, s.course_code, s.course_name,
           sa.room_id, rm.room_code, rm.building, sa.student_id, sa.seat_number, sa.attendance_status
    FROM   seating_allocation sa
           JOIN v_exam_slots s  ON s.exam_id  = sa.exam_id
           JOIN rooms        rm ON rm.room_id = sa.room_id;

PROMPT === invalid objects (expect none) ===
SELECT object_type, object_name FROM user_objects WHERE status <> 'VALID' ORDER BY 1, 2;

COMMIT;
EXIT;
