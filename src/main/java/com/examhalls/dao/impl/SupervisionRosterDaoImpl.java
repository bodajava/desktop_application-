package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.SupervisionRosterDao;
import com.examhalls.model.AssignmentStatus;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SupervisionRoster;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class SupervisionRosterDaoImpl extends JdbcSupport implements SupervisionRosterDao {

    // Base tables (not V_SUPERVISION_ROSTER) so REPLACED/CANCELLED rows are visible by id.
    private static final String SELECT = """
            SELECT r.roster_id, r.exam_id, s.exam_date, s.period_name, s.course_code, r.room_id, rm.room_code,
                   r.teacher_id, t.teacher_code, t.full_name AS teacher_name, r.role_type, r.assignment_status
            FROM   supervision_roster r
                   JOIN v_exam_slots s ON s.exam_id    = r.exam_id
                   JOIN rooms       rm ON rm.room_id   = r.room_id
                   JOIN teachers     t ON t.teacher_id = r.teacher_id
            """;

    public SupervisionRosterDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public Optional<SupervisionRoster> findById(long rosterId) {
        return queryOne(SELECT + " WHERE r.roster_id = ?", ps -> ps.setLong(1, rosterId),
                SupervisionRosterDaoImpl::map);
    }

    @Override
    public List<SupervisionRoster> findActiveByExam(long examId) {
        return query(SELECT + """
                 WHERE r.exam_id = ? AND r.assignment_status = 'CONFIRMED'
                 ORDER BY rm.room_code, CASE r.role_type WHEN 'HEAD_OF_COMMITTEE' THEN 0 ELSE 1 END, t.full_name
                """, ps -> ps.setLong(1, examId), SupervisionRosterDaoImpl::map);
    }

    @Override
    public List<SupervisionRoster> findActiveForExamRooms(long examId) {
        return query(SELECT + """
                 JOIN v_exam_slots target ON target.exam_id = ?
                 WHERE r.assignment_status = 'CONFIRMED'
                 AND   r.room_id IN (SELECT sa.room_id FROM seating_allocation sa WHERE sa.exam_id = target.exam_id)
                 AND   s.slot_start < target.slot_end AND target.slot_start < s.slot_end
                 ORDER BY rm.room_code, CASE r.role_type WHEN 'HEAD_OF_COMMITTEE' THEN 0 ELSE 1 END, t.full_name
                """, ps -> ps.setLong(1, examId), SupervisionRosterDaoImpl::map);
    }

    @Override
    public List<SupervisionRoster> findUpcomingByTeacher(long teacherId, LocalDate from) {
        return query(SELECT + """
                 WHERE r.teacher_id = ? AND r.assignment_status = 'CONFIRMED' AND s.exam_date >= ?
                 ORDER BY s.slot_start
                """, ps -> {
            ps.setLong(1, teacherId);
            setDate(ps, 2, from);
        }, SupervisionRosterDaoImpl::map);
    }

    private static SupervisionRoster map(ResultSet rs) throws SQLException {
        return new SupervisionRoster(rs.getLong("roster_id"), rs.getLong("exam_id"), getLocalDate(rs, "exam_date"),
                rs.getString("period_name"), rs.getString("course_code"), rs.getLong("room_id"),
                rs.getString("room_code"), rs.getLong("teacher_id"), rs.getString("teacher_code"),
                rs.getString("teacher_name"), SupervisionRole.valueOf(rs.getString("role_type")),
                AssignmentStatus.valueOf(rs.getString("assignment_status")));
    }
}
