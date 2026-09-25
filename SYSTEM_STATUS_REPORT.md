# System Status Report

**Exam Halls & Proctoring Allocation Management System** · v1.0.0 · 23 September 2026
**Status:** feature-complete and demo-ready · full documentation: `DOCUMENTATION.md` / `DOCUMENTATION.pdf`

## At a glance

| 13 tables | 5 views | 16 triggers | 4 PL/SQL packages | 15 views (9 spec screens) | 5 exports | 84 JUnit cases + 25 SQL checks | EN / AR (RTL) |
|---|---|---|---|---|---|---|---|

## Architecture

JavaFX 21 (FXML + CSS, English/Arabic) → Controllers → Services (RBAC, validation, transactions)
→ DAOs (`PreparedStatement` only) → HikariCP → **Oracle: PL/SQL rules engine, triggers, constraints**.
The database is the single source of truth for every business rule; Java orchestrates, translates
errors and presents.

## Delivered scope

| Area | Delivered |
|---|---|
| Database | 13 tables, named constraints, 3 approved extensions, soft-delete views, function-based unique indexes |
| Business engine | Seating generator, fair proctor allocation, eligibility checks, 1-click substitution, candidate list, cancellation |
| Safety interlocks | No hard deletes; no archiving of busy teachers or rooms in use; capacity cannot shrink under future seating; append-only audit |
| Screens | Login, Executive Dashboard (KPIs, 3 charts, focus-day panel, quick actions), Exam Schedule (+ editor), Occupancy Grid, Seating & Supervision, Substitution, Reports, Teachers, Rooms, Courses & Departments, Students, Users & Roles, My Duties |
| Exports | PDF: seating stickers, call sheets, individual schedules, daily control sheet. Excel: compensation hours with live formulas |
| Delivery | Cross-platform jar, `run-app.bat/.sh` with database pre-flight check, Windows portable edition with embedded Java 21 |

## Engineering highlights

- **Rules enforced in the database.** No client bug or hand-typed SQL can bypass them.
- **Correct under concurrency.** Savepoint-atomic procedures, "pick → lock → re-check" and unique
  indexes as the last guard.
- **Real time-overlap model.** Exams can share rooms without double-booking a seat or a teacher.
- **Conflict of interest.** Subject rule per room, relatives rule per session (up to 4th degree), and
  daily/weekly caps.
- **Accountability.** Immutable audit with server timestamps; history is marked, never overwritten.
- **Security.** BCrypt, no user enumeration, one validation policy on client and server (malformed
  input never reaches the database), BCrypt 72-byte guard, configurable lockout, 15-minute idle sign-out,
  server-side RBAC, no secrets in the jar, fail-fast pool that cannot lock the Oracle account.
- **Operations at a glance.** The dashboard computes coverage per room session (the engine's own unit)
  from six read-only queries, with red/amber staffing alerts and one-click jumps to the right screen.
- **Bilingual.** Every screen, error message and report in English and Arabic, right-to-left.

## Measured performance

Measured with Oracle Database Free 23.26 in Docker on Apple Silicon, seed-data scale (24 teachers,
10 rooms, 210 students), in rolled-back transactions.

| Operation | Warm (steady state) | First call after a reset |
|---|---|---|
| Generate seating (70 students) | 6–72 ms | 0.2–2.0 s |
| Allocate proctors | 5–107 ms | 0.07–1.3 s |
| Eligibility list (24 teachers) | 9–162 ms | ≈ 0.2 s |
| Supervision matrix / occupancy grid | 73 / 113 ms | ≈ 0.3 s |
| 1-click substitution | 16 ms | ≈ 60 ms |
| Sign-in (BCrypt cost 12, by design) | ≈ 0.9 s | ≈ 1–3 s |

The first calls after a reset are slower because Oracle re-parses the SQL and reloads the packages, so
do one warm-up pass before a demo.

## Caching strategy

**Today:**

- **Database side.** HikariCP connection reuse (10 max / 2 idle), Oracle statement cache (50 per
  connection), row prefetch of 50, and `HOURS_BALANCE` as a transactionally maintained aggregate.
- **Application side.** Only fonts, settings and language bundles are cached.
- **Master and operational data are read fresh** on purpose, because several lab PCs may write
  concurrently.

**Recommended ("cache lookups, not decisions"):**

| Tier | Data | Strategy |
|---|---|---|
| 1. Fixed | Roles, exam periods, departments | Load once per session; invalidate on local edit |
| 2. Slow-changing | Rooms & capacities, courses, grade levels, teacher pickers | Caffeine, 2–5 min TTL + refresh; invalidated **after commit** |
| 3. Operational | Seating, roster, eligibility, hours | Never cached; the database is the synchronisation point |

**Invalidation patterns:**

- Invalidate after commit only, and never populate a cache inside a transaction.
- Use the TTL as the safety net for other clients.
- Use Oracle Continuous Query Notification, or a version-stamp check, for push-style consistency.

## Scalability & future optimisation

| # | Finding | Fix |
|---|---|---|
| 1 | Overlap predicates on computed slot columns cannot use indexes | Add a same-day predicate (slots never cross midnight, guaranteed by a CHECK); make the weekly cap a date range |
| 2 | Eligibility runs about 6 queries per teacher per position and re-reads the slot each time | Load the slot once; evaluate all candidates in one set-based SQL |
| 3 | `get_candidates` calls a PL/SQL function for every row | Same set-based query |
| 4 | `gradeLevels()` loads all courses and students | `SELECT DISTINCT … UNION` |
| 5 | ~~Dashboard counts teachers by loading the whole list~~ | Fixed: counts come from one grouped query |
| 6 | Room lists re-read on every matrix/grid refresh | Tier-2 cache |
| 7 | Each client holds 2 idle connections (30 PCs = 60 sessions) | `minimumIdle=1`, `maximumPoolSize=4` for lab use |

None of these affect the demo (every action is under 0.2 s once warm). Priority at scale: #1 and #2
first, then the caches.

## Quality & known limitations

- **Tests.** 84 JUnit test cases (64 unit, 20 integration, all rolled back) and 25 SQL smoke checks. Every
  screen was driven end to end in both languages on Java 25 and Java 21.
- **Future work.**
  - A screen for digital attendance (the API already exists).
  - An editor for exam periods.
  - An audit-log viewer (the dashboard already shows the latest substitutions).
  - A database-backed lockout shared by all lab PCs, and per-user database identities (see §2.7.1 of
    the documentation).
  - Automatic timetabling.
