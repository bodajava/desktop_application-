package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.SupervisionAuditDao;
import com.examhalls.model.SupervisionAudit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class SupervisionAuditDaoImpl extends JdbcSupport implements SupervisionAuditDao {

    private static final String SELECT = """
            SELECT a.audit_id, a.roster_id, r.exam_id, rm.room_code,
                   r.teacher_id AS replaced_id, t_old.full_name AS replaced_name,
                   a.substitute_teacher_id, t_new.full_name AS substitute_name,
                   a.executed_by, u.username AS executed_by_username, a.reason, a.audit_timestamp
            FROM   supervision_audit a
                   JOIN supervision_roster r ON r.roster_id      = a.roster_id
                   JOIN rooms             rm ON rm.room_id       = r.room_id
                   JOIN teachers       t_old ON t_old.teacher_id = r.teacher_id
                   JOIN teachers       t_new ON t_new.teacher_id = a.substitute_teacher_id
                   JOIN users              u ON u.user_id        = a.executed_by
            """;

    public SupervisionAuditDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<SupervisionAudit> findRecent(int limit) {
        return query(SELECT + " ORDER BY a.audit_timestamp DESC FETCH FIRST ? ROWS ONLY",
                ps -> ps.setInt(1, limit), SupervisionAuditDaoImpl::map);
    }

    @Override
    public List<SupervisionAudit> findByExam(long examId) {
        return query(SELECT + " WHERE r.exam_id = ? ORDER BY a.audit_timestamp DESC",
                ps -> ps.setLong(1, examId), SupervisionAuditDaoImpl::map);
    }

    private static SupervisionAudit map(ResultSet rs) throws SQLException {
        return new SupervisionAudit(rs.getLong("audit_id"), rs.getLong("roster_id"), rs.getLong("exam_id"),
                rs.getString("room_code"), rs.getLong("replaced_id"), rs.getString("replaced_name"),
                rs.getLong("substitute_teacher_id"), rs.getString("substitute_name"), rs.getLong("executed_by"),
                rs.getString("executed_by_username"), rs.getString("reason"), getDateTime(rs, "audit_timestamp"));
    }
}
