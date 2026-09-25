package com.examhalls.integration;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.AllocationResult;
import com.examhalls.model.AllocationState;
import com.examhalls.model.DashboardData;
import com.examhalls.model.DayCapacity;
import com.examhalls.model.DeptWorkload;
import com.examhalls.model.FocusExamRow;
import com.examhalls.model.SeatingResult;
import com.examhalls.service.AuthService;
import com.examhalls.service.DashboardService;
import com.examhalls.service.ExamManagementService;
import com.examhalls.service.ServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Dashboard figures against the live database. Written to hold on ANY data set (the seed or a
 * school's trial data): the first test checks internal consistency, the second seats and staffs
 * one unseated exam inside {@link TransactionManager#runAndRollback} and checks the deltas.
 */
class DashboardIntegrationTest {

    private static ServiceRegistry registry;
    private static AuthService auth;
    private static DashboardService dashboard;
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
        dashboard = registry.dashboardService();
        exams = registry.examManagementService();
        tx = registry.transactions();
    }

    @AfterEach
    void logout() {
        auth.logout();
    }

    @Test
    void figuresAreInternallyConsistent() {
        auth.login("control", "Control@2026".toCharArray());
        DashboardData d = dashboard.getDashboard();

        assertEquals(d.activeExams(), d.allocation().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(d.seated(), d.days().stream().mapToInt(DayCapacity::seated).sum());
        assertEquals(d.hallCapacity(), d.days().stream().mapToInt(DayCapacity::capacity).sum());
        assertTrue(d.filledPositions() <= d.requiredPositions());
        assertTrue(d.coverage() >= 0 && d.coverage() <= 1);
        assertTrue(d.teachersOnDuty() <= d.activeTeachers());
        assertEquals(registry.teacherDao().findAll().size(), d.activeTeachers());
        assertEquals(d.activeTeachers(), d.departments().stream().mapToInt(DeptWorkload::teachers).sum());
        assertEquals(d.examsOnFocusDay(), d.focusExams().size());
        d.focusExams().forEach(r -> assertEquals(d.focusDay(), r.exam().examDate()));
        assertTrue(d.auditVisible(), "control officers see the substitution feed");
        assertTrue(d.recentSubstitutions().size() <= 6);
    }

    @Test
    void rolesSeeWhatTheyMaySee() {
        auth.login("head", "Head@2026".toCharArray());
        DashboardData d = dashboard.getDashboard();
        assertFalse(d.auditVisible(), "committee heads cannot read the audit trail");
        assertTrue(d.recentSubstitutions().isEmpty());

        auth.login("teacher", "Teacher@2026".toCharArray());
        AppException e = assertThrows(AppException.class, () -> dashboard.getDashboard());
        assertEquals(ErrorCode.ACCESS_DENIED, e.code());
    }

    @Test
    void seatingAndStaffingAnExamMovesTheFigures() throws Exception {
        auth.login("control", "Control@2026".toCharArray());
        Optional<LocalDate> date = exams.getExamOverview(LocalDate.now(), LocalDate.now().plusYears(1)).stream()
                .filter(o -> o.seated() == 0 && o.enrolled() > 0).map(o -> o.exam().examDate()).findFirst();
        assumeTrue(date.isPresent(), "no upcoming unseated exam in this data set");
        LocalDate day = date.get();

        tx.runAndRollback(() -> {
            DashboardData before = dashboard.getDashboard(day);   // focus day = the exam's day
            FocusExamRow target = before.focusExams().stream()
                    .filter(r -> r.state() == AllocationState.NOT_SEATED && r.enrolled() > 0).findFirst().orElseThrow();
            long examId = target.exam().examId();

            SeatingResult seated = exams.generateSeating(examId);
            DashboardData afterSeating = dashboard.getDashboard(day);
            FocusExamRow row = row(afterSeating, examId);
            assertEquals(seated.studentsSeated(), row.seated());
            assertEquals(before.seated() + seated.studentsSeated(), afterSeating.seated());
            assertEquals(before.allocation().get(AllocationState.NOT_SEATED) - 1,
                    afterSeating.allocation().get(AllocationState.NOT_SEATED));
            assertNotEquals(AllocationState.NOT_SEATED, row.state());
            assertTrue(afterSeating.requiredPositions() >= before.requiredPositions());

            AllocationResult staffed = exams.allocateProctors(examId, 20, true);
            DashboardData afterAllocation = dashboard.getDashboard(day);
            row = row(afterAllocation, examId);
            if (staffed.complete()) {
                assertEquals(AllocationState.FULL, row.state());
                assertEquals(row.required(), row.filled());
            }
            if (staffed.assigned() > 0) {
                assertTrue(afterAllocation.filledPositions() > afterSeating.filledPositions());
                assertTrue(afterAllocation.teachersOnDuty() > afterSeating.teachersOnDuty());
            }
            return null;
        });
    }

    private static FocusExamRow row(DashboardData d, long examId) {
        return d.focusExams().stream().filter(r -> r.exam().examId() == examId).findFirst().orElseThrow();
    }
}
