package com.examhalls.integration;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.AllocationResult;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.ExamStage;
import com.examhalls.model.RoomAllocationSummary;
import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;
import com.examhalls.model.SeatingResult;
import com.examhalls.model.SubstitutionResult;
import com.examhalls.model.SupervisionAudit;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.model.TeacherCandidate;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.RoleType;
import com.examhalls.security.UserSession;
import com.examhalls.service.AuthService;
import com.examhalls.service.ExamManagementService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.util.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end: Java -> HikariCP -> Oracle -> PL/SQL, on the seed data (04_seed_data.sql).
 * Skipped automatically when the database is not reachable, so {@code mvn package} still works
 * on machines without Oracle. Every mutating test runs inside
 * {@link TransactionManager#runAndRollback}, so the seed data is never changed.
 */
class DatabaseIntegrationTest {

    private static ServiceRegistry registry;
    private static AuthService auth;
    private static ExamManagementService exams;
    private static TransactionManager tx;

    @BeforeAll
    static void connect() {
        boolean up;
        try {
            up = DatabaseConnection.getInstance().isHealthy();
        } catch (RuntimeException e) {
            up = false;
        }
        assumeTrue(up, "Oracle not reachable - integration tests skipped");
        registry = ServiceRegistry.get();
        auth = registry.authService();
        exams = registry.examManagementService();
        tx = registry.transactions();
    }

    @AfterEach
    void logout() {
        auth.logout();
    }

    // ------------------------------------------------------------------ authentication & RBAC

    @Test
    void seedUsersLogInWithTheirRoles() {
        assertEquals(RoleType.SCHOOL_ADMIN, auth.login("admin", "Admin@2026".toCharArray()).role());
        assertEquals(RoleType.CONTROL_OFFICER, auth.login("CONTROL", "Control@2026".toCharArray()).role());
        assertEquals(RoleType.COMMITTEE_HEAD, auth.login("head", "Head@2026".toCharArray()).role());
        AuthenticatedUser teacher = auth.login("teacher", "Teacher@2026".toCharArray());
        assertEquals(RoleType.TEACHER, teacher.role());
        assertSame(teacher, UserSession.get().requireUser());
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() {
        AppException wrongPassword = assertThrows(AppException.class,
                () -> auth.login("head", "not-the-password1".toCharArray()));
        AppException unknownUser = assertThrows(AppException.class,
                () -> auth.login("no_such_user", "whatever123".toCharArray()));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrongPassword.code());
        assertEquals(wrongPassword.userMessage(Messages.ARABIC), unknownUser.userMessage(Messages.ARABIC));
        assertFalse(UserSession.get().isAuthenticated());
    }

    @Test
    void passwordArrayIsWipedAfterLogin() {
        char[] password = "Admin@2026".toCharArray();
        auth.login("admin", password);
        assertArrayEquals(new char[password.length], password);
    }

    @Test
    void teacherCannotAllocateAndAnonymousCannotSeat() {
        long examId = examByCourse("MATH-10").examId();
        assertEquals(ErrorCode.NOT_AUTHENTICATED,
                assertThrows(AppException.class, () -> exams.generateSeating(examId)).code());

        auth.login("teacher", "Teacher@2026".toCharArray());
        AppException denied = assertThrows(AppException.class, () -> exams.allocateProctors(examId));
        assertEquals(ErrorCode.ACCESS_DENIED, denied.code());
        assertEquals("ليس لديك صلاحية لتنفيذ هذا الإجراء.", denied.userMessage(Messages.ARABIC));
    }

    // ------------------------------------------------------------------ stored procedures

    @Test
    void fullExamDayWorkflowThroughPlsql() throws Exception {
        AuthenticatedUser officer = auth.login("control", "Control@2026".toCharArray());
        long math10 = examByCourse("MATH-10").examId();
        long chem11 = examByCourse("CHEM-11").examId();

        tx.runAndRollback(() -> {
            // 1. Seating (two exams sharing the same slot)
            SeatingResult s1 = exams.generateSeating(math10);
            SeatingResult s2 = exams.generateSeating(chem11);
            assertEquals(70, s1.studentsSeated());
            assertEquals(70, s2.studentsSeated());
            assertEquals(70, exams.getSeating(math10).size());

            // 2. Allocation engine
            AllocationResult a1 = exams.allocateProctors(math10);
            AllocationResult a2 = exams.allocateProctors(chem11);
            assertTrue(a1.complete() && a2.complete());
            List<SupervisionRoster> roster = exams.getActiveRoster(math10);
            assertEquals(a1.assigned(), roster.size());

            // 3. Candidate list: eligibility reasons come back from PL/SQL and are translatable
            SupervisionRoster first = roster.get(0);
            List<TeacherCandidate> candidates = exams.getCandidates(math10, first.roomId());
            assertTrue(candidates.stream().anyMatch(TeacherCandidate::eligible));
            TeacherCandidate mathTeacher = candidates.stream()
                    .filter(c -> c.teacherCode().equals("T-MATH-01")).findFirst().orElseThrow();
            assertEquals("SUBJECT_CONFLICT", mathTeacher.reasonCode());
            assertFalse(mathTeacher.reasonText(Messages.ARABIC).isBlank());

            // 4. Emergency 1-click substitution, logged with the signed-in user
            OptionalLong suggested = exams.suggestSubstitute(first.rosterId());
            assertTrue(suggested.isPresent());
            SubstitutionResult sub = exams.substituteProctor(first.rosterId(), OptionalLong.empty(), "Sudden illness");
            assertEquals(suggested.getAsLong(), sub.substituteTeacherId());
            List<SupervisionAudit> audit = exams.getAuditTrail(math10);
            assertEquals(1, audit.size());
            assertEquals(officer.userId(), audit.get(0).executedBy());
            assertEquals(first.teacherId(), audit.get(0).replacedTeacherId());
            assertEquals(sub.substituteTeacherId(), audit.get(0).substituteTeacherId());

            // 5. PL/SQL business errors arrive as translated AppExceptions
            AppException again = assertThrows(AppException.class,
                    () -> exams.substituteProctor(first.rosterId(), OptionalLong.empty(), "again"));
            assertEquals(ErrorCode.INVALID_STATE, again.code());
            AppException reseat = assertThrows(AppException.class, () -> exams.generateSeating(math10));
            assertEquals(ErrorCode.INVALID_STATE, reseat.code());
            assertTrue(reseat.detail().contains("cancel_allocation"), reseat.detail());
            assertEquals("هذا الإجراء غير مسموح به في الحالة الحالية للامتحان.", reseat.userMessage(Messages.ARABIC));

            // 6. Soft-delete rules enforced by triggers, surfaced through the DAOs
            long busyTeacher = sub.substituteTeacherId();
            AppException duties = assertThrows(AppException.class, () -> registry.teacherDao().delete(busyTeacher));
            assertEquals(ErrorCode.HAS_FUTURE_DUTIES, duties.code());

            assertEquals(a2.assigned(), exams.cancelAllocation(chem11));
            return null;
        });

        // Everything above was rolled back
        assertEquals(0, registry.seatingDao().countByExam(math10));
        assertTrue(registry.rosterDao().findActiveByExam(math10).isEmpty());
    }

    @Test
    void daoSoftDeleteAndConstraintTranslation() throws Exception {
        tx.runAndRollback(() -> {
            Room a104 = registry.roomDao().findByCode("a104").orElseThrow();   // trigger upper-cases codes
            registry.roomDao().delete(a104.roomId());
            assertTrue(registry.roomDao().findByCode("A104").isEmpty(), "hidden from V_ROOMS after soft delete");

            long newId = registry.roomDao().insert(new Room(null, "c301", "Building C", 30, 20, RoomStatus.AVAILABLE));
            assertEquals("C301", registry.roomDao().findById(newId).orElseThrow().roomCode());

            AppException dup = assertThrows(AppException.class, () -> registry.roomDao()
                    .insert(new Room(null, "A101", "Building A - Main", 30, 20, RoomStatus.AVAILABLE)));
            assertEquals(ErrorCode.DUPLICATE_VALUE, dup.code());
            assertEquals("A room with this code already exists.", dup.userMessage(Messages.ENGLISH));

            AppException cap = assertThrows(AppException.class, () -> registry.roomDao()
                    .insert(new Room(null, "C302", "Building C", 20, 40, RoomStatus.AVAILABLE)));
            assertEquals(ErrorCode.CHECK_VIOLATION, cap.code());
            assertEquals("يجب أن تكون سعة الامتحان أكبر من صفر ولا تتجاوز السعة العادية.", cap.userMessage(Messages.ARABIC));
            return null;
        });
        assertTrue(registry.roomDao().findByCode("A104").isPresent(), "soft delete rolled back");
        assertTrue(registry.roomDao().findByCode("C301").isEmpty(), "insert rolled back");
    }

    @Test
    void scheduleStagesAndSupervisionMatrix() throws Exception {
        auth.login("control", "Control@2026".toCharArray());
        long math10 = examByCourse("MATH-10").examId();
        long chem11 = examByCourse("CHEM-11").examId();

        tx.runAndRollback(() -> {
            assertEquals(ExamStage.NOT_SEATED, overview(math10).stage());
            exams.generateSeating(math10);
            exams.generateSeating(chem11);
            assertEquals(ExamStage.SEATED, overview(math10).stage());
            assertEquals(70, overview(math10).seated());
            assertEquals(70, overview(math10).enrolled());

            exams.allocateProctors(math10);
            exams.allocateProctors(chem11);
            assertEquals(ExamStage.STAFFED, overview(math10).stage());

            for (long examId : List.of(math10, chem11)) {
                List<RoomAllocationSummary> rooms = exams.getRoomSummaries(examId);
                assertEquals(70, rooms.stream().mapToInt(RoomAllocationSummary::seatedThisExam).sum());
                for (RoomAllocationSummary r : rooms) {
                    // shared rooms show the staff attached to the other exam too
                    assertEquals(1, r.heads().size(), "one head in " + r.roomCode());
                    assertFalse(r.proctors().isEmpty(), "proctors in " + r.roomCode());
                    assertTrue(r.seatedThisExam() <= r.examCapacity());
                }
            }
            return null;
        });
    }

    @Test
    void linkedTeacherSeesOnlyOwnDuties() throws Exception {
        tx.runAndRollback(() -> {
            auth.login("control", "Control@2026".toCharArray());
            for (String course : List.of("MATH-10", "CHEM-11", "MATH-12", "PHYS-10", "MATH-11")) {
                long id = examByCourse(course).examId();
                exams.generateSeating(id);
                exams.allocateProctors(id);
            }
            auth.logout();

            AuthenticatedUser teacher = auth.login("teacher", "Teacher@2026".toCharArray());
            assertNotNull(teacher.teacherId(), "seed login 'teacher' is linked to T-ENGL-01");
            List<SupervisionRoster> mine = exams.getMyUpcomingDuties();
            assertEquals(registry.rosterDao().findUpcomingByTeacher(teacher.teacherId(), LocalDate.now()).size(),
                    mine.size());
            assertTrue(mine.stream().allMatch(d -> d.teacherCode().equals("T-ENGL-01")));
            // the relatives rule keeps T-ENGL-01 (son in Grade 10) out of the MATH-10 / CHEM-11 slot
            assertTrue(mine.stream().noneMatch(d -> d.courseCode().equals("MATH-10") || d.courseCode().equals("CHEM-11")));
            assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                    () -> exams.getExamOverview(LocalDate.now(), LocalDate.now().plusYears(1))).code());
            return null;
        });
    }

    private static ExamOverview overview(long examId) {
        return exams.getExamOverview(LocalDate.now(), LocalDate.now().plusYears(1)).stream()
                .filter(o -> o.exam().examId() == examId).findFirst().orElseThrow();
    }

    private static ExamSchedule examByCourse(String courseCode) {
        return registry.examScheduleDao().findAll().stream()
                .filter(e -> e.courseCode().equals(courseCode)).findFirst().orElseThrow();
    }
}
