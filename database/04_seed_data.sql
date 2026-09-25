--------------------------------------------------------------------------------
-- Exam Halls & Proctoring Allocation Management System
-- 04_seed_data.sql  —  Realistic mock data for development and testing
--------------------------------------------------------------------------------
-- Contents
--   4 roles, 4 users, 8 departments, 12 courses (Grades 10-12), 10 rooms,
--   2 exam periods, 24 teachers, 210 students (70 per grade), 9 exams in
--   January 2027 (incl. two exams sharing one slot), 5 kinship relations.
--
-- Seed logins (BCrypt cost 12). CHANGE THESE PASSWORDS AFTER FIRST LOGIN.
--   admin    / Admin@2026    (SCHOOL_ADMIN)
--   control  / Control@2026  (CONTROL_OFFICER)
--   head     / Head@2026     (COMMITTEE_HEAD, linked to teacher T-PHYS-01)
--   teacher  / Teacher@2026  (TEACHER, linked to teacher T-ENGL-01)
--------------------------------------------------------------------------------

SET DEFINE OFF

-- ---------------------------------------------------------------- roles & users
-- ROLE_NAME values are the Java enum com.examhalls.security.RoleType constants.
INSERT INTO roles (role_name, role_description) VALUES ('SCHOOL_ADMIN',    'School administration: full access incl. user management');
INSERT INTO roles (role_name, role_description) VALUES ('CONTROL_OFFICER', 'Exam control: master data, seating, proctor allocation, substitutions');
INSERT INTO roles (role_name, role_description) VALUES ('COMMITTEE_HEAD',  'Head of an exam committee: attendance and room-level operations');
INSERT INTO roles (role_name, role_description) VALUES ('TEACHER',         'Teacher: views own supervision schedule');

INSERT INTO users (username, password_hash, full_name, role_id)
VALUES ('admin', '$2a$12$go5qppVf2OqsXNkLnmc4FO0muIlImlZrSBRD3dLeaUgVM5FjYpi/6', 'System Administrator',
        (SELECT role_id FROM roles WHERE role_name = 'SCHOOL_ADMIN'));
INSERT INTO users (username, password_hash, full_name, role_id)
VALUES ('control', '$2a$12$RXfNMAx65aB1o3XGcOTN8OXh3i3eK6kHYLYFKCDhLmZGAXv6ySLL.', 'Mona Abdel-Aziz',
        (SELECT role_id FROM roles WHERE role_name = 'CONTROL_OFFICER'));
-- ---------------------------------------------------------------- departments
INSERT INTO departments (dept_name) VALUES ('Mathematics');
INSERT INTO departments (dept_name) VALUES ('Physics');
INSERT INTO departments (dept_name) VALUES ('Chemistry');
INSERT INTO departments (dept_name) VALUES ('Biology');
INSERT INTO departments (dept_name) VALUES ('Arabic Language');
INSERT INTO departments (dept_name) VALUES ('English Language');
INSERT INTO departments (dept_name) VALUES ('Computer Science');
INSERT INTO departments (dept_name) VALUES ('Social Studies');

-- ---------------------------------------------------------------- courses
INSERT INTO courses (course_name, course_code, dept_id, grade_level)
SELECT c.course_name, c.course_code, d.dept_id, c.grade_level
FROM (
    SELECT 'Algebra & Geometry'      AS course_name, 'MATH-10' AS course_code, 'Mathematics'      AS dept_name, 'Grade 10' AS grade_level FROM dual UNION ALL
    SELECT 'General Physics I',                      'PHYS-10',               'Physics',                       'Grade 10' FROM dual UNION ALL
    SELECT 'Arabic Language I',                      'ARAB-10',               'Arabic Language',               'Grade 10' FROM dual UNION ALL
    SELECT 'English Language I',                     'ENGL-10',               'English Language',              'Grade 10' FROM dual UNION ALL
    SELECT 'Calculus I',                             'MATH-11',               'Mathematics',                   'Grade 11' FROM dual UNION ALL
    SELECT 'Organic Chemistry',                      'CHEM-11',               'Chemistry',                     'Grade 11' FROM dual UNION ALL
    SELECT 'Human Biology',                          'BIOL-11',               'Biology',                       'Grade 11' FROM dual UNION ALL
    SELECT 'History & Geography',                    'SOCS-11',               'Social Studies',                'Grade 11' FROM dual UNION ALL
    SELECT 'Calculus II',                            'MATH-12',               'Mathematics',                   'Grade 12' FROM dual UNION ALL
    SELECT 'Electricity & Magnetism',                'PHYS-12',               'Physics',                       'Grade 12' FROM dual UNION ALL
    SELECT 'Programming Fundamentals',               'COMP-12',               'Computer Science',              'Grade 12' FROM dual UNION ALL
    SELECT 'English Language III',                   'ENGL-12',               'English Language',              'Grade 12' FROM dual
) c
JOIN departments d ON d.dept_name = c.dept_name;

