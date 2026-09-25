package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.SeatingAllocationDao;
import com.examhalls.model.AttendanceStatus;
import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.StudentExamRow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public class SeatingAllocationDaoImpl extends JdbcSupport implements SeatingAllocationDao {

    private static final String SELECT = """
            SELECT sa.seating_id, sa.exam_id, sa.room_id, r.room_code, sa.student_id, st.student_code,
                   st.full_name AS student_name, st.has_special_needs, sa.seat_number, sa.attendance_status
            FROM   seating_allocation sa
                   JOIN rooms    r  ON r.room_id     = sa.room_id
                   JOIN students st ON st.student_id = sa.student_id
            """;

    public SeatingAllocationDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<SeatingAllocation> findByExam(long examId) {
        return query(SELECT + " WHERE sa.exam_id = ? ORDER BY r.room_code, sa.seat_number",
                ps -> ps.setLong(1, examId), SeatingAllocationDaoImpl::map);
    }

    @Override
    public List<SeatingAllocation> findByExamAndRoom(long examId, long roomId) {
        return query(SELECT + " WHERE sa.exam_id = ? AND sa.room_id = ? ORDER BY sa.seat_number", ps -> {
            ps.setLong(1, examId);
            ps.setLong(2, roomId);
        }, SeatingAllocationDaoImpl::map);
    }

    @Override
    public int countByExam(long examId) {
        return queryOne("SELECT COUNT(*) FROM seating_allocation WHERE exam_id = ?", ps -> ps.setLong(1, examId),
                rs -> rs.getInt(1)).orElse(0);
    }

    private static SeatingAllocation map(ResultSet rs) throws SQLException {
        return new SeatingAllocation(rs.getLong("seating_id"), rs.getLong("exam_id"), rs.getLong("room_id"),
                rs.getString("room_code"), rs.getLong("student_id"), rs.getString("student_code"),
                rs.getString("student_name"), getFlag(rs, "has_special_needs"), rs.getString("seat_number"),
                AttendanceStatus.valueOf(rs.getString("attendance_status")));
    }

    private static final String STUDENT_EXAMS_SELECT = """
            SELECT seating_id, exam_id, exam_date, period_name, course_code, course_name, room_code, building,
                   seat_number, attendance_status
            FROM   v_student_exams
            """;

    @Override
    public List<StudentExamRow> findUpcomingByStudent(long studentId, LocalDate from) {
        return query(STUDENT_EXAMS_SELECT + " WHERE student_id = ? AND exam_date >= ? ORDER BY exam_date", ps -> {
            ps.setLong(1, studentId);
            setDate(ps, 2, from);
        }, SeatingAllocationDaoImpl::mapStudentExam);
    }

    private static StudentExamRow mapStudentExam(ResultSet rs) throws SQLException {
        return new StudentExamRow(rs.getLong("seating_id"), rs.getLong("exam_id"), getLocalDate(rs, "exam_date"),
                rs.getString("period_name"), rs.getString("course_code"), rs.getString("course_name"),
                rs.getString("room_code"), rs.getString("building"), rs.getString("seat_number"),
                AttendanceStatus.valueOf(rs.getString("attendance_status")));
    }
}
