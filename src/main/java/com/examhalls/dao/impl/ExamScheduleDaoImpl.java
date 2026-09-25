package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamSchedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class ExamScheduleDaoImpl extends JdbcSupport implements ExamScheduleDao {

    private static final String SELECT = """
            SELECT s.exam_id, s.exam_date, s.course_id, s.course_code, s.course_name, s.grade_level,
                   s.period_id, s.period_name, s.slot_start, s.slot_end, s.duration_hours, e.notes
            FROM   v_exam_slots s JOIN exam_schedule e ON e.exam_id = s.exam_id
            """;

    public ExamScheduleDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<ExamSchedule> findById(long id) {
        return queryOne(SELECT + " WHERE s.exam_id = ?", ps -> ps.setLong(1, id), ExamScheduleDaoImpl::map);
    }

    @Override
    public List<ExamSchedule> findAll() {
        return query(SELECT + " ORDER BY s.slot_start, s.course_code", NO_PARAMS, ExamScheduleDaoImpl::map);
    }

    @Override
    public List<ExamSchedule> findBetween(LocalDate from, LocalDate to) {
        return query(SELECT + " WHERE s.exam_date BETWEEN ? AND ? ORDER BY s.slot_start, s.course_code", ps -> {
            setDate(ps, 1, from);
            setDate(ps, 2, to);
        }, ExamScheduleDaoImpl::map);
    }

    @Override
    public List<ExamOverview> findOverview(LocalDate from, LocalDate to) {
        String sql = """
                SELECT x.*,
                       (SELECT COUNT(*) FROM students st WHERE st.grade_level = x.grade_level)            AS enrolled,
                       (SELECT COUNT(*) FROM seating_allocation sa WHERE sa.exam_id = x.exam_id)         AS seated,
                       (SELECT COUNT(DISTINCT sa.room_id) FROM seating_allocation sa
                        WHERE  sa.exam_id = x.exam_id)                                                    AS rooms_used,
                       (SELECT COUNT(*) FROM supervision_roster r
                        WHERE  r.exam_id = x.exam_id AND r.assignment_status = 'CONFIRMED')               AS proctors
                FROM (""" + SELECT + """
                      WHERE s.exam_date BETWEEN ? AND ?) x
                ORDER BY x.slot_start, x.course_code
                """;
        return query(sql, ps -> {
            setDate(ps, 1, from);
            setDate(ps, 2, to);
        }, rs -> new ExamOverview(map(rs), rs.getInt("enrolled"), rs.getInt("seated"), rs.getInt("rooms_used"),
                rs.getInt("proctors")));
    }

    @Override
    public long insert(ExamSchedule e) {
        return insert("INSERT INTO exam_schedule (exam_date, course_id, period_id, notes) VALUES (?, ?, ?, ?)",
                "EXAM_ID", ps -> {
                    setDate(ps, 1, e.examDate());
                    ps.setLong(2, e.courseId());
                    ps.setLong(3, e.periodId());
                    ps.setString(4, e.notes());
                });
    }

    @Override
    public void update(ExamSchedule e) {
        updateExactlyOne("""
                UPDATE exam_schedule SET exam_date = ?, course_id = ?, period_id = ?, notes = ? WHERE exam_id = ?
                """, "Exam", e.examId(), ps -> {
            setDate(ps, 1, e.examDate());
            ps.setLong(2, e.courseId());
            ps.setLong(3, e.periodId());
            ps.setString(4, e.notes());
            ps.setLong(5, e.examId());
        });
    }

    /** Seating is removed by ON DELETE CASCADE; an exam with supervision history cannot be deleted. */
    @Override
    public void delete(long id) {
        updateExactlyOne("DELETE FROM exam_schedule WHERE exam_id = ?", "Exam", id, ps -> ps.setLong(1, id));
    }

    private static ExamSchedule map(ResultSet rs) throws SQLException {
        return new ExamSchedule(rs.getLong("exam_id"), getLocalDate(rs, "exam_date"), rs.getLong("course_id"),
                rs.getString("course_code"), rs.getString("course_name"), rs.getString("grade_level"),
                rs.getLong("period_id"), rs.getString("period_name"), getDateTime(rs, "slot_start"),
                getDateTime(rs, "slot_end"), getDecimal(rs, "duration_hours"), rs.getString("notes"));
    }
}
