--------------------------------------------------------------------------------
-- Exam Halls & Proctoring Allocation Management System
-- 03_packages.sql  —  Business logic
--   PKG_EXAM_UTIL    shared slot/time helpers
--   PKG_SEATING      automated student seating per room capacity
--   PKG_SUPERVISION  smart proctor allocation + 1-click emergency substitution
--------------------------------------------------------------------------------
-- Transaction contract
--   Procedures never COMMIT. Each one sets a SAVEPOINT and rolls back to it on any
--   error, so a failed call leaves no partial work. The caller commits (with
--   HikariCP autoCommit=true every CallableStatement is committed automatically).
--
-- Time model
--   Two exams "overlap" when their real slots intersect:
--       a.slot_start < b.slot_end AND b.slot_start < a.slot_end
--   so capacity, student clashes and teacher availability stay correct even if
--   periods are redefined or rooms are shared by several exams in one slot.
--------------------------------------------------------------------------------

SET DEFINE OFF

-- ============================================================================
-- PKG_EXAM_UTIL
-- ============================================================================
CREATE OR REPLACE PACKAGE pkg_exam_util AS
    TYPE t_slot IS RECORD (
        exam_id         exam_schedule.exam_id%TYPE,
        exam_date       exam_schedule.exam_date%TYPE,
        period_id       exam_schedule.period_id%TYPE,
        slot_start      DATE,
        slot_end        DATE,
        duration_hours  NUMBER,
        course_dept_id  courses.dept_id%TYPE,
        grade_level     courses.grade_level%TYPE
    );

    -- Raises pkg_app_ctx.e_not_found if the exam does not exist.
    FUNCTION get_slot(p_exam_id IN NUMBER) RETURN t_slot;
END pkg_exam_util;
/

CREATE OR REPLACE PACKAGE BODY pkg_exam_util AS
    FUNCTION get_slot(p_exam_id IN NUMBER) RETURN t_slot IS
        v_slot t_slot;
    BEGIN
        SELECT exam_id, exam_date, period_id, slot_start, slot_end,
               duration_hours, course_dept_id, grade_level
        INTO   v_slot
        FROM   v_exam_slots
        WHERE  exam_id = p_exam_id;
        RETURN v_slot;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Exam ' || p_exam_id || ' does not exist.');
    END get_slot;
END pkg_exam_util;
/

-- ============================================================================
-- PKG_SEATING
-- ============================================================================
CREATE OR REPLACE PACKAGE pkg_seating AS
    /*
     * Seats every student whose GRADE_LEVEL matches the exam's course.
     *   - Rooms: active + AVAILABLE, largest free capacity first (fewer rooms => fewer proctors).
     *   - Free capacity = EXAM_CAPACITY minus seats taken by other exams overlapping this slot,
     *     so one room can be shared by several exams at the same time.
     *   - Special-needs students are seated first (lowest seat numbers = front rows).
     *   - Seat numbers are 3-digit strings, continuing after seats used by other exams in the room.
     *   - Existing seating for the exam is replaced. Refused while the exam has active proctors
     *     (call pkg_supervision.cancel_allocation first).
     *   - All-or-nothing: raises e_insufficient_capacity if not everyone fits.
     */
    PROCEDURE generate_seating(
        p_exam_id     IN  NUMBER,
        p_seated      OUT PLS_INTEGER,
        p_rooms_used  OUT PLS_INTEGER
    );

    -- Removes all seats of an exam (refused while proctors are allocated).
    PROCEDURE clear_seating(p_exam_id IN NUMBER);

    -- PRESENT / ABSENT / EXCUSED / ABSENT_PENDING
    PROCEDURE mark_attendance(p_seating_id IN NUMBER, p_status IN VARCHAR2);
END pkg_seating;
/

