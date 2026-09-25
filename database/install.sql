--------------------------------------------------------------------------------
-- install.sql — full (re)install. Run from this folder:
--   sql  EXAM_ADMIN/<pwd>@//localhost:1521/FREEPDB1 @install.sql     (SQLcl)
--   sqlplus EXAM_ADMIN/<pwd>@//localhost:1521/FREEPDB1 @install.sql
--------------------------------------------------------------------------------
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
SET ECHO OFF FEEDBACK ON SERVEROUTPUT ON

PROMPT === 00 drop ===
@@00_drop_all.sql
PROMPT === 01 schema ===
@@01_schema.sql
PROMPT === 02 triggers ===
@@02_triggers.sql
PROMPT === 03 packages ===
@@03_packages.sql
PROMPT === 04 seed data ===
@@04_seed_data.sql

PROMPT === invalid objects (expect none) ===
SELECT object_type, object_name FROM user_objects WHERE status <> 'VALID' ORDER BY 1, 2;
