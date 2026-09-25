--------------------------------------------------------------------------------
-- 06_student_accounts.sql — adds student login accounts on top of 01-05.
-- Run once, as EXAM_ADMIN, after install.sql:
--   sqlplus EXAM_ADMIN/<pwd>@//localhost:1521/XEPDB1 @06_student_accounts.sql
--------------------------------------------------------------------------------
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
SET ECHO OFF FEEDBACK ON SERVEROUTPUT ON

PROMPT === students: add EMAIL ===
ALTER TABLE students ADD email VARCHAR2(150);

PROMPT === roles: add STUDENT ===
INSERT INTO roles (role_name, role_description)
VALUES ('STUDENT', 'Student: views own exam schedule and attendance');

PROMPT === users: link one login per student, first-login password change flag ===
ALTER TABLE users ADD student_id NUMBER;
ALTER TABLE users ADD CONSTRAINT fk_users_student FOREIGN KEY (student_id) REFERENCES students (student_id);
ALTER TABLE users ADD CONSTRAINT uk_users_student UNIQUE (student_id);

ALTER TABLE users ADD must_change_password CHAR(1) DEFAULT 'N' NOT NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_must_change CHECK (must_change_password IN ('Y', 'N'));

PROMPT === invalid objects (expect none) ===
SELECT object_type, object_name FROM user_objects WHERE status <> 'VALID' ORDER BY 1, 2;

COMMIT;
EXIT;