CREATE OR REPLACE PACKAGE BODY pkg_seating AS

    TYPE t_num_tab IS TABLE OF NUMBER;
    TYPE t_seat_tab IS TABLE OF VARCHAR2(10);

    PROCEDURE assert_no_active_roster(p_exam_id IN NUMBER) IS
        v_cnt PLS_INTEGER;
    BEGIN
        SELECT COUNT(*) INTO v_cnt
        FROM   supervision_roster
        WHERE  exam_id = p_exam_id AND assignment_status = 'CONFIRMED';

        IF v_cnt > 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_state,
                'Exam ' || p_exam_id || ' has ' || v_cnt || ' active proctor assignments. ' ||
                'Run pkg_supervision.cancel_allocation first.');
        END IF;
    END assert_no_active_roster;

    PROCEDURE lock_exam(p_exam_id IN NUMBER, p_period_id OUT NUMBER) IS
        v_dummy NUMBER;
    BEGIN
        SELECT period_id INTO p_period_id
        FROM   exam_schedule WHERE exam_id = p_exam_id
        FOR UPDATE WAIT 10;

        -- Serialise seating generation for every exam that uses this period, so two
        -- users generating in parallel cannot both grab the same free seats.
        SELECT period_id INTO v_dummy
        FROM   exam_periods WHERE period_id = p_period_id
        FOR UPDATE WAIT 10;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Exam ' || p_exam_id || ' does not exist.');
    END lock_exam;

    PROCEDURE generate_seating(
        p_exam_id     IN  NUMBER,
        p_seated      OUT PLS_INTEGER,
        p_rooms_used  OUT PLS_INTEGER
    ) IS
        v_slot       pkg_exam_util.t_slot;
        v_period_id  NUMBER;
        v_students   t_num_tab;
        v_room_ids   t_num_tab  := t_num_tab();
        v_stu_ids    t_num_tab  := t_num_tab();
        v_seats      t_seat_tab := t_seat_tab();
        v_next       PLS_INTEGER := 1;       -- next student index to seat
        v_take       PLS_INTEGER;
        v_clashes    PLS_INTEGER;
    BEGIN
        SAVEPOINT sp_generate_seating;
        p_seated     := 0;
        p_rooms_used := 0;

        lock_exam(p_exam_id, v_period_id);
        assert_no_active_roster(p_exam_id);
        v_slot := pkg_exam_util.get_slot(p_exam_id);

        DELETE FROM seating_allocation WHERE exam_id = p_exam_id;

        SELECT student_id
        BULK COLLECT INTO v_students
        FROM   students
        WHERE  grade_level = v_slot.grade_level
        ORDER  BY has_special_needs DESC, student_code;

        IF v_students.COUNT = 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_no_students,
                'No students found for grade level ''' || v_slot.grade_level || '''.');
        END IF;

        -- A student cannot sit two exams whose slots overlap.
        SELECT COUNT(DISTINCT sa.student_id)
        INTO   v_clashes
        FROM   seating_allocation sa
               JOIN v_exam_slots s ON s.exam_id    = sa.exam_id
               JOIN students    st ON st.student_id = sa.student_id
        WHERE  st.grade_level = v_slot.grade_level
        AND    sa.exam_id    <> p_exam_id
        AND    s.slot_start   < v_slot.slot_end
        AND    v_slot.slot_start < s.slot_end;

        IF v_clashes > 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_student_clash,
                v_clashes || ' student(s) of ' || v_slot.grade_level ||
                ' are already seated in another exam at the same time.');
        END IF;

        -- Session-wide relatives rule: refuse if a teacher already supervising this
        -- slot is related to a student about to be seated (seat all exams of a slot
        -- before allocating proctors).
        SELECT COUNT(*) INTO v_clashes
        FROM   teacher_student_relations tsr
               JOIN students st ON st.student_id = tsr.student_id
        WHERE  st.grade_level = v_slot.grade_level
        AND    EXISTS (SELECT 1
                       FROM   supervision_roster r
                              JOIN v_exam_slots s ON s.exam_id = r.exam_id
                       WHERE  r.teacher_id        = tsr.teacher_id
                       AND    r.assignment_status = 'CONFIRMED'
                       AND    s.slot_start < v_slot.slot_end
                       AND    v_slot.slot_start < s.slot_end);
        IF v_clashes > 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_state,
                v_clashes || ' teacher(s) already supervising this slot are related to ' ||
                v_slot.grade_level || ' students. Seat all exams of the slot before allocating proctors, ' ||
                'or substitute those teachers first.');
        END IF;

        FOR rm IN (
            SELECT r.room_id,
                   r.exam_capacity - NVL(u.used, 0) AS free_seats,
                   NVL(u.last_seat, 0)              AS last_seat
            FROM   rooms r
                   LEFT JOIN (
                       SELECT sa.room_id,
                              COUNT(*) AS used,
                              MAX(CASE WHEN REGEXP_LIKE(sa.seat_number, '^[0-9]+$')
                                       THEN TO_NUMBER(sa.seat_number) END) AS last_seat
                       FROM   seating_allocation sa
                              JOIN v_exam_slots s ON s.exam_id = sa.exam_id
                       WHERE  sa.exam_id <> p_exam_id
                       AND    s.slot_start < v_slot.slot_end
                       AND    v_slot.slot_start < s.slot_end
                       GROUP  BY sa.room_id
                   ) u ON u.room_id = r.room_id
            WHERE  r.is_deleted  = 'N'
            AND    r.room_status = 'AVAILABLE'
            AND    r.exam_capacity - NVL(u.used, 0) > 0
            ORDER  BY free_seats DESC, r.room_code
        ) LOOP
            EXIT WHEN v_next > v_students.COUNT;

            v_take := LEAST(rm.free_seats, v_students.COUNT - v_next + 1);
            FOR i IN 1 .. v_take LOOP
                v_room_ids.EXTEND; v_room_ids(v_room_ids.LAST) := rm.room_id;
                v_stu_ids.EXTEND;  v_stu_ids(v_stu_ids.LAST)   := v_students(v_next);
                v_seats.EXTEND;    v_seats(v_seats.LAST)       := LPAD(rm.last_seat + i, 3, '0');
                v_next := v_next + 1;
            END LOOP;
            p_rooms_used := p_rooms_used + 1;
        END LOOP;

        IF v_next <= v_students.COUNT THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_insufficient_capacity,
                'Not enough exam capacity: ' || v_students.COUNT || ' students, only ' ||
                (v_next - 1) || ' free seats in available rooms for this slot.');
        END IF;

        FORALL i IN 1 .. v_room_ids.COUNT
            INSERT INTO seating_allocation (exam_id, room_id, student_id, seat_number)
            VALUES (p_exam_id, v_room_ids(i), v_stu_ids(i), v_seats(i));

        p_seated := v_room_ids.COUNT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK TO sp_generate_seating;
            RAISE;
    END generate_seating;

    PROCEDURE clear_seating(p_exam_id IN NUMBER) IS
        v_period_id NUMBER;
    BEGIN
        SAVEPOINT sp_clear_seating;
        lock_exam(p_exam_id, v_period_id);
        assert_no_active_roster(p_exam_id);
        DELETE FROM seating_allocation WHERE exam_id = p_exam_id;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK TO sp_clear_seating;
            RAISE;
    END clear_seating;

    PROCEDURE mark_attendance(p_seating_id IN NUMBER, p_status IN VARCHAR2) IS
    BEGIN
        UPDATE seating_allocation
        SET    attendance_status = UPPER(TRIM(p_status))
        WHERE  seating_id = p_seating_id;

        IF SQL%ROWCOUNT = 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Seat ' || p_seating_id || ' does not exist.');
        END IF;
    END mark_attendance;

END pkg_seating;
/

-- ============================================================================
-- PKG_SUPERVISION
-- ============================================================================
CREATE OR REPLACE PACKAGE pkg_supervision AS
    /*
     * Returns NULL when the teacher may supervise p_room_id during p_exam_id,
     * otherwise '<CODE>: <explanation>'. Codes, in the order they are checked:
     *   NOT_FOUND / TEACHER_INACTIVE
     *   SUBJECT_CONFLICT  teacher's dept = dept of the exam's course, or of any other
     *                     course seated in the same room during the same slot
     *   TIME_CONFLICT     already supervising an overlapping slot
     *   DAILY_LIMIT       confirmed sessions that day  >= MAX_DAILY_LOAD
     *   WEEKLY_LIMIT      confirmed sessions that ISO week >= MAX_WEEKLY_LOAD
     *   RELATIVE_IN_SESSION a relative (TEACHER_STUDENT_RELATIONS, degree 1-4) sits any
     *                     exam, in any room, during an overlapping slot (whole-session ban)
     */
    FUNCTION check_eligibility(
        p_teacher_id IN NUMBER,
        p_exam_id    IN NUMBER,
        p_room_id    IN NUMBER
    ) RETURN VARCHAR2;

    /*
     * Staffs every room used by the exam's seating with 1 HEAD_OF_COMMITTEE plus
     * CEIL(students in room / p_students_per_proctor) PROCTORs (minimum 1).
     * Staff already present in the room for an overlapping exam are counted, so a
     * shared room is not double-staffed.
     * Fairness: lowest HOURS_BALANCE first, then fewest active duties.
     * Each assignment adds the slot duration to the teacher's HOURS_BALANCE.
     * p_allow_partial = 'N' (default): all-or-nothing, raises e_no_eligible_teacher
     *                   if any position cannot be filled.
     * p_allow_partial = 'Y': keeps what could be filled, reports the rest in p_unfilled.
     */
    PROCEDURE allocate_proctors(
        p_exam_id               IN  NUMBER,
        p_assigned              OUT PLS_INTEGER,
        p_unfilled              OUT PLS_INTEGER,
        p_students_per_proctor  IN  PLS_INTEGER DEFAULT 20,
        p_allow_partial         IN  CHAR        DEFAULT 'N'
    );

    -- Cancels all active assignments of an exam and refunds the teachers' hours.
    PROCEDURE cancel_allocation(p_exam_id IN NUMBER, p_cancelled OUT PLS_INTEGER);

    -- Best eligible substitute for a roster row (NULL if none). Read-only preview for the UI.
    FUNCTION find_best_substitute(p_roster_id IN NUMBER) RETURN NUMBER;

    /*
     * Emergency 1-click substitution.
     *   p_substitute_id IN  NULL  -> the best eligible teacher is chosen automatically
     *                   IN  value -> that teacher is validated with check_eligibility
     *                   OUT       -> the teacher actually assigned
     * The original roster row becomes 'REPLACED', a new CONFIRMED row is created for the
     * substitute, hours are transferred, and SUPERVISION_AUDIT records who/why/when
     * (AUDIT.ROSTER_ID = the original row, so the replaced teacher stays traceable).
     */
    PROCEDURE replace_proctor(
        p_roster_id      IN     NUMBER,
        p_executed_by    IN     NUMBER,
        p_reason         IN     VARCHAR2,
        p_substitute_id  IN OUT NUMBER,
        p_new_roster_id  OUT    NUMBER,
        p_audit_id       OUT    NUMBER
    );

    -- Every active teacher with an ELIGIBILITY column (NULL = eligible), eligible first.
    -- Columns: teacher_id, teacher_code, full_name, dept_name, hours_balance, eligibility
    FUNCTION get_candidates(p_exam_id IN NUMBER, p_room_id IN NUMBER) RETURN SYS_REFCURSOR;
END pkg_supervision;
/

CREATE OR REPLACE PACKAGE BODY pkg_supervision AS

    -- ------------------------------------------------------------------ eligibility
    FUNCTION check_eligibility(
        p_teacher_id IN NUMBER,
        p_exam_id    IN NUMBER,
        p_room_id    IN NUMBER
    ) RETURN VARCHAR2 IS
        v_teacher teachers%ROWTYPE;
        v_slot    pkg_exam_util.t_slot;
        v_cnt     PLS_INTEGER;
    BEGIN
        BEGIN
            SELECT * INTO v_teacher FROM teachers WHERE teacher_id = p_teacher_id;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RETURN 'NOT_FOUND: teacher ' || p_teacher_id || ' does not exist';
        END;

        IF v_teacher.is_deleted = 'Y' THEN
            RETURN 'TEACHER_INACTIVE: teacher is deleted';
        END IF;

        v_slot := pkg_exam_util.get_slot(p_exam_id);

        -- 1. Subject conflict (this exam, or any other exam seated in the same room/slot)
        SELECT COUNT(*) INTO v_cnt
        FROM   v_exam_slots s
        WHERE  s.course_dept_id = v_teacher.dept_id
        AND   (s.exam_id = p_exam_id
               OR (s.slot_start < v_slot.slot_end
                   AND v_slot.slot_start < s.slot_end
                   AND EXISTS (SELECT 1 FROM seating_allocation sa
                               WHERE  sa.exam_id = s.exam_id AND sa.room_id = p_room_id)));
        IF v_cnt > 0 THEN
            RETURN 'SUBJECT_CONFLICT: teacher belongs to the department of a course examined in this room';
        END IF;

        -- 2. Already busy during an overlapping slot
        SELECT COUNT(*) INTO v_cnt
        FROM   supervision_roster r
               JOIN v_exam_slots s ON s.exam_id = r.exam_id
        WHERE  r.teacher_id        = p_teacher_id
        AND    r.assignment_status = 'CONFIRMED'
        AND    s.slot_start < v_slot.slot_end
        AND    v_slot.slot_start < s.slot_end;
        IF v_cnt > 0 THEN
            RETURN 'TIME_CONFLICT: already supervising during this slot';
        END IF;

        -- 3. Daily cap
        SELECT COUNT(*) INTO v_cnt
        FROM   supervision_roster r
               JOIN exam_schedule e ON e.exam_id = r.exam_id
        WHERE  r.teacher_id        = p_teacher_id
        AND    r.assignment_status = 'CONFIRMED'
        AND    e.exam_date         = v_slot.exam_date;
        IF v_cnt >= v_teacher.max_daily_load THEN
            RETURN 'DAILY_LIMIT: ' || v_cnt || '/' || v_teacher.max_daily_load ||
                   ' sessions on ' || TO_CHAR(v_slot.exam_date, 'YYYY-MM-DD');
        END IF;

        -- 4. Weekly cap (ISO week, Monday-based)
        SELECT COUNT(*) INTO v_cnt
        FROM   supervision_roster r
               JOIN exam_schedule e ON e.exam_id = r.exam_id
        WHERE  r.teacher_id        = p_teacher_id
        AND    r.assignment_status = 'CONFIRMED'
        AND    TRUNC(e.exam_date, 'IW') = TRUNC(v_slot.exam_date, 'IW');
        IF v_cnt >= v_teacher.max_weekly_load THEN
            RETURN 'WEEKLY_LIMIT: ' || v_cnt || '/' || v_teacher.max_weekly_load ||
                   ' sessions in week of ' || TO_CHAR(TRUNC(v_slot.exam_date, 'IW'), 'YYYY-MM-DD');
        END IF;

        -- 5. Relative (up to 4th degree) sitting ANY exam, in ANY room, during an
        --    overlapping slot => the teacher is barred from the whole session.
        --    The candidate's own exam is checked too, even before it has seating.
        SELECT COUNT(*) INTO v_cnt
        FROM   teacher_student_relations tsr
        WHERE  tsr.teacher_id = p_teacher_id
        AND   (EXISTS (SELECT 1
                       FROM   seating_allocation sa
                              JOIN v_exam_slots s ON s.exam_id = sa.exam_id
                       WHERE  sa.student_id = tsr.student_id
                       AND    s.slot_start < v_slot.slot_end
                       AND    v_slot.slot_start < s.slot_end)
            OR EXISTS (SELECT 1
                       FROM   students st
                       WHERE  st.student_id  = tsr.student_id
                       AND    st.grade_level = v_slot.grade_level));
        IF v_cnt > 0 THEN
            RETURN 'RELATIVE_IN_SESSION: ' || v_cnt || ' related student(s) sit an exam during this slot';
        END IF;

        RETURN NULL;
    END check_eligibility;

    -- ------------------------------------------------------------------ helpers
    -- Picks the fairest eligible teacher, locks their row and re-checks (another
    -- session may have assigned them meanwhile). Returns NULL if nobody qualifies.
    FUNCTION pick_teacher(
        p_exam_id         IN NUMBER,
        p_room_id         IN NUMBER,
        p_exclude_teacher IN NUMBER DEFAULT NULL
    ) RETURN NUMBER IS
        v_locked NUMBER;
    BEGIN
        FOR c IN (
            SELECT t.teacher_id
            FROM   teachers t
            WHERE  t.is_deleted = 'N'
            AND    t.teacher_id <> NVL(p_exclude_teacher, -1)
            ORDER  BY t.hours_balance,
                      (SELECT COUNT(*) FROM supervision_roster r
                       WHERE  r.teacher_id = t.teacher_id AND r.assignment_status = 'CONFIRMED'),
                      t.teacher_id
        ) LOOP
            IF check_eligibility(c.teacher_id, p_exam_id, p_room_id) IS NULL THEN
                SELECT teacher_id INTO v_locked
                FROM   teachers WHERE teacher_id = c.teacher_id
                FOR UPDATE WAIT 10;

                IF check_eligibility(c.teacher_id, p_exam_id, p_room_id) IS NULL THEN
                    RETURN c.teacher_id;
                END IF;
            END IF;
        END LOOP;
        RETURN NULL;
    END pick_teacher;

    PROCEDURE add_hours(p_teacher_id IN NUMBER, p_hours IN NUMBER) IS
    BEGIN
        UPDATE teachers
        SET    hours_balance = GREATEST(0, hours_balance + p_hours)
        WHERE  teacher_id = p_teacher_id;
    END add_hours;

    -- ------------------------------------------------------------------ allocation
    PROCEDURE allocate_proctors(
        p_exam_id               IN  NUMBER,
        p_assigned              OUT PLS_INTEGER,
        p_unfilled              OUT PLS_INTEGER,
        p_students_per_proctor  IN  PLS_INTEGER DEFAULT 20,
        p_allow_partial         IN  CHAR        DEFAULT 'N'
    ) IS
        v_slot          pkg_exam_util.t_slot;
        v_dummy         NUMBER;
        v_rooms         PLS_INTEGER := 0;
        v_in_room       PLS_INTEGER;
        v_have_head     PLS_INTEGER;
        v_have_proctors PLS_INTEGER;
        v_need_proctors PLS_INTEGER;
        v_teacher_id    NUMBER;
        v_report        VARCHAR2(2000);

        PROCEDURE assign(p_room_id IN NUMBER, p_room_code IN VARCHAR2, p_role IN VARCHAR2) IS
        BEGIN
            v_teacher_id := pick_teacher(p_exam_id, p_room_id);
            IF v_teacher_id IS NULL THEN
                p_unfilled := p_unfilled + 1;
                IF LENGTH(v_report) < 1800 OR v_report IS NULL THEN
                    v_report := v_report || ' ' || p_room_code || '/' || p_role || ';';
                END IF;
            ELSE
                INSERT INTO supervision_roster (exam_id, room_id, teacher_id, role_type)
                VALUES (p_exam_id, p_room_id, v_teacher_id, p_role);
                add_hours(v_teacher_id, v_slot.duration_hours);
                p_assigned := p_assigned + 1;
            END IF;
        END assign;
    BEGIN
        SAVEPOINT sp_allocate_proctors;
        p_assigned := 0;
        p_unfilled := 0;

        IF p_students_per_proctor IS NULL OR p_students_per_proctor < 1 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_argument,
                'p_students_per_proctor must be >= 1.');
        END IF;

        BEGIN
            SELECT exam_id INTO v_dummy FROM exam_schedule
            WHERE  exam_id = p_exam_id FOR UPDATE WAIT 10;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Exam ' || p_exam_id || ' does not exist.');
        END;
        v_slot := pkg_exam_util.get_slot(p_exam_id);

        FOR rm IN (
            SELECT DISTINCT sa.room_id, r.room_code
            FROM   seating_allocation sa
                   JOIN rooms r ON r.room_id = sa.room_id
            WHERE  sa.exam_id = p_exam_id
            ORDER  BY r.room_code
        ) LOOP
            v_rooms := v_rooms + 1;

            -- Everyone sitting in this room during the slot (all exams sharing it)
            SELECT COUNT(*) INTO v_in_room
            FROM   seating_allocation sa
                   JOIN v_exam_slots s ON s.exam_id = sa.exam_id
            WHERE  sa.room_id = rm.room_id
            AND    s.slot_start < v_slot.slot_end
            AND    v_slot.slot_start < s.slot_end;

            -- Staff already in this room during the slot
            SELECT NVL(SUM(CASE WHEN r.role_type = 'HEAD_OF_COMMITTEE' THEN 1 ELSE 0 END), 0),
                   NVL(SUM(CASE WHEN r.role_type = 'PROCTOR'           THEN 1 ELSE 0 END), 0)
            INTO   v_have_head, v_have_proctors
            FROM   supervision_roster r
                   JOIN v_exam_slots s ON s.exam_id = r.exam_id
            WHERE  r.room_id           = rm.room_id
            AND    r.assignment_status = 'CONFIRMED'
            AND    s.slot_start < v_slot.slot_end
            AND    v_slot.slot_start < s.slot_end;

            IF v_have_head = 0 THEN
                assign(rm.room_id, rm.room_code, 'HEAD_OF_COMMITTEE');
            END IF;

            v_need_proctors := GREATEST(1, CEIL(v_in_room / p_students_per_proctor)) - v_have_proctors;
            FOR i IN 1 .. v_need_proctors LOOP
                assign(rm.room_id, rm.room_code, 'PROCTOR');
            END LOOP;
        END LOOP;

        IF v_rooms = 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_state,
                'Exam ' || p_exam_id || ' has no seating. Run pkg_seating.generate_seating first.');
        END IF;

        IF p_unfilled > 0 AND NVL(p_allow_partial, 'N') <> 'Y' THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_no_eligible_teacher,
                p_unfilled || ' position(s) could not be filled by an eligible teacher:' ||
                SUBSTR(v_report, 1, 1800));
        END IF;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK TO sp_allocate_proctors;
            RAISE;
    END allocate_proctors;

    PROCEDURE cancel_allocation(p_exam_id IN NUMBER, p_cancelled OUT PLS_INTEGER) IS
        v_slot pkg_exam_util.t_slot;
    BEGIN
        SAVEPOINT sp_cancel_allocation;
        p_cancelled := 0;
        v_slot := pkg_exam_util.get_slot(p_exam_id);

        FOR r IN (
            SELECT roster_id, teacher_id
            FROM   supervision_roster
            WHERE  exam_id = p_exam_id AND assignment_status = 'CONFIRMED'
            FOR UPDATE WAIT 10
        ) LOOP
            UPDATE supervision_roster SET assignment_status = 'CANCELLED' WHERE roster_id = r.roster_id;
            add_hours(r.teacher_id, -v_slot.duration_hours);
            p_cancelled := p_cancelled + 1;
        END LOOP;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK TO sp_cancel_allocation;
            RAISE;
    END cancel_allocation;

    -- ------------------------------------------------------------------ substitution
    FUNCTION find_best_substitute(p_roster_id IN NUMBER) RETURN NUMBER IS
        v_exam_id    NUMBER;
        v_room_id    NUMBER;
        v_teacher_id NUMBER;
    BEGIN
        SELECT exam_id, room_id, teacher_id
        INTO   v_exam_id, v_room_id, v_teacher_id
        FROM   supervision_roster
        WHERE  roster_id = p_roster_id;

        -- Read-only preview: same ordering as pick_teacher, without locking.
        FOR c IN (
            SELECT t.teacher_id
            FROM   teachers t
            WHERE  t.is_deleted = 'N' AND t.teacher_id <> v_teacher_id
            ORDER  BY t.hours_balance,
                      (SELECT COUNT(*) FROM supervision_roster r
                       WHERE  r.teacher_id = t.teacher_id AND r.assignment_status = 'CONFIRMED'),
                      t.teacher_id
        ) LOOP
            IF check_eligibility(c.teacher_id, v_exam_id, v_room_id) IS NULL THEN
                RETURN c.teacher_id;
            END IF;
        END LOOP;
        RETURN NULL;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Roster entry ' || p_roster_id || ' does not exist.');
    END find_best_substitute;

    PROCEDURE replace_proctor(
        p_roster_id      IN     NUMBER,
        p_executed_by    IN     NUMBER,
        p_reason         IN     VARCHAR2,
        p_substitute_id  IN OUT NUMBER,
        p_new_roster_id  OUT    NUMBER,
        p_audit_id       OUT    NUMBER
    ) IS
        v_roster  supervision_roster%ROWTYPE;
        v_slot    pkg_exam_util.t_slot;
        v_user_ok PLS_INTEGER;
        v_locked  NUMBER;
        v_reason  VARCHAR2(4000);
    BEGIN
        SAVEPOINT sp_replace_proctor;

        IF TRIM(p_reason) IS NULL THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_argument, 'A substitution reason is required.');
        END IF;

        SELECT COUNT(*) INTO v_user_ok
        FROM   users WHERE user_id = p_executed_by AND is_deleted = 'N';
        IF v_user_ok = 0 THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found,
                'Executing user ' || p_executed_by || ' does not exist or is deleted.');
        END IF;

        BEGIN
            SELECT * INTO v_roster
            FROM   supervision_roster
            WHERE  roster_id = p_roster_id
            FOR UPDATE WAIT 10;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found, 'Roster entry ' || p_roster_id || ' does not exist.');
        END;

        IF v_roster.assignment_status <> 'CONFIRMED' THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_state,
                'Roster entry ' || p_roster_id || ' is ' || v_roster.assignment_status || ', not CONFIRMED.');
        END IF;

        IF p_substitute_id = v_roster.teacher_id THEN
            RAISE_APPLICATION_ERROR(pkg_app_ctx.e_invalid_argument,
                'The substitute must be a different teacher.');
        END IF;

        v_slot := pkg_exam_util.get_slot(v_roster.exam_id);

        -- Free the slot first so the head-of-committee unique index and the
        -- eligibility checks see the room without the outgoing teacher.
        UPDATE supervision_roster SET assignment_status = 'REPLACED' WHERE roster_id = p_roster_id;

        IF p_substitute_id IS NULL THEN
            p_substitute_id := pick_teacher(v_roster.exam_id, v_roster.room_id, v_roster.teacher_id);
            IF p_substitute_id IS NULL THEN
                RAISE_APPLICATION_ERROR(pkg_app_ctx.e_no_eligible_teacher,
                    'No eligible substitute is available for roster entry ' || p_roster_id || '.');
            END IF;
        ELSE
            BEGIN
                SELECT teacher_id INTO v_locked
                FROM   teachers WHERE teacher_id = p_substitute_id
                FOR UPDATE WAIT 10;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN
                    RAISE_APPLICATION_ERROR(pkg_app_ctx.e_not_found,
                        'Teacher ' || p_substitute_id || ' does not exist.');
            END;
            v_reason := check_eligibility(p_substitute_id, v_roster.exam_id, v_roster.room_id);
            IF v_reason IS NOT NULL THEN
                RAISE_APPLICATION_ERROR(pkg_app_ctx.e_teacher_ineligible,
                    'Teacher ' || p_substitute_id || ' cannot substitute: ' || v_reason);
            END IF;
        END IF;

        INSERT INTO supervision_roster (exam_id, room_id, teacher_id, role_type, assignment_status)
        VALUES (v_roster.exam_id, v_roster.room_id, p_substitute_id, v_roster.role_type, 'CONFIRMED')
        RETURNING roster_id INTO p_new_roster_id;

        add_hours(v_roster.teacher_id, -v_slot.duration_hours);
        add_hours(p_substitute_id,      v_slot.duration_hours);

        INSERT INTO supervision_audit (roster_id, substitute_teacher_id, executed_by, reason)
        VALUES (p_roster_id, p_substitute_id, p_executed_by, SUBSTR(TRIM(p_reason), 1, 300))
        RETURNING audit_id INTO p_audit_id;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK TO sp_replace_proctor;
            RAISE;
    END replace_proctor;

    -- ------------------------------------------------------------------ UI support
    FUNCTION get_candidates(p_exam_id IN NUMBER, p_room_id IN NUMBER) RETURN SYS_REFCURSOR IS
        v_cur SYS_REFCURSOR;
    BEGIN
        OPEN v_cur FOR
            SELECT teacher_id, teacher_code, full_name, dept_name, hours_balance, eligibility
            FROM (
                SELECT t.teacher_id, t.teacher_code, t.full_name, d.dept_name, t.hours_balance,
                       pkg_supervision.check_eligibility(t.teacher_id, p_exam_id, p_room_id) AS eligibility
                FROM   teachers t
                       JOIN departments d ON d.dept_id = t.dept_id
                WHERE  t.is_deleted = 'N'
            )
            ORDER BY CASE WHEN eligibility IS NULL THEN 0 ELSE 1 END, hours_balance, full_name;
        RETURN v_cur;
    END get_candidates;

END pkg_supervision;
/
