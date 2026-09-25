# Database scripts

| File | Purpose |
|---|---|
| `00_drop_all.sql` | Drops all app objects (clean re-install) |
| `01_schema.sql` | 13 tables, constraints, indexes, views |
| `02_triggers.sql` | Soft delete, audit timestamps, integrity guards, error codes (`PKG_APP_CTX`) |
| `03_packages.sql` | `PKG_SEATING`, `PKG_SUPERVISION` business logic |
| `04_seed_data.sql` | Mock data + seed logins |
| `05_smoke_test.sql` | End-to-end test of every procedure; rolls back at the end |
| `install.sql` | Runs 00 → 04 and lists invalid objects |
| `install-docker.sh` / `.bat` | Copies the scripts into the `examhalls-oracle` container, runs `install.sql` and the smoke test (password from `APP_USER_PASSWORD` or a prompt) |

One-time DBA setup (as SYSTEM, in the PDB):

```sql
CREATE USER exam_admin IDENTIFIED BY "choose_a_password" QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE TRIGGER, CREATE SEQUENCE TO exam_admin;
```

Soft delete: the Java DAOs delete through `V_USERS`, `V_TEACHERS`, `V_ROOMS`.
A `DELETE` on the base tables is rejected with ORA-20001.
