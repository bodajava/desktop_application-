package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.DepartmentDao;
import com.examhalls.model.Department;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class DepartmentDaoImpl extends JdbcSupport implements DepartmentDao {

    public DepartmentDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<Department> findById(long id) {
        return queryOne("SELECT dept_id, dept_name FROM departments WHERE dept_id = ?",
                ps -> ps.setLong(1, id), DepartmentDaoImpl::map);
    }

    @Override
    public List<Department> findAll() {
        return query("SELECT dept_id, dept_name FROM departments ORDER BY dept_name", NO_PARAMS, DepartmentDaoImpl::map);
    }

    @Override
    public long insert(Department d) {
        return insert("INSERT INTO departments (dept_name) VALUES (?)", "DEPT_ID", ps -> ps.setString(1, d.deptName()));
    }

    @Override
    public void update(Department d) {
        updateExactlyOne("UPDATE departments SET dept_name = ? WHERE dept_id = ?", "Department", d.deptId(), ps -> {
            ps.setString(1, d.deptName());
            ps.setLong(2, d.deptId());
        });
    }

    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM departments WHERE dept_id = ?", "Department", id, ps -> ps.setLong(1, id));
    }

    private static Department map(ResultSet rs) throws SQLException {
        return new Department(rs.getLong("dept_id"), rs.getString("dept_name"));
    }
}
