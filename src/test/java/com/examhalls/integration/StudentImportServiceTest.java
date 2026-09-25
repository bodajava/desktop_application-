package com.examhalls.integration;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.model.Student;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.service.AuthService;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.service.StudentImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bulk student+login import from an admin sheet, on seed data; everything rolled back. */
class StudentImportServiceTest {

    private static ServiceRegistry reg;
    private static AuthService auth;
    private static TransactionManager tx;
    private StudentImportService imports;
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
        auth.login("admin", "Admin@2026".toCharArray());
        imports = reg.studentImportService();
        data = reg.masterDataService();
    }

    @AfterEach
    void logout() {
        auth.logout();
    }

    @Test
    void createsStudentAndLoginThenIsIdempotentAndIsolatesBadRows() throws Exception {
        tx.runAndRollback(() -> {
            List<StudentImportService.Row> rows = List.of(
                    new StudentImportService.Row("stu-import-01", "Import Test One", "Grade 10", "c", "one@school.test"),
                    new StudentImportService.Row("", "Missing Code", "Grade 10", "c", null),
                    new StudentImportService.Row("stu-import-02", "Import Test Two", "Grade 11", null, null));

            List<StudentImportService.RowResult> first = imports.importRows(rows);
            assertEquals(3, first.size());
            assertEquals(StudentImportService.Outcome.CREATED_WITH_LOGIN, first.get(0).outcome());
            assertEquals(StudentImportService.Outcome.ERROR, first.get(1).outcome());
            assertEquals(StudentImportService.Outcome.CREATED_WITH_LOGIN, first.get(2).outcome());

            Student created = data.students().stream()
                    .filter(s -> s.studentCode().equals("STU-IMPORT-01")).findFirst().orElseThrow();
            assertEquals("one@school.test", created.email());

            // the new student's own (normalised, upper-case) code is their initial password
            AuthenticatedUser studentLogin = auth.login("stu-import-01", "STU-IMPORT-01".toCharArray());
            assertEquals("STUDENT", studentLogin.role().name());
            auth.logout();
            auth.login("admin", "Admin@2026".toCharArray());

            // re-importing the same row must not duplicate the login
            List<StudentImportService.RowResult> second = imports.importRows(List.of(rows.get(0)));
            assertEquals(StudentImportService.Outcome.LOGIN_ALREADY_EXISTED, second.get(0).outcome());
            long matches = data.students().stream().filter(s -> s.studentCode().equals("STU-IMPORT-01")).count();
            assertEquals(1, matches, "no duplicate student row on re-import");
            return null;
        });
    }

    @Test
    void newStudentMustChangePasswordOnFirstLogin() throws Exception {
        tx.runAndRollback(() -> {
            imports.importRows(List.of(
                    new StudentImportService.Row("stu-import-03", "Import Test Three", "Grade 10", "a", null)));

            AuthenticatedUser first = auth.login("stu-import-03", "STU-IMPORT-03".toCharArray());
            assertTrue(first.mustChangePassword(), "initial login must be flagged");

            reg.authService().changePasswordForCurrentSession("NewPass2026".toCharArray());
            auth.logout();

            AppException oldPasswordRejected = assertThrows(AppException.class,
                    () -> auth.login("stu-import-03", "STU-IMPORT-03".toCharArray()));
            assertEquals("INVALID_CREDENTIALS", oldPasswordRejected.code().name());

            AuthenticatedUser second = auth.login("stu-import-03", "NewPass2026".toCharArray());
            assertFalse(second.mustChangePassword(), "cleared after the forced change");
            auth.logout();
            auth.login("admin", "Admin@2026".toCharArray());
            return null;
        });
    }
}
