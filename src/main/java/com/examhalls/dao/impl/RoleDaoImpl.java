package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.RoleDao;
import com.examhalls.model.Role;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class RoleDaoImpl extends JdbcSupport implements RoleDao {

    private static final String SELECT = "SELECT role_id, role_name, role_description FROM roles";

    public RoleDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<Role> findAll() {
        return query(SELECT + " ORDER BY role_id", NO_PARAMS, RoleDaoImpl::map);
    }

    @Override
    public Optional<Role> findByName(String roleName) {
        return queryOne(SELECT + " WHERE role_name = ?", ps -> ps.setString(1, roleName), RoleDaoImpl::map);
    }

    private static Role map(ResultSet rs) throws SQLException {
        return new Role(rs.getLong("role_id"), rs.getString("role_name"), rs.getString("role_description"));
    }
}
