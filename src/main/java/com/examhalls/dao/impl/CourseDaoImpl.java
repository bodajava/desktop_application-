package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.CourseDao;
import com.examhalls.model.Course;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class CourseDaoImpl extends JdbcSupport implements CourseDao {

    private static final String SELECT = """
            SELECT c.course_id, c.course_name, c.course_code, c.dept_id, d.dept_name, c.grade_level
            FROM   courses c JOIN departments d ON d.dept_id = c.dept_id
            """;

    public CourseDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<Course> findById(long id) {
        return queryOne(SELECT + " WHERE c.course_id = ?", ps -> ps.setLong(1, id), CourseDaoImpl::map);
    }

    @Override
    public Optional<Course> findByCode(String courseCode) {
        return queryOne(SELECT + " WHERE c.course_code = ?", ps -> ps.setString(1, courseCode), CourseDaoImpl::map);
    }

    @Override
    public List<Course> findAll() {
        return query(SELECT + " ORDER BY c.grade_level, c.course_code", NO_PARAMS, CourseDaoImpl::map);
    }

    @Override
    public List<Course> findByGradeLevel(String gradeLevel) {
        return query(SELECT + " WHERE c.grade_level = ? ORDER BY c.course_code", ps -> ps.setString(1, gradeLevel),
                CourseDaoImpl::map);
    }

    @Override
    public long insert(Course c) {
        return insert("INSERT INTO courses (course_name, course_code, dept_id, grade_level) VALUES (?, ?, ?, ?)",
                "COURSE_ID", ps -> {
                    ps.setString(1, c.courseName());
                    ps.setString(2, c.courseCode());
                    ps.setLong(3, c.deptId());
                    ps.setString(4, c.gradeLevel());
                });
    }

    @Override
    public void update(Course c) {
        updateExactlyOne("""
                UPDATE courses SET course_name = ?, course_code = ?, dept_id = ?, grade_level = ?
                WHERE  course_id = ?
                """, "Course", c.courseId(), ps -> {
            ps.setString(1, c.courseName());
            ps.setString(2, c.courseCode());
            ps.setLong(3, c.deptId());
            ps.setString(4, c.gradeLevel());
            ps.setLong(5, c.courseId());
        });
    }

    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM courses WHERE course_id = ?", "Course", id, ps -> ps.setLong(1, id));
    }

    private static Course map(ResultSet rs) throws SQLException {
        return new Course(rs.getLong("course_id"), rs.getString("course_name"), rs.getString("course_code"),
                rs.getLong("dept_id"), rs.getString("dept_name"), rs.getString("grade_level"));
    }
}
