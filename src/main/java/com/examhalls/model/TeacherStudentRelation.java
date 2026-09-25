package com.examhalls.model;

/** Kinship (degree 1-4) between a teacher and a student: bars the teacher from that student's exam sessions. */
public record TeacherStudentRelation(Long relationId, long teacherId, long studentId, String studentCode,
                                     String studentName, String gradeLevel, int relationDegree, String notes) {
}
