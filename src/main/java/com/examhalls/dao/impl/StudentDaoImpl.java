package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.StudentDao;
import com.examhalls.model.Student;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class StudentDaoImpl extends JdbcSupport implements StudentDao {

    private static final String SELECT =
            "SELECT student_id, student_code, full_name, grade_level, section, has_special_needs FROM students";

    public StudentDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<Student> findById(long id) {
        return queryOne(SELECT + " WHERE student_id = ?", ps -> ps.setLong(1, id), StudentDaoImpl::map);
    }

    @Override
    public Optional<Student> findByCode(String studentCode) {
        return queryOne(SELECT + " WHERE student_code = ?", ps -> ps.setString(1, studentCode), StudentDaoImpl::map);
    }

    @Override
    public List<Student> findAll() {
        return query(SELECT + " ORDER BY grade_level, student_code", NO_PARAMS, StudentDaoImpl::map);
    }

    @Override
    public List<Student> findByGradeLevel(String gradeLevel) {
        return query(SELECT + " WHERE grade_level = ? ORDER BY student_code", ps -> ps.setString(1, gradeLevel),
                StudentDaoImpl::map);
    }

    @Override
    public long insert(Student s) {
        return insert("""
                INSERT INTO students (student_code, full_name, grade_level, section, has_special_needs)
                VALUES (?, ?, ?, ?, ?)
                """, "STUDENT_ID", ps -> {
            ps.setString(1, s.studentCode());
            ps.setString(2, s.fullName());
            ps.setString(3, s.gradeLevel());
            ps.setString(4, s.section());
            ps.setString(5, flag(s.hasSpecialNeeds()));
        });
    }

    @Override
    public void update(Student s) {
        updateExactlyOne("""
                UPDATE students SET student_code = ?, full_name = ?, grade_level = ?, section = ?, has_special_needs = ?
                WHERE  student_id = ?
                """, "Student", s.studentId(), ps -> {
            ps.setString(1, s.studentCode());
            ps.setString(2, s.fullName());
            ps.setString(3, s.gradeLevel());
            ps.setString(4, s.section());
            ps.setString(5, flag(s.hasSpecialNeeds()));
            ps.setLong(6, s.studentId());
        });
    }

    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM students WHERE student_id = ?", "Student", id, ps -> ps.setLong(1, id));
    }

    private static Student map(ResultSet rs) throws SQLException {
        return new Student(rs.getLong("student_id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getString("grade_level"), rs.getString("section"), getFlag(rs, "has_special_needs"));
    }
}
