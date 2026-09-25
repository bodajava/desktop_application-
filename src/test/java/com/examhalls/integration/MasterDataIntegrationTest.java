package com.examhalls.integration;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.Course;
import com.examhalls.model.Department;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;
import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.Student;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.model.Teacher;
import com.examhalls.service.AuthService;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.util.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Master data CRUD + the database safety rules, on seed data; everything rolled back. */
class MasterDataIntegrationTest {

    private static ServiceRegistry reg;
    private static AuthService auth;
    private static TransactionManager tx;
    private MasterDataService data;

    @BeforeAll
    static void connect() {
        boolean up;
        try {
            up = DatabaseConnection.getInstance().isHealthy();
        } catch (RuntimeException e) {
            up = false;
        }
        assumeTrue(up, "Oracle not reachable - integration tests skipped");
        reg = ServiceRegistry.get();
        auth = reg.authService();
        tx = reg.transactions();
    }

    @BeforeEach
    void login() {
        auth.login("control", "Control@2026".toCharArray());
        data = reg.masterDataService();
    }

    @AfterEach
    void logout() {
        auth.logout();
    }

    @Test
    void teachersAndRelatives() throws Exception {
        tx.runAndRollback(() -> {
            long dept = data.departments().get(0).deptId();
            long id = data.saveTeacher(new Teacher(null, "Test Teacher", "t-test-1", dept, null, 2, 6, null));
            Teacher saved = data.teachers().stream().filter(t -> t.teacherId() == id).findFirst().orElseThrow();
            assertEquals("T-TEST-1", saved.teacherCode(), "code normalised by trigger");
            data.saveTeacher(new Teacher(id, "Test Teacher 2", "T-TEST-1", dept, null, 1, 3, null));
            assertEquals(ErrorCode.VALIDATION_FAILED, code(() ->
                    data.saveTeacher(new Teacher(id, "X", "T-TEST-1", dept, null, 3, 2, null))));
            data.deleteTeacher(id);
            assertTrue(data.teachers().stream().noneMatch(t -> t.teacherId() == id), "archived teacher hidden");

            // relation that collides with an existing duty is stored and reported
            long math10 = exam("MATH-10");
            reg.examManagementService().generateSeating(math10);
            reg.examManagementService().allocateProctors(math10);
            SupervisionRoster duty = reg.examManagementService().getActiveRoster(math10).get(0);
            SeatingAllocation seat = reg.examManagementService().getSeating(math10).get(20);
            int conflicts = data.addRelation(duty.teacherId(), seat.studentId(), 2, "Nephew");
            assertEquals(1, conflicts);
            assertEquals(1, data.relations(duty.teacherId()).size());
            AppException dup = assertThrows(AppException.class, () -> data.addRelation(duty.teacherId(), seat.studentId(), 3, null));
            assertEquals("This teacher-student relation is already registered.", dup.userMessage(Messages.ENGLISH));
            assertEquals(ErrorCode.HAS_FUTURE_DUTIES, code(() -> data.deleteTeacher(duty.teacherId())));
            data.removeRelation(data.relations(duty.teacherId()).get(0).relationId());
            assertTrue(data.relations(duty.teacherId()).isEmpty());
            return null;
        });
    }

    @Test
    void roomsAreProtectedWhileInUse() throws Exception {
        tx.runAndRollback(() -> {
            long id = data.saveRoom(new Room(null, "c-101", "Building C", 30, 20, RoomStatus.AVAILABLE));
            assertTrue(data.rooms().stream().anyMatch(r -> r.roomCode().equals("C-101")));
            assertEquals(ErrorCode.VALIDATION_FAILED, code(() ->
                    data.saveRoom(new Room(id, "C-101", "Building C", 20, 30, RoomStatus.AVAILABLE))));
            data.deleteRoom(id);

            long math10 = exam("MATH-10");
            reg.examManagementService().generateSeating(math10);
            Room hall = data.rooms().stream().filter(r -> r.roomCode().equals("HALL-1")).findFirst().orElseThrow();
            assertEquals(ErrorCode.HAS_FUTURE_DUTIES, code(() -> data.saveRoom(new Room(hall.roomId(), hall.roomCode(),
                    hall.building(), hall.regularCapacity(), hall.examCapacity() - 10, hall.status()))));
            assertEquals(ErrorCode.HAS_FUTURE_DUTIES, code(() -> data.saveRoom(new Room(hall.roomId(), hall.roomCode(),
                    hall.building(), hall.regularCapacity(), hall.examCapacity(), RoomStatus.UNAVAILABLE))));
            assertEquals(ErrorCode.HAS_FUTURE_DUTIES, code(() -> data.deleteRoom(hall.roomId())));
            // growing the capacity is fine
            data.saveRoom(new Room(hall.roomId(), hall.roomCode(), hall.building(), hall.regularCapacity(),
                    hall.examCapacity() + 5, hall.status()));
            return null;
        });
    }

