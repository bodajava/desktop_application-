package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.RelationDao;
import com.examhalls.model.TeacherStudentRelation;

import java.util.List;

public class RelationDaoImpl extends JdbcSupport implements RelationDao {

    public RelationDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<TeacherStudentRelation> findByTeacher(long teacherId) {
        return query("""
                SELECT r.relation_id, r.teacher_id, r.student_id, s.student_code, s.full_name, s.grade_level,
                       r.relation_degree, r.notes
                FROM   teacher_student_relations r JOIN students s ON s.student_id = r.student_id
                WHERE  r.teacher_id = ?
                ORDER  BY r.relation_degree, s.full_name
                """, ps -> ps.setLong(1, teacherId),
                rs -> new TeacherStudentRelation(rs.getLong("relation_id"), rs.getLong("teacher_id"),
                        rs.getLong("student_id"), rs.getString("student_code"), rs.getString("full_name"),
                        rs.getString("grade_level"), rs.getInt("relation_degree"), rs.getString("notes")));
    }

    @Override
    public long insert(TeacherStudentRelation r) {
        return insert("""
                INSERT INTO teacher_student_relations (teacher_id, student_id, relation_degree, notes) VALUES (?, ?, ?, ?)
                """, "RELATION_ID", ps -> {
            ps.setLong(1, r.teacherId());
            ps.setLong(2, r.studentId());
            ps.setInt(3, r.relationDegree());
            ps.setString(4, r.notes());
        });
    }

    @Override
    public void delete(long relationId) {
        updateExactlyOne("DELETE FROM teacher_student_relations WHERE relation_id = ?", "Relation", relationId,
                ps -> ps.setLong(1, relationId));
    }

    @Override
    public int countConflictingDuties(long teacherId, long studentId) {
        return queryOne("""
                SELECT COUNT(*)
                FROM   supervision_roster r
                       JOIN v_exam_slots duty ON duty.exam_id = r.exam_id
                WHERE  r.teacher_id = ? AND r.assignment_status = 'CONFIRMED' AND duty.exam_date >= TRUNC(SYSDATE)
                AND    EXISTS (SELECT 1
                               FROM   seating_allocation sa JOIN v_exam_slots s ON s.exam_id = sa.exam_id
                               WHERE  sa.student_id = ?
                               AND    s.slot_start < duty.slot_end AND duty.slot_start < s.slot_end)
                """, ps -> {
            ps.setLong(1, teacherId);
            ps.setLong(2, studentId);
        }, rs -> rs.getInt(1)).orElse(0);
    }
}
