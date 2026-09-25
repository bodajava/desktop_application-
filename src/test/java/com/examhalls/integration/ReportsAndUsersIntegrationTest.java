package com.examhalls.integration;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.CellStatus;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.OccupancyGrid;
import com.examhalls.model.UserAccount;
import com.examhalls.model.UserForm;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.RoleType;
import com.examhalls.service.AuthService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.service.UserManagementService;
import com.examhalls.util.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Screens 8 / 9 / 1-2 against the seed data; everything is rolled back. */
class ReportsAndUsersIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

    private static ServiceRegistry reg;
    private static AuthService auth;
    private static TransactionManager tx;

    @TempDir
    Path dir;

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

    @AfterEach
    void cleanUp() {
        auth.logout();
        Messages.setLocale(Messages.ENGLISH);
    }

    @Test
    void occupancyGridFlagsUnderstaffedRooms() throws Exception {
        auth.login("control", "Control@2026".toCharArray());
        long math10 = exam("MATH-10");
        tx.runAndRollback(() -> {
            reg.examManagementService().generateSeating(math10);
            OccupancyGrid seatedOnly = reg.occupancyService().getGrid(DAY);
            assertTrue(seatedOnly.count(CellStatus.UNDERSTAFFED) > 0, "seated rooms without staff are flagged");
            assertEquals(0, seatedOnly.count(CellStatus.STAFFED));
            assertTrue(seatedOnly.count(CellStatus.UNAVAILABLE) >= 1, "A104 is under maintenance");

            reg.examManagementService().allocateProctors(math10);
            OccupancyGrid staffed = reg.occupancyService().getGrid(DAY);
            assertEquals(0, staffed.count(CellStatus.UNDERSTAFFED));
            assertEquals(seatedOnly.count(CellStatus.UNDERSTAFFED), staffed.count(CellStatus.STAFFED));
            assertEquals(70, staffed.cells().values().stream().mapToInt(c -> c.seated()).sum());
            assertEquals(DAY, reg.occupancyService().nextExamDate(LocalDate.now()).orElseThrow());
            return null;
        });
    }

    @Test
    void everyReportIsGeneratedInBothLanguages() throws Exception {
        auth.login("control", "Control@2026".toCharArray());
        long math10 = exam("MATH-10");
        tx.runAndRollback(() -> {
            reg.examManagementService().generateSeating(math10);
            reg.examManagementService().allocateProctors(math10);
            var reports = reg.reportService();
            for (var locale : new java.util.Locale[]{Messages.ENGLISH, Messages.ARABIC}) {
                Messages.setLocale(locale);
                String l = locale.getLanguage();
                reports.exportSeatingStickers(math10, dir.resolve("s_" + l + ".pdf"));
                reports.exportAttendanceSheets(math10, dir.resolve("a_" + l + ".pdf"));
                reports.exportTeacherSchedule(null, DAY, DAY, dir.resolve("t_" + l + ".pdf"));
                reports.exportDailyControlSheet(DAY, dir.resolve("d_" + l + ".pdf"));
                reports.exportHoursWorkbook(DAY, DAY, dir.resolve("h_" + l + ".xlsx"));
            }
            return null;
        });
        try (var files = Files.list(dir)) {
            var list = files.toList();
            assertEquals(10, list.size(), "no temp files left behind: " + list);
            for (Path p : list) {
                byte[] head = Files.readAllBytes(p);
                String magic = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                assertEquals(p.toString().endsWith(".pdf") ? "%PDF" : "PK\u0003\u0004", magic, p.toString());
                assertTrue(head.length > 1500, p + " is suspiciously small");
            }
        }
    }

    @Test
    void teacherMayExportOnlyOwnSchedule() {
        AuthenticatedUser t = auth.login("teacher", "Teacher@2026".toCharArray());
        reg.reportService().exportTeacherSchedule(t.teacherId(), DAY, DAY, dir.resolve("mine.pdf"));
        assertTrue(Files.exists(dir.resolve("mine.pdf")));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> reg.reportService().exportTeacherSchedule(null, DAY, DAY, dir.resolve("all.pdf"))).code());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> reg.reportService().exportDailyControlSheet(DAY, dir.resolve("d.pdf"))).code());
    }

    @Test
    void userLifecycleAndGuards() throws Exception {
        AuthenticatedUser admin = auth.login("admin", "Admin@2026".toCharArray());
        UserManagementService users = reg.userManagementService();
        long freeTeacher = reg.teacherDao().findByCode("T-MATH-02").orElseThrow().teacherId();

        tx.runAndRollback(() -> {
            // create -> login -> edit -> reset password -> delete (soft)
            long id = users.createUser(new UserForm("new.teacher", "Mahmoud Ibrahim Farouk", RoleType.TEACHER, freeTeacher),
                    "Welcome2027".toCharArray());
            UserAccount created = users.listUsers().stream().filter(u -> u.userId() == id).findFirst().orElseThrow();
            assertEquals(RoleType.TEACHER, created.role());
            assertTrue(created.teacherLabel().contains("T-MATH-02"));

            users.updateUser(id, new UserForm("new.teacher", "M. Farouk", RoleType.COMMITTEE_HEAD, freeTeacher));
            users.resetPassword(id, "Changed2027".toCharArray());
            users.deleteUser(id);
            assertTrue(users.listUsers().stream().noneMatch(u -> u.userId() == id), "archived users are hidden");

            // guards
            assertEquals(ErrorCode.CANNOT_DELETE_SELF,
                    assertThrows(AppException.class, () -> users.deleteUser(admin.userId())).code());
            assertEquals(ErrorCode.LAST_ADMIN, assertThrows(AppException.class, () -> users.updateUser(admin.userId(),
                    new UserForm("admin", "System Administrator", RoleType.CONTROL_OFFICER, null))).code());
            assertEquals(ErrorCode.TEACHER_LINK_REQUIRED, assertThrows(AppException.class,
                    () -> users.createUser(new UserForm("x.teacher", "X", RoleType.TEACHER, null), "Welcome2027".toCharArray())).code());
            assertEquals(ErrorCode.WEAK_PASSWORD, assertThrows(AppException.class,
                    () -> users.createUser(new UserForm("x.user", "X", RoleType.CONTROL_OFFICER, null), "short".toCharArray())).code());
            assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(AppException.class,
                    () -> users.createUser(new UserForm("a b", "X", RoleType.CONTROL_OFFICER, null), "Welcome2027".toCharArray())).code());
            AppException dup = assertThrows(AppException.class, () -> users.createUser(
                    new UserForm("control", "Dup", RoleType.CONTROL_OFFICER, null), "Welcome2027".toCharArray()));
            assertEquals(ErrorCode.DUPLICATE_VALUE, dup.code());
            assertEquals("This username is already taken.", dup.userMessage(Messages.ENGLISH));
            long teacherOfOtherUser = reg.teacherDao().findByCode("T-ENGL-01").orElseThrow().teacherId();
            AppException linked = assertThrows(AppException.class, () -> users.createUser(
                    new UserForm("second.login", "Sarah", RoleType.TEACHER, teacherOfOtherUser), "Welcome2027".toCharArray()));
            assertEquals("This teacher is already linked to another user account.", linked.userMessage(Messages.ENGLISH));
            return null;
        });

        auth.logout();
        auth.login("control", "Control@2026".toCharArray());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class, users::listUsers).code());
    }

    private static long exam(String courseCode) {
        return reg.examScheduleDao().findAll().stream().filter(e -> e.courseCode().equals(courseCode))
                .map(ExamSchedule::examId).findFirst().orElseThrow();
    }
}
