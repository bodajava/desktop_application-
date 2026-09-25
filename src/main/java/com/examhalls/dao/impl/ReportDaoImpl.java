package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.ReportDao;
import com.examhalls.model.AssignmentStatus;
import com.examhalls.model.DeptWorkload;
import com.examhalls.model.DutyRow;
import com.examhalls.model.RoomSeatCount;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SubstitutionFeedItem;
import com.examhalls.model.SupervisionRoster;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class ReportDaoImpl extends JdbcSupport implements ReportDao {

    public ReportDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<DutyRow> findDuties(LocalDate from, LocalDate to, Long teacherId) {
        // Base TEACHERS (not V_TEACHERS): archived teachers still get paid for work they did.
        String sql = """
                SELECT t.teacher_id, t.teacher_code, t.full_name, d.dept_name, s.exam_id, s.exam_date, s.period_name,
                       s.slot_start, s.slot_end, s.duration_hours, s.course_code, s.course_name, rm.room_code, r.role_type
                FROM   supervision_roster r
                       JOIN v_exam_slots s ON s.exam_id    = r.exam_id
                       JOIN teachers     t ON t.teacher_id = r.teacher_id
                       JOIN departments  d ON d.dept_id    = t.dept_id
                       JOIN rooms       rm ON rm.room_id   = r.room_id
                WHERE  r.assignment_status = 'CONFIRMED'
                AND    s.exam_date BETWEEN ? AND ?
                AND    (? IS NULL OR r.teacher_id = ?)
                ORDER  BY t.full_name, s.slot_start, rm.room_code
                """;
        return query(sql, ps -> {
            setDate(ps, 1, from);
            setDate(ps, 2, to);
            setNullableLong(ps, 3, teacherId);
            setNullableLong(ps, 4, teacherId);
        }, rs -> new DutyRow(rs.getLong("teacher_id"), rs.getString("teacher_code"), rs.getString("full_name"),
                rs.getString("dept_name"), rs.getLong("exam_id"), getLocalDate(rs, "exam_date"),
                rs.getString("period_name"), getDateTime(rs, "slot_start"), getDateTime(rs, "slot_end"),
                getDecimal(rs, "duration_hours"), rs.getString("course_code"), rs.getString("course_name"),
                rs.getString("room_code"), SupervisionRole.valueOf(rs.getString("role_type"))));
    }

    @Override
    public List<RoomSeatCount> seatCountsBetween(LocalDate from, LocalDate to) {
        return query("""
                SELECT sa.exam_id, sa.room_id, COUNT(*) AS seated
                FROM   seating_allocation sa JOIN exam_schedule e ON e.exam_id = sa.exam_id
                WHERE  e.exam_date BETWEEN ? AND ?
                GROUP  BY sa.exam_id, sa.room_id
                """, ps -> {
                    setDate(ps, 1, from);
                    setDate(ps, 2, to);
                },
                rs -> new RoomSeatCount(rs.getLong("exam_id"), rs.getLong("room_id"), rs.getInt("seated")));
    }

    @Override
    public List<SupervisionRoster> activeRosterBetween(LocalDate from, LocalDate to) {
        return query("""
                SELECT r.roster_id, r.exam_id, s.exam_date, s.period_name, s.course_code, r.room_id, rm.room_code,
                       r.teacher_id, t.teacher_code, t.full_name AS teacher_name, r.role_type
                FROM   supervision_roster r
                       JOIN v_exam_slots s ON s.exam_id    = r.exam_id
                       JOIN rooms       rm ON rm.room_id   = r.room_id
                       JOIN teachers     t ON t.teacher_id = r.teacher_id
                WHERE  r.assignment_status = 'CONFIRMED' AND s.exam_date BETWEEN ? AND ?
                ORDER  BY s.exam_date, rm.room_code, CASE r.role_type WHEN 'HEAD_OF_COMMITTEE' THEN 0 ELSE 1 END,
                          t.full_name
                """, ps -> {
                    setDate(ps, 1, from);
                    setDate(ps, 2, to);
                },
                rs -> new SupervisionRoster(rs.getLong("roster_id"), rs.getLong("exam_id"), getLocalDate(rs, "exam_date"),
                        rs.getString("period_name"), rs.getString("course_code"), rs.getLong("room_id"),
                        rs.getString("room_code"), rs.getLong("teacher_id"), rs.getString("teacher_code"),
                        rs.getString("teacher_name"), SupervisionRole.valueOf(rs.getString("role_type")),
                        AssignmentStatus.CONFIRMED));
    }

    @Override
    public List<DeptWorkload> departmentWorkload() {
        return query("""
                SELECT d.dept_name, COUNT(t.teacher_id) AS teachers,
                       NVL(SUM(t.hours_balance), 0) AS total_hours, NVL(AVG(t.hours_balance), 0) AS avg_hours
                FROM   departments d JOIN v_teachers t ON t.dept_id = d.dept_id
                GROUP  BY d.dept_name
                ORDER  BY avg_hours DESC, d.dept_name
                """, ps -> { },
                rs -> new DeptWorkload(rs.getString("dept_name"), rs.getInt("teachers"),
                        getDecimal(rs, "total_hours"), getDecimal(rs, "avg_hours")));
    }

    @Override
    public List<SubstitutionFeedItem> recentSubstitutions(int limit) {
        return query("""
                SELECT a.audit_timestamp, s.exam_date, s.period_name, s.course_code, rm.room_code,
                       t_old.full_name AS replaced_name, t_new.full_name AS substitute_name,
                       u.username AS executed_by, a.reason
                FROM   supervision_audit a
                       JOIN supervision_roster r ON r.roster_id      = a.roster_id
                       JOIN v_exam_slots       s ON s.exam_id        = r.exam_id
                       JOIN rooms             rm ON rm.room_id       = r.room_id
                       JOIN teachers       t_old ON t_old.teacher_id = r.teacher_id
                       JOIN teachers       t_new ON t_new.teacher_id = a.substitute_teacher_id
                       JOIN users              u ON u.user_id        = a.executed_by
                ORDER  BY a.audit_timestamp DESC, a.audit_id DESC
                FETCH  FIRST ? ROWS ONLY
                """, ps -> ps.setInt(1, limit),
                rs -> new SubstitutionFeedItem(getDateTime(rs, "audit_timestamp"), getLocalDate(rs, "exam_date"),
                        rs.getString("period_name"), rs.getString("course_code"), rs.getString("room_code"),
                        rs.getString("replaced_name"), rs.getString("substitute_name"),
                        rs.getString("executed_by"), rs.getString("reason")));
    }

    @Override
    public Optional<LocalDate> nextExamDate(LocalDate from) {
        return queryOne("SELECT MIN(exam_date) AS d FROM exam_schedule WHERE exam_date >= ?",
                ps -> setDate(ps, 1, from), rs -> getLocalDate(rs, "d"));
    }
}
