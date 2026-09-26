package com.examhalls.service;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.RoleDao;
import com.examhalls.dao.StudentDao;
import com.examhalls.dao.UserDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.exception.OracleErrorTranslator;
import com.examhalls.model.Role;
import com.examhalls.model.Student;
import com.examhalls.model.User;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.PasswordHasher;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bulk-creates student rosters and their logins from an admin-supplied sheet (School Admin only).
 * Each row is its own transaction, so one bad row cannot abort the rest of the import. The initial
 * password is the student's own code; {@code MUST_CHANGE_PASSWORD} forces a change on first login.
 */
public class StudentImportService {

    private static final Logger log = LoggerFactory.getLogger(StudentImportService.class);

    /** One row from the admin's sheet, already trimmed by the caller. */
    public record Row(String studentCode, String fullName, String gradeLevel, String section, String email) {
    }

    public enum Outcome { CREATED_WITH_LOGIN, UPDATED, LOGIN_ALREADY_EXISTED, ERROR }

    public record RowResult(int rowNumber, String studentCode, Outcome outcome, String message) {
    }

    private final TransactionManager tx;
    private final UserSession session;
    private final StudentDao studentDao;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final PasswordHasher hasher;
    private final EmailService emailService;

    public StudentImportService(TransactionManager tx, UserSession session, StudentDao studentDao, UserDao userDao,
                                RoleDao roleDao, PasswordHasher hasher, EmailService emailService) {
        this.tx = tx;
        this.session = session;
        this.studentDao = studentDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.hasher = hasher;
        this.emailService = emailService;
    }

    public List<RowResult> importRows(List<Row> rows) {
        AuthenticatedUser me = session.require(Permission.MANAGE_USERS);
        long studentRoleId = roleDao.findByName("STUDENT").map(Role::roleId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Role STUDENT missing in ROLES"));

        List<RowResult> results = new ArrayList<>();
        int rowNumber = 1;
        int created = 0, updated = 0, errors = 0;
        for (Row row : rows) {
            int currentRow = rowNumber;
            try {
                RowResult r = run(() -> importOneRow(currentRow, row, studentRoleId));
                results.add(r);
                if (r.outcome() == Outcome.CREATED_WITH_LOGIN) {
                    created++;
                    emailCredentialsIfPossible(row, r.studentCode());
                } else if (r.outcome() == Outcome.ERROR) {
                    errors++;
                } else {
                    updated++;
                }
            } catch (AppException ex) {
                errors++;
                results.add(new RowResult(currentRow, row.studentCode(), Outcome.ERROR, ex.userMessage()));
            }
            rowNumber++;
        }
        log.info("{} imported students: {} created, {} updated/unchanged, {} errors ({} rows)",
                me.username(), created, updated, errors, rows.size());
        return results;
    }

    private RowResult importOneRow(int rowNumber, Row row, long studentRoleId) {
        List<String> bad = new ArrayList<>();
        if (blank(row.studentCode()) || row.studentCode().trim().length() > 30) {
            bad.add("studentCode");
        }
        if (blank(row.fullName()) || row.fullName().trim().length() > 150) {
            bad.add("fullName");
        }
        if (blank(row.gradeLevel()) || row.gradeLevel().trim().length() > 30) {
            bad.add("gradeLevel");
        }
        if (!bad.isEmpty()) {
            return new RowResult(rowNumber, row.studentCode(), Outcome.ERROR, "Missing/invalid: " + String.join(", ", bad));
        }

        String code = row.studentCode().trim().toUpperCase();
        String section = blank(row.section()) ? null : row.section().trim().toUpperCase();
        String email = blank(row.email()) ? null : row.email().trim();

        Optional<Student> existing = studentDao.findByCode(code);
        long studentId;
        boolean isNew = existing.isEmpty();
        if (isNew) {
            studentId = studentDao.insert(new Student(null, code, row.fullName().trim(), row.gradeLevel().trim(),
                    section, false, email));
        } else {
            Student current = existing.get();
            studentId = current.studentId();
            studentDao.update(new Student(studentId, code, row.fullName().trim(), row.gradeLevel().trim(), section,
                    current.hasSpecialNeeds(), email));
        }

        Optional<Long> existingLogin = userDao.findUserIdByStudentId(studentId);
        if (existingLogin.isPresent()) {
            return new RowResult(rowNumber, code, Outcome.LOGIN_ALREADY_EXISTED,
                    isNew ? "Student created; login already existed" : "Student updated; login unchanged");
        }

        char[] initialPassword = code.toCharArray();
        try {
            String hash = hasher.hash(initialPassword);
            userDao.insert(new User(null, code, hash, row.fullName().trim(), studentRoleId, null, null, studentId,
                    true, null, null));
        } finally {
            PasswordHasher.wipe(initialPassword);
        }
        return new RowResult(rowNumber, code, Outcome.CREATED_WITH_LOGIN,
                isNew ? "Student and login created" : "Login created for existing student");
    }

    /** Best-effort: a mail failure never fails the import, it just gets logged. */
    private void emailCredentialsIfPossible(Row row, String normalizedCode) {
        if (blank(row.email()) || !emailService.isConfigured()) {
            return;
        }
        try {
            emailService.sendStudentCredentials(row.email().trim(), row.fullName().trim(), normalizedCode, normalizedCode);
        } catch (Exception e) {
            log.warn("Could not email login credentials to {} for student {}: {}", row.email().trim(), normalizedCode,
                    e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private <T> T run(TransactionManager.SqlWork<T> work) {
        try {
            return tx.inTransaction(work);
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }
}
