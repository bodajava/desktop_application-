package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.TeacherDao;
import com.examhalls.model.Teacher;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class TeacherDaoImpl extends JdbcSupport implements TeacherDao {

    private static final String SELECT = """
            SELECT t.teacher_id, t.full_name, t.teacher_code, t.dept_id, d.dept_name,
                   t.max_daily_load, t.max_weekly_load, t.hours_balance
            FROM   v_teachers t JOIN departments d ON d.dept_id = t.dept_id
            """;

    public TeacherDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<Teacher> findById(long id) {
        return queryOne(SELECT + " WHERE t.teacher_id = ?", ps -> ps.setLong(1, id), TeacherDaoImpl::map);
    }

    @Override
    public Optional<Teacher> findByCode(String teacherCode) {
        return queryOne(SELECT + " WHERE t.teacher_code = UPPER(TRIM(?))", ps -> ps.setString(1, teacherCode),
                TeacherDaoImpl::map);
    }

    @Override
    public List<Teacher> findAll() {
        return query(SELECT + " ORDER BY t.full_name", NO_PARAMS, TeacherDaoImpl::map);
    }

    @Override
    public List<Teacher> findByDepartment(long deptId) {
        return query(SELECT + " WHERE t.dept_id = ? ORDER BY t.full_name", ps -> ps.setLong(1, deptId),
                TeacherDaoImpl::map);
    }

    /** HOURS_BALANCE starts at 0 and is maintained only by pkg_supervision. */
    @Override
    public long insert(Teacher t) {
        return insert("""
                INSERT INTO teachers (full_name, teacher_code, dept_id, max_daily_load, max_weekly_load)
                VALUES (?, ?, ?, ?, ?)
                """, "TEACHER_ID", ps -> {
            ps.setString(1, t.fullName());
            ps.setString(2, t.teacherCode());
            ps.setLong(3, t.deptId());
            ps.setInt(4, t.maxDailyLoad());
            ps.setInt(5, t.maxWeeklyLoad());
        });
    }

    @Override
    public void update(Teacher t) {
        updateExactlyOne("""
                UPDATE teachers
                SET    full_name = ?, teacher_code = ?, dept_id = ?, max_daily_load = ?, max_weekly_load = ?
                WHERE  teacher_id = ? AND is_deleted = 'N'
                """, "Teacher", t.teacherId(), ps -> {
            ps.setString(1, t.fullName());
            ps.setString(2, t.teacherCode());
            ps.setLong(3, t.deptId());
            ps.setInt(4, t.maxDailyLoad());
            ps.setInt(5, t.maxWeeklyLoad());
            ps.setLong(6, t.teacherId());
        });
    }

    /** Soft delete via V_TEACHERS (refused with HAS_FUTURE_DUTIES if the teacher has upcoming duties). */
    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM v_teachers WHERE teacher_id = ?", "Teacher", id, ps -> ps.setLong(1, id));
    }

    private static Teacher map(ResultSet rs) throws SQLException {
        return new Teacher(rs.getLong("teacher_id"), rs.getString("full_name"), rs.getString("teacher_code"),
                rs.getLong("dept_id"), rs.getString("dept_name"), rs.getInt("max_daily_load"),
                rs.getInt("max_weekly_load"), getDecimal(rs, "hours_balance"));
    }
}
