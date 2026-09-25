package com.examhalls.model;

import java.time.LocalDate;

public record SupervisionRoster(Long rosterId, Long examId, LocalDate examDate, String periodName, String courseCode,
                                Long roomId, String roomCode, Long teacherId, String teacherCode,
                                String teacherName, SupervisionRole roleType, AssignmentStatus assignmentStatus) {
}
