package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.UserDao;
import com.examhalls.model.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class UserDaoImpl extends JdbcSupport implements UserDao {

    private static final String SELECT_NO_HASH = """
            SELECT u.user_id, u.username, NULL AS password_hash, u.full_name, u.role_id, r.role_name,
                   u.teacher_id, u.created_at, u.updated_at
            FROM   v_users u JOIN roles r ON r.role_id = u.role_id
            """;

    public UserDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<User> findById(long id) {
        return queryOne(SELECT_NO_HASH + " WHERE u.user_id = ?", ps -> ps.setLong(1, id), UserDaoImpl::map);
    }

    @Override
    public List<User> findAll() {
        return query(SELECT_NO_HASH + " ORDER BY u.username", NO_PARAMS, UserDaoImpl::map);
    }

    @Override
    public Optional<User> findByUsernameWithHash(String username) {
        String sql = """
                SELECT u.user_id, u.username, u.password_hash, u.full_name, u.role_id, r.role_name,
                       u.teacher_id, u.created_at, u.updated_at
                FROM   v_users u JOIN roles r ON r.role_id = u.role_id
                WHERE  LOWER(u.username) = LOWER(?)
                """;
        return queryOne(sql, ps -> ps.setString(1, username.trim()), UserDaoImpl::map);
    }

    @Override
    public long insert(User user) {
        if (user.passwordHash() == null) {
            throw new IllegalArgumentException("A password hash is required to create a user");
        }
        return insert("INSERT INTO users (username, password_hash, full_name, role_id, teacher_id) VALUES (?, ?, ?, ?, ?)",
                "USER_ID", ps -> {
                    ps.setString(1, user.username());
                    ps.setString(2, user.passwordHash());
                    ps.setString(3, user.fullName());
                    ps.setLong(4, user.roleId());
                    setNullableLong(ps, 5, user.teacherId());
                });
    }

    @Override
    public void update(User user) {
        updateExactlyOne("""
                UPDATE users SET username = ?, full_name = ?, role_id = ?, teacher_id = ?
                WHERE  user_id = ? AND is_deleted = 'N'
                """, "User", user.userId(), ps -> {
            ps.setString(1, user.username());
            ps.setString(2, user.fullName());
            ps.setLong(3, user.roleId());
            setNullableLong(ps, 4, user.teacherId());
            ps.setLong(5, user.userId());
        });
    }

    @Override
    public void updatePasswordHash(long userId, String newHash) {
        updateExactlyOne("UPDATE users SET password_hash = ? WHERE user_id = ? AND is_deleted = 'N'",
                "User", userId, ps -> {
                    ps.setString(1, newHash);
                    ps.setLong(2, userId);
                });
    }

    /** Soft delete via the INSTEAD OF trigger on V_USERS. */
    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM v_users WHERE user_id = ?", "User", id, ps -> ps.setLong(1, id));
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(rs.getLong("user_id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("full_name"), rs.getLong("role_id"), rs.getString("role_name"),
                getNullableLong(rs, "teacher_id"), getDateTime(rs, "created_at"), getDateTime(rs, "updated_at"));
    }
}
