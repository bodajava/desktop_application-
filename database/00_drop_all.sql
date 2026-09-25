--------------------------------------------------------------------------------
-- 00_drop_all.sql — removes every object created by 01-04 (for clean re-installs)
-- DANGER: destroys all application data in the current schema.
--------------------------------------------------------------------------------
SET DEFINE OFF
BEGIN
    FOR o IN (
        SELECT object_name, object_type FROM user_objects
        WHERE  object_name IN (
                 'PKG_SUPERVISION', 'PKG_SEATING', 'PKG_EXAM_UTIL', 'PKG_APP_CTX',
                 'V_SUPERVISION_ROSTER', 'V_EXAM_SLOTS', 'V_ROOMS', 'V_TEACHERS', 'V_USERS')
        AND    object_type IN ('PACKAGE', 'VIEW')
    ) LOOP
        EXECUTE IMMEDIATE 'DROP ' || o.object_type || ' ' || o.object_name;
    END LOOP;

    FOR t IN (
        SELECT table_name FROM user_tables
        WHERE  table_name IN (
                 'SUPERVISION_AUDIT', 'SUPERVISION_ROSTER', 'SEATING_ALLOCATION',
                 'TEACHER_STUDENT_RELATIONS', 'EXAM_SCHEDULE', 'EXAM_PERIODS', 'STUDENTS',
                 'ROOMS', 'COURSES', 'TEACHERS', 'DEPARTMENTS', 'USERS', 'ROLES')
    ) LOOP
        EXECUTE IMMEDIATE 'DROP TABLE ' || t.table_name || ' CASCADE CONSTRAINTS PURGE';
    END LOOP;
END;
/
