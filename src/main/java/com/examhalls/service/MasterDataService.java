package com.examhalls.service;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.CourseDao;
import com.examhalls.dao.DepartmentDao;
import com.examhalls.dao.ExamPeriodDao;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.RelationDao;
import com.examhalls.dao.RoomDao;
import com.examhalls.dao.SeatingAllocationDao;
import com.examhalls.dao.StudentDao;
import com.examhalls.dao.TeacherDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.exception.OracleErrorTranslator;
import com.examhalls.model.Course;
import com.examhalls.model.Department;
import com.examhalls.model.ExamPeriod;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.Room;
import com.examhalls.model.Student;
import com.examhalls.model.Teacher;
import com.examhalls.model.TeacherStudentRelation;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import com.examhalls.util.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Master data maintenance (teachers, relatives, rooms, departments, courses, students, exam
 * schedule) for School Admin and Control Officer. Validation here is the backstop behind the
 * form dialogs; the database constraints and triggers are the final authority.
 */
public class MasterDataService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataService.class);

    private final TransactionManager tx;
    private final UserSession session;
    private final DepartmentDao departmentDao;
    private final CourseDao courseDao;
    private final TeacherDao teacherDao;
    private final RelationDao relationDao;
    private final RoomDao roomDao;
    private final StudentDao studentDao;
    private final ExamPeriodDao periodDao;
    private final ExamScheduleDao examDao;
    private final SeatingAllocationDao seatingDao;

    public MasterDataService(TransactionManager tx, UserSession session, DepartmentDao departmentDao,
                             CourseDao courseDao, TeacherDao teacherDao, RelationDao relationDao, RoomDao roomDao,
                             StudentDao studentDao, ExamPeriodDao periodDao, ExamScheduleDao examDao,
                             SeatingAllocationDao seatingDao) {
        this.tx = tx;
        this.session = session;
        this.departmentDao = departmentDao;
        this.courseDao = courseDao;
        this.teacherDao = teacherDao;
        this.relationDao = relationDao;
        this.roomDao = roomDao;
        this.studentDao = studentDao;
        this.periodDao = periodDao;
        this.examDao = examDao;
        this.seatingDao = seatingDao;
    }

    // ================================================================== departments & courses

    public List<Department> departments() {
        read();
        return departmentDao.findAll();
    }

    public long saveDepartment(Department d) {
        AuthenticatedUser me = write();
        require(!blank(d.deptName()) && d.deptName().trim().length() <= 100, "master.field.deptName");
        Department clean = new Department(d.deptId(), d.deptName().trim());
        long id = upsert(clean.deptId(), () -> departmentDao.insert(clean), () -> departmentDao.update(clean));
        log.info("{} saved department #{} '{}'", me.username(), id, clean.deptName());
        return id;
    }

    public void deleteDepartment(long id) {
        AuthenticatedUser me = write();
        departmentDao.delete(id);
        log.info("{} deleted department #{}", me.username(), id);
    }

    public List<Course> courses() {
        read();
        return courseDao.findAll();
    }

    public long saveCourse(Course c) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, !blank(c.courseCode()) && c.courseCode().trim().length() <= 20, "master.field.courseCode");
        check(bad, !blank(c.courseName()) && c.courseName().trim().length() <= 100, "master.field.courseName");
        check(bad, c.deptId() != null, "master.field.department");
        check(bad, !blank(c.gradeLevel()) && c.gradeLevel().trim().length() <= 30, "master.field.gradeLevel");
        fail(bad);
        Course clean = new Course(c.courseId(), c.courseName().trim(), c.courseCode().trim().toUpperCase(),
                c.deptId(), null, c.gradeLevel().trim());
        long id = upsert(clean.courseId(), () -> courseDao.insert(clean), () -> courseDao.update(clean));
        log.info("{} saved course #{} {}", me.username(), id, clean.courseCode());
        return id;
    }

    public void deleteCourse(long id) {
        AuthenticatedUser me = write();
        courseDao.delete(id);
        log.info("{} deleted course #{}", me.username(), id);
    }

    /** Distinct grade levels used by courses and students, sorted. */
    public List<String> gradeLevels() {
        read();
        return Stream.concat(courseDao.findAll().stream().map(Course::gradeLevel),
                        studentDao.findAll().stream().map(Student::gradeLevel))
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new)).stream().toList();
    }

    // ================================================================== teachers & relatives

    public List<Teacher> teachers() {
        read();
        return teacherDao.findAll();
    }

    public long saveTeacher(Teacher t) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, !blank(t.fullName()) && t.fullName().trim().length() <= 150, "master.field.fullName");
        check(bad, !blank(t.teacherCode()) && t.teacherCode().trim().length() <= 30, "master.field.teacherCode");
        check(bad, t.deptId() != null, "master.field.department");
        check(bad, t.maxDailyLoad() >= 0 && t.maxDailyLoad() <= 10, "master.field.dailyLoad");
        check(bad, t.maxWeeklyLoad() >= t.maxDailyLoad() && t.maxWeeklyLoad() <= 99, "master.field.weeklyLoad");
        fail(bad);
        Teacher clean = new Teacher(t.teacherId(), t.fullName().trim(), t.teacherCode().trim(), t.deptId(), null,
                t.maxDailyLoad(), t.maxWeeklyLoad(), null);
        long id = upsert(clean.teacherId(), () -> teacherDao.insert(clean), () -> teacherDao.update(clean));
        log.info("{} saved teacher #{} {}", me.username(), id, clean.teacherCode());
        return id;
    }

    /** Soft delete (V_TEACHERS); refused by the trigger while the teacher has upcoming duties. */
    public void deleteTeacher(long id) {
        AuthenticatedUser me = write();
        teacherDao.delete(id);
        log.info("{} archived teacher #{}", me.username(), id);
    }

    public List<TeacherStudentRelation> relations(long teacherId) {
        read();
        return relationDao.findByTeacher(teacherId);
    }

    /**
     * Records a relative. The relation is always stored (it is a fact); the return value is the
     * number of the teacher's upcoming duties that now conflict and need a substitution.
     */
    public int addRelation(long teacherId, long studentId, int degree, String notes) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, degree >= 1 && degree <= 4, "master.field.degree");
        check(bad, notes == null || notes.trim().length() <= 200, "master.field.notes");
        fail(bad);
        return run(() -> {
            relationDao.insert(new TeacherStudentRelation(null, teacherId, studentId, null, null, null, degree,
                    blank(notes) ? null : notes.trim()));
            int conflicts = relationDao.countConflictingDuties(teacherId, studentId);
            log.info("{} recorded relation teacher #{} - student #{} (degree {}), {} conflicting duties",
                    me.username(), teacherId, studentId, degree, conflicts);
            return conflicts;
        });
    }

    public void removeRelation(long relationId) {
        AuthenticatedUser me = write();
        relationDao.delete(relationId);
        log.info("{} removed relation #{}", me.username(), relationId);
    }

    // ================================================================== rooms

    public List<Room> rooms() {
        read();
        return roomDao.findAll();
    }

    /** Status changes and capacity reductions are refused by the trigger while future exams use the room. */
    public long saveRoom(Room r) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, !blank(r.roomCode()) && r.roomCode().trim().length() <= 20, "master.field.roomCode");
        check(bad, !blank(r.building()) && r.building().trim().length() <= 50, "master.field.building");
        check(bad, r.regularCapacity() > 0 && r.regularCapacity() <= 9999, "master.field.regularCapacity");
        check(bad, r.examCapacity() > 0 && r.examCapacity() <= r.regularCapacity(), "master.field.examCapacity");
        check(bad, r.status() != null, "master.field.status");
        fail(bad);
        Room clean = new Room(r.roomId(), r.roomCode().trim(), r.building().trim(), r.regularCapacity(),
                r.examCapacity(), r.status());
        long id = upsert(clean.roomId(), () -> roomDao.insert(clean), () -> roomDao.update(clean));
        log.info("{} saved room #{} {}", me.username(), id, clean.roomCode());
        return id;
    }

    public void deleteRoom(long id) {
        AuthenticatedUser me = write();
        roomDao.delete(id);
        log.info("{} archived room #{}", me.username(), id);
    }

    // ================================================================== students

    public List<Student> students() {
        read();
        return studentDao.findAll();
    }

    public long saveStudent(Student s) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, !blank(s.studentCode()) && s.studentCode().trim().length() <= 30, "master.field.studentCode");
        check(bad, !blank(s.fullName()) && s.fullName().trim().length() <= 150, "master.field.fullName");
        check(bad, !blank(s.gradeLevel()) && s.gradeLevel().trim().length() <= 30, "master.field.gradeLevel");
        check(bad, s.section() == null || s.section().trim().length() <= 10, "master.field.section");
        fail(bad);
        Student clean = new Student(s.studentId(), s.studentCode().trim().toUpperCase(), s.fullName().trim(),
                s.gradeLevel().trim(), blank(s.section()) ? null : s.section().trim().toUpperCase(), s.hasSpecialNeeds());
        long id = upsert(clean.studentId(), () -> studentDao.insert(clean), () -> studentDao.update(clean));
        log.info("{} saved student #{} {}", me.username(), id, clean.studentCode());
        return id;
    }

    /** Physical delete; refused (translated FK error) once the student has seating records. */
    public void deleteStudent(long id) {
        AuthenticatedUser me = write();
        studentDao.delete(id);
        log.info("{} deleted student #{}", me.username(), id);
    }

    // ================================================================== exam schedule

    public List<ExamPeriod> periods() {
        read();
        return periodDao.findAll();
    }

    public long saveExam(ExamSchedule e) {
        AuthenticatedUser me = write();
        List<String> bad = new ArrayList<>();
        check(bad, e.examDate() != null, "master.field.examDate");
        check(bad, e.courseId() != null, "master.field.course");
        check(bad, e.periodId() != null, "master.field.period");
        check(bad, e.notes() == null || e.notes().trim().length() <= 300, "master.field.notes");
        fail(bad);
        ExamSchedule clean = ExamSchedule.forInsert(e.examDate(), e.courseId(), e.periodId(),
                blank(e.notes()) ? null : e.notes().trim());
        long id = run(() -> {
            if (e.examId() == null) {
                return examDao.insert(clean);
            }
            ExamSchedule current = examDao.findById(e.examId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Exam " + e.examId()));
            boolean slotChanged = !current.examDate().equals(e.examDate()) || !current.periodId().equals(e.periodId())
                    || !current.courseId().equals(e.courseId());
            if (slotChanged && seatingDao.countByExam(e.examId()) > 0) {
                throw new AppException(ErrorCode.EXAM_HAS_SEATING, "exam " + e.examId() + " is seated");
            }
            examDao.update(new ExamSchedule(e.examId(), clean.examDate(), clean.courseId(), null, null, null,
                    clean.periodId(), null, null, null, null, clean.notes()));
            return e.examId();
        });
        log.info("{} saved exam #{} ({} {})", me.username(), id, e.examDate(), e.courseId());
        return id;
    }

    /** Seating is removed with the exam; refused (translated FK error) once supervision records exist. */
    public void deleteExam(long id) {
        AuthenticatedUser me = write();
        examDao.delete(id);
        log.info("{} deleted exam #{}", me.username(), id);
    }

    // ================================================================== helpers

    private void read() {
        session.require(Permission.MANAGE_MASTER_DATA);
    }

    private AuthenticatedUser write() {
        return session.require(Permission.MANAGE_MASTER_DATA);
    }

    private long upsert(Long id, java.util.function.LongSupplier insert, Runnable update) {
        if (id == null) {
            return insert.getAsLong();
        }
        update.run();
        return id;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void check(List<String> bad, boolean ok, String fieldKey) {
        if (!ok) {
            bad.add(fieldKey);
        }
    }

    private static void require(boolean ok, String fieldKey) {
        fail(ok ? List.of() : List.of(fieldKey));
    }

    /** VALIDATION_FAILED listing the translated field labels. */
    private static void fail(List<String> fieldKeys) {
        if (fieldKeys.isEmpty()) {
            return;
        }
        String fields = fieldKeys.stream().map(Messages::get).collect(Collectors.joining(Messages.isRightToLeft() ? "، " : ", "));
        throw new AppException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.messageKey(),
                String.join(",", fieldKeys), null, fields);
    }

    private <T> T run(TransactionManager.SqlWork<T> work) {
        try {
            return tx.inTransaction(work);
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }
}
