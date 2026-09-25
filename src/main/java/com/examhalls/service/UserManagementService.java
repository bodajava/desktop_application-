package com.examhalls.service;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.RoleDao;
import com.examhalls.dao.TeacherDao;
import com.examhalls.dao.UserDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.exception.OracleErrorTranslator;
import com.examhalls.model.Role;
import com.examhalls.model.Teacher;
import com.examhalls.model.User;
import com.examhalls.model.UserAccount;
import com.examhalls.model.UserForm;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.CredentialPolicy;
import com.examhalls.security.PasswordHasher;
import com.examhalls.security.Permission;
import com.examhalls.security.RoleType;
import com.examhalls.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Screens 1 & 2 — user accounts and role assignment (School Admin only). Deletion is a soft
 * delete through V_USERS. Guards: no self-delete, and at least one active School Admin remains.
 */
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final TransactionManager tx;
    private final UserSession session;
    private final UserDao userDao;
    private final RoleDao roleDao;
    private final TeacherDao teacherDao;
    private final PasswordHasher hasher;

    public UserManagementService(TransactionManager tx, UserSession session, UserDao userDao, RoleDao roleDao,
                                 TeacherDao teacherDao, PasswordHasher hasher) {
        this.tx = tx;
        this.session = session;
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.teacherDao = teacherDao;
        this.hasher = hasher;
    }

    public List<UserAccount> listUsers() {
        session.require(Permission.MANAGE_USERS);
        Map<Long, Teacher> teachers = teacherDao.findAll().stream()
                .collect(Collectors.toMap(Teacher::teacherId, Function.identity()));
        List<UserAccount> list = new ArrayList<>();
        for (User u : userDao.findAll()) {
            Teacher t = u.teacherId() == null ? null : teachers.get(u.teacherId());
            String label = u.teacherId() == null ? null
                    : t == null ? "#" + u.teacherId() : t.fullName() + " (" + t.teacherCode() + ")";
            list.add(new UserAccount(u.userId(), u.username(), u.fullName(), RoleType.fromDb(u.roleName()),
                    u.teacherId(), label, u.createdAt()));
        }
        return list;
    }

    /** Active teachers that can be linked to an account. */
    public List<Teacher> linkableTeachers() {
        session.require(Permission.MANAGE_USERS);
        return teacherDao.findAll();
    }

    /** @return the new user id. The password array is wiped. */
    public long createUser(UserForm form, char[] password) {
        AuthenticatedUser me = session.require(Permission.MANAGE_USERS);
        try {
            validate(form);
            requireStrong(password);
            long id = run(() -> userDao.insert(new User(null, form.username().strip(), hasher.hash(password),
                    form.fullName().trim(), roleId(form.role()), null, form.teacherId(), null, null)));
            log.info("{} created user '{}' ({})", me.username(), form.username(), form.role());
            return id;
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    public void updateUser(long userId, UserForm form) {
        AuthenticatedUser me = session.require(Permission.MANAGE_USERS);
        validate(form);
        run(() -> {
            User current = userDao.findById(userId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User " + userId));
            if (RoleType.fromDb(current.roleName()) == RoleType.SCHOOL_ADMIN && form.role() != RoleType.SCHOOL_ADMIN) {
                requireAnotherAdmin(userId);
            }
            userDao.update(new User(userId, form.username().strip(), null, form.fullName().trim(), roleId(form.role()),
                    null, form.teacherId(), null, null));
            return null;
        });
        log.info("{} updated user #{} -> {} ({})", me.username(), userId, form.username(), form.role());
    }

    /** Admin reset of another user's password. The array is wiped. */
    public void resetPassword(long userId, char[] newPassword) {
        AuthenticatedUser me = session.require(Permission.MANAGE_USERS);
        try {
            requireStrong(newPassword);
            userDao.updatePasswordHash(userId, hasher.hash(newPassword));
            log.info("{} reset the password of user #{}", me.username(), userId);
        } finally {
            PasswordHasher.wipe(newPassword);
        }
    }

    /** Soft delete (V_USERS). */
    public void deleteUser(long userId) {
        AuthenticatedUser me = session.require(Permission.MANAGE_USERS);
        if (me.userId() == userId) {
            throw new AppException(ErrorCode.CANNOT_DELETE_SELF);
        }
        run(() -> {
            User target = userDao.findById(userId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User " + userId));
            if (RoleType.fromDb(target.roleName()) == RoleType.SCHOOL_ADMIN) {
                requireAnotherAdmin(userId);
            }
            userDao.delete(userId);
            return null;
        });
        log.info("{} deleted (archived) user #{}", me.username(), userId);
    }

    // ------------------------------------------------------------------ rules

    private void validate(UserForm f) {
        List<String> bad = new ArrayList<>();
        if (f.username() == null || !CredentialPolicy.isValidUsername(f.username().strip())) {
            bad.add("username");
        }
        if (f.fullName() == null || f.fullName().isBlank() || f.fullName().trim().length() > 150) {
            bad.add("full name");
        }
        if (f.role() == null) {
            bad.add("role");
        }
        if (!bad.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.messageKey(),
                    String.join(", ", bad), null, String.join(", ", bad));
        }
        if (f.role() == RoleType.TEACHER && f.teacherId() == null) {
            throw new AppException(ErrorCode.TEACHER_LINK_REQUIRED);
        }
    }

    private static void requireStrong(char[] password) {
        if (!PasswordHasher.isStrongEnough(password)) {
            throw new AppException(ErrorCode.WEAK_PASSWORD);
        }
    }

    private void requireAnotherAdmin(long excludingUserId) {
        long others = userDao.findAll().stream()
                .filter(u -> u.userId() != excludingUserId && RoleType.fromDb(u.roleName()) == RoleType.SCHOOL_ADMIN)
                .count();
        if (others == 0) {
            throw new AppException(ErrorCode.LAST_ADMIN);
        }
    }

    private long roleId(RoleType role) {
        return roleDao.findByName(role.name()).map(Role::roleId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Role " + role + " missing in ROLES"));
    }

    private <T> T run(TransactionManager.SqlWork<T> work) {
        try {
            return tx.inTransaction(work);
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }
}
