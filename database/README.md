# Database scripts

| File | Purpose |
|---|---|
| `00_drop_all.sql` | Drops all app objects (clean re-install) |
| `01_schema.sql` | 13 tables, constraints, indexes, views |
| `02_triggers.sql` | Soft delete, audit timestamps, integrity guards, error codes (`PKG_APP_CTX`) |
| `03_packages.sql` | `PKG_SEATING`, `PKG_SUPERVISION` business logic |
| `04_seed_data.sql` | Mock data + seed logins |
| `05_smoke_test.sql` | End-to-end test of every procedure; rolls back at the end |
| `06_student_accounts.sql` | Student `EMAIL`, `STUDENT` role, one login per student (`users.student_id`), forced password change on first login |
| `07_student_exams.sql` | `V_STUDENT_EXAMS` — a student's own exam schedule, seat and attendance |
| `install.sql` | Runs 00 → 04 and lists invalid objects |
| `install-docker.sh` / `.bat` | Copies the scripts into the `examhalls-oracle` container, runs `install.sql`, `06_student_accounts.sql`, `07_student_exams.sql` and the smoke test (password from `APP_USER_PASSWORD` or a prompt) |

Run `06_student_accounts.sql` and `07_student_exams.sql` once, in order, right after `install.sql` on any database that doesn't have a `students.email` column yet (a fresh install, or one seeded before the student-portal feature).

One-time DBA setup (as SYSTEM, in the PDB):

```sql
CREATE USER exam_admin IDENTIFIED BY "choose_a_password" QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE TRIGGER, CREATE SEQUENCE TO exam_admin;
```

Soft delete: the Java DAOs delete through `V_USERS`, `V_TEACHERS`, `V_ROOMS`.
A `DELETE` on the base tables is rejected with ORA-20001.
