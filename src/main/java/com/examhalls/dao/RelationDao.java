package com.examhalls.dao;

import com.examhalls.model.TeacherStudentRelation;

import java.util.List;

public interface RelationDao {
    List<TeacherStudentRelation> findByTeacher(long teacherId);

    long insert(TeacherStudentRelation relation);

    void delete(long relationId);

    /**
     * Upcoming CONFIRMED duties of the teacher that overlap an exam the student is seated in —
     * i.e. assignments that now violate the session-wide relatives rule.
     */
    int countConflictingDuties(long teacherId, long studentId);
}