-- ---------------------------------------------------------------- rooms
-- exam_capacity < regular_capacity: spaced seating for exams.
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A101',  'Building A - Main',    40, 24, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A102',  'Building A - Main',    40, 24, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A103',  'Building A - Main',    40, 24, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A104',  'Building A - Main',    40, 24, 'MAINTENANCE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A201',  'Building A - Main',    35, 20, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('A202',  'Building A - Main',    35, 20, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('B101',  'Building B - Science', 50, 30, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('B102',  'Building B - Science', 50, 30, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('B-LAB1','Building B - Science', 24, 16, 'AVAILABLE');
INSERT INTO rooms (room_code, building, regular_capacity, exam_capacity, room_status) VALUES ('HALL-1','Sports Hall',         150, 60, 'AVAILABLE');

-- ---------------------------------------------------------------- exam periods
-- Only the time-of-day matters; the date part is a fixed reference day.
INSERT INTO exam_periods (period_name, start_time, end_time)
VALUES ('First Period',  TO_DATE('2000-01-01 09:00', 'YYYY-MM-DD HH24:MI'), TO_DATE('2000-01-01 11:00', 'YYYY-MM-DD HH24:MI'));
INSERT INTO exam_periods (period_name, start_time, end_time)
VALUES ('Second Period', TO_DATE('2000-01-01 12:00', 'YYYY-MM-DD HH24:MI'), TO_DATE('2000-01-01 14:30', 'YYYY-MM-DD HH24:MI'));

-- ---------------------------------------------------------------- teachers (3 per department)
INSERT INTO teachers (full_name, teacher_code, dept_id, max_daily_load, max_weekly_load)
SELECT t.full_name, t.teacher_code, d.dept_id, t.daily, t.weekly
FROM (
    SELECT 'Ahmed Hassan El-Sayed'   AS full_name, 'T-MATH-01' AS teacher_code, 'Mathematics'      AS dept_name, 2 AS daily, 8 AS weekly FROM dual UNION ALL
    SELECT 'Mahmoud Ibrahim Farouk',               'T-MATH-02',                 'Mathematics',                   2,          8 FROM dual UNION ALL
    SELECT 'Nadia Samir Khalil',                   'T-MATH-03',                 'Mathematics',                   1,          5 FROM dual UNION ALL
    SELECT 'Omar Tarek Mansour',                   'T-PHYS-01',                 'Physics',                       2,          8 FROM dual UNION ALL
    SELECT 'Heba Mostafa Ali',                     'T-PHYS-02',                 'Physics',                       2,          8 FROM dual UNION ALL
    SELECT 'Youssef Adel Nasser',                  'T-PHYS-03',                 'Physics',                       2,          6 FROM dual UNION ALL
    SELECT 'Rania Mohamed Saleh',                  'T-CHEM-01',                 'Chemistry',                     2,          8 FROM dual UNION ALL
    SELECT 'Khaled Waleed Amin',                   'T-CHEM-02',                 'Chemistry',                     2,          8 FROM dual UNION ALL
    SELECT 'Dina Hesham Rashad',                   'T-CHEM-03',                 'Chemistry',                     1,          4 FROM dual UNION ALL
    SELECT 'Sherif Ashraf Gamal',                  'T-BIOL-01',                 'Biology',                       2,          8 FROM dual UNION ALL
    SELECT 'Mariam Fawzy Lotfy',                   'T-BIOL-02',                 'Biology',                       2,          8 FROM dual UNION ALL
    SELECT 'Tamer Nabil Zaki',                     'T-BIOL-03',                 'Biology',                       2,          8 FROM dual UNION ALL
    SELECT 'Samia Abdel-Rahman Fouad',             'T-ARAB-01',                 'Arabic Language',               2,          8 FROM dual UNION ALL
    SELECT 'Hany Magdy Soliman',                   'T-ARAB-02',                 'Arabic Language',               2,          8 FROM dual UNION ALL
    SELECT 'Aya Reda Shawky',                      'T-ARAB-03',                 'Arabic Language',               2,          6 FROM dual UNION ALL
    SELECT 'Sarah Kamal Wahba',                    'T-ENGL-01',                 'English Language',              2,          8 FROM dual UNION ALL
    SELECT 'Mostafa Hamdy Barakat',                'T-ENGL-02',                 'English Language',              2,          8 FROM dual UNION ALL
    SELECT 'Laila Osama Helmy',                    'T-ENGL-03',                 'English Language',              2,          8 FROM dual UNION ALL
    SELECT 'Amr Sayed Radwan',                     'T-COMP-01',                 'Computer Science',              2,          8 FROM dual UNION ALL
    SELECT 'Noha Essam Badawi',                    'T-COMP-02',                 'Computer Science',              2,          8 FROM dual UNION ALL
    SELECT 'Hossam Galal Moussa',                  'T-COMP-03',                 'Computer Science',              2,          8 FROM dual UNION ALL
    SELECT 'Fatma Yasser Abdallah',                'T-SOCS-01',                 'Social Studies',                2,          8 FROM dual UNION ALL
    SELECT 'Wael Emad Morsy',                      'T-SOCS-02',                 'Social Studies',                2,          8 FROM dual UNION ALL
    SELECT 'Eman Adly Tawfik',                     'T-SOCS-03',                 'Social Studies',                2,          8 FROM dual
) t
JOIN departments d ON d.dept_name = t.dept_name;

-- ---------------------------------------------------------------- logins linked to teacher records
INSERT INTO users (username, password_hash, full_name, role_id, teacher_id)
SELECT 'head', '$2a$12$2sb0gg/QBcnJys1FHp09ZOVUEG5O.lM1UpVMkjlTeXy240wGxhkEG', t.full_name, r.role_id, t.teacher_id
FROM   teachers t, roles r
WHERE  t.teacher_code = 'T-PHYS-01' AND r.role_name = 'COMMITTEE_HEAD';

INSERT INTO users (username, password_hash, full_name, role_id, teacher_id)
SELECT 'teacher', '$2a$12$oXU2owFVgHL8rCg1LZZOGuHlhaczOjGPN1L4AnhoIfIq/Hh5Mykiu', t.full_name, r.role_id, t.teacher_id
FROM   teachers t, roles r
WHERE  t.teacher_code = 'T-ENGL-01' AND r.role_name = 'TEACHER';

-- ---------------------------------------------------------------- students (70 per grade)
-- Deterministic name generation; every 15th student has special needs.
DECLARE
    TYPE t_names IS TABLE OF VARCHAR2(40);
    v_first  t_names := t_names('Adam', 'Ali', 'Amira', 'Basma', 'Farida', 'Hamza', 'Hana', 'Jana',
                                'Karim', 'Laila', 'Malak', 'Mariam', 'Mohamed', 'Nour', 'Omar', 'Rana',
                                'Salma', 'Seif', 'Tarek', 'Yara', 'Yassin', 'Youssef', 'Zeina', 'Ziad');
    v_middle t_names := t_names('Ahmed', 'Mahmoud', 'Mostafa', 'Hassan', 'Ibrahim', 'Khaled', 'Tamer',
                                'Sherif', 'Hany', 'Amr', 'Wael', 'Adel', 'Sameh');
    v_last   t_names := t_names('El-Sayed', 'Farouk', 'Mansour', 'Nasser', 'Saleh', 'Amin', 'Gamal',
                                'Zaki', 'Soliman', 'Wahba', 'Barakat', 'Helmy', 'Radwan', 'Badawi',
                                'Moussa', 'Morsy', 'Tawfik', 'Fouad', 'Shawky', 'Lotfy', 'Rashad');
    v_grades t_names := t_names('Grade 10', 'Grade 11', 'Grade 12');
    v_n      PLS_INTEGER := 0;
    v_code   students.student_code%TYPE;
    v_name   students.full_name%TYPE;
    v_grade  students.grade_level%TYPE;
    v_sn     students.has_special_needs%TYPE;
BEGIN
    FOR g IN 1 .. v_grades.COUNT LOOP
        FOR i IN 1 .. 70 LOOP
            v_n     := v_n + 1;
            -- Collections can't be referenced inside SQL, so build the values in PL/SQL first.
            v_code  := 'STU-' || SUBSTR(v_grades(g), -2) || '-' || LPAD(i, 3, '0');
            v_name  := v_first(MOD(v_n * 7, v_first.COUNT) + 1) || ' ' ||
                       v_middle(MOD(v_n * 5, v_middle.COUNT) + 1) || ' ' ||
                       v_last(MOD(v_n * 11, v_last.COUNT) + 1);
            v_grade := v_grades(g);
            v_sn    := CASE WHEN MOD(i, 15) = 0 THEN 'Y' ELSE 'N' END;
            INSERT INTO students (student_code, full_name, grade_level, section, has_special_needs)
            VALUES (v_code, v_name, v_grade, CASE WHEN i <= 35 THEN 'A' ELSE 'B' END, v_sn);
        END LOOP;
    END LOOP;
END;
/

-- ---------------------------------------------------------------- exam schedule (January 2027, Sun-Thu)
INSERT INTO exam_schedule (exam_date, course_id, period_id, notes)
SELECT TO_DATE(x.exam_date, 'YYYY-MM-DD'), c.course_id, p.period_id, x.notes
FROM (
    SELECT '2027-01-10' AS exam_date, 'MATH-10' AS course_code, 'First Period'  AS period_name, 'Calculators not allowed' AS notes FROM dual UNION ALL
    SELECT '2027-01-10',              'CHEM-11',                'First Period',                 'Shares the slot with MATH-10 (room sharing test)' FROM dual UNION ALL
    SELECT '2027-01-10',              'MATH-12',                'Second Period',                NULL FROM dual UNION ALL
    SELECT '2027-01-11',              'PHYS-10',                'First Period',                 'Scientific calculators allowed' FROM dual UNION ALL
    SELECT '2027-01-11',              'MATH-11',                'Second Period',                NULL FROM dual UNION ALL
    SELECT '2027-01-12',              'PHYS-12',                'First Period',                 NULL FROM dual UNION ALL
    SELECT '2027-01-12',              'BIOL-11',                'Second Period',                NULL FROM dual UNION ALL
    SELECT '2027-01-13',              'ARAB-10',                'First Period',                 NULL FROM dual UNION ALL
    SELECT '2027-01-13',              'COMP-12',                'Second Period',                'Written paper, no computers' FROM dual
) x
JOIN courses      c ON c.course_code = x.course_code
JOIN exam_periods p ON p.period_name = x.period_name;

-- ---------------------------------------------------------------- kinship relations (conflict of interest)
INSERT INTO teacher_student_relations (teacher_id, student_id, relation_degree, notes)
SELECT t.teacher_id, s.student_id, r.degree, r.notes
FROM (
    SELECT 'T-ENGL-01' AS teacher_code, 'STU-10-001' AS student_code, 1 AS degree, 'Son'         AS notes FROM dual UNION ALL
    SELECT 'T-ARAB-02',                 'STU-10-020',                 2,           'Nephew'               FROM dual UNION ALL
    SELECT 'T-COMP-01',                 'STU-11-005',                 3,           'Niece'                FROM dual UNION ALL
    SELECT 'T-BIOL-02',                 'STU-12-010',                 4,           'Cousin''s son'        FROM dual UNION ALL
    SELECT 'T-SOCS-01',                 'STU-10-033',                 1,           'Daughter'             FROM dual
) r
JOIN teachers t ON t.teacher_code = r.teacher_code
JOIN students s ON s.student_code = r.student_code;

COMMIT;