    @Test
    void departmentsCoursesStudents() throws Exception {
        tx.runAndRollback(() -> {
            Department maths = data.departments().stream().filter(d -> d.deptName().equals("Mathematics")).findFirst().orElseThrow();
            AppException deptInUse = assertThrows(AppException.class, () -> data.deleteDepartment(maths.deptId()));
            assertEquals(ErrorCode.CHILD_RECORDS_EXIST, deptInUse.code());
            assertTrue(deptInUse.userMessage(Messages.ENGLISH).startsWith("This department still has"), deptInUse.userMessage(Messages.ENGLISH));

            long newDept = data.saveDepartment(new Department(null, "Religious Studies"));
            long course = data.saveCourse(new Course(null, "Religion", "reli-10", newDept, null, "Grade 10"));
            assertTrue(data.courses().stream().anyMatch(c -> c.courseId() == course && c.courseCode().equals("RELI-10")));
            data.deleteCourse(course);
            data.deleteDepartment(newDept);

            Course math10Course = data.courses().stream().filter(c -> c.courseCode().equals("MATH-10")).findFirst().orElseThrow();
            AppException courseInUse = assertThrows(AppException.class, () -> data.deleteCourse(math10Course.courseId()));
            assertEquals("This course has scheduled exams and cannot be deleted.", courseInUse.userMessage(Messages.ENGLISH));

            long sid = data.saveStudent(new Student(null, "stu-10-900", "New Student", "Grade 10", "c", true, null));
            Student s = data.students().stream().filter(x -> x.studentId() == sid).findFirst().orElseThrow();
            assertEquals("STU-10-900", s.studentCode());
            assertEquals("C", s.section());
            assertTrue(s.hasSpecialNeeds());
            assertTrue(data.gradeLevels().containsAll(List.of("Grade 10", "Grade 11", "Grade 12")));

            long math10 = exam("MATH-10");
            reg.examManagementService().generateSeating(math10);     // the new student gets a seat
            AppException seated = assertThrows(AppException.class, () -> data.deleteStudent(sid));
            assertEquals("This student has seating records and cannot be deleted.", seated.userMessage(Messages.ENGLISH));
            return null;
        });
    }

    @Test
    void examScheduleEditor() throws Exception {
        tx.runAndRollback(() -> {
            Course arab = data.courses().stream().filter(c -> c.courseCode().equals("ARAB-10")).findFirst().orElseThrow();
            long period = data.periods().get(1).periodId();
            LocalDate day = LocalDate.of(2027, 1, 14);
            long id = data.saveExam(ExamSchedule.forInsert(day, arab.courseId(), period, "Dictionary not allowed"));
            AppException dup = assertThrows(AppException.class,
                    () -> data.saveExam(ExamSchedule.forInsert(day, arab.courseId(), period, null)));
            assertEquals("This course already has an exam on that date.", dup.userMessage(Messages.ENGLISH));
            data.deleteExam(id);

            long math10 = exam("MATH-10");
            ExamSchedule e = reg.examScheduleDao().findById(math10).orElseThrow();
            reg.examManagementService().generateSeating(math10);
            assertEquals(ErrorCode.EXAM_HAS_SEATING, code(() -> data.saveExam(new ExamSchedule(math10, e.examDate().plusDays(1),
                    e.courseId(), null, null, null, e.periodId(), null, null, null, null, e.notes()))));
            data.saveExam(new ExamSchedule(math10, e.examDate(), e.courseId(), null, null, null, e.periodId(),
                    null, null, null, null, "Rulers allowed"));                  // notes-only edit is fine
            reg.examManagementService().allocateProctors(math10);
            AppException staffed = assertThrows(AppException.class, () -> data.deleteExam(math10));
            assertEquals(ErrorCode.CHILD_RECORDS_EXIST, staffed.code());
            return null;
        });
    }

    @Test
    void onlyManagementRolesMayEdit() {
        auth.logout();
        auth.login("head", "Head@2026".toCharArray());
        assertEquals(ErrorCode.ACCESS_DENIED, code(() -> data.teachers()));
        assertEquals(ErrorCode.ACCESS_DENIED, code(() -> data.saveDepartment(new Department(null, "X"))));
    }

    private static ErrorCode code(Executable e) {
        return assertThrows(AppException.class, e).code();
    }

    private static long exam(String courseCode) {
        return reg.examScheduleDao().findAll().stream().filter(x -> x.courseCode().equals(courseCode))
                .map(ExamSchedule::examId).findFirst().orElseThrow();
    }
}
