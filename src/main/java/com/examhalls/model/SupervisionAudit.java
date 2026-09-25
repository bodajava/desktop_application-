package com.examhalls.model;

import java.time.LocalDateTime;

/** One substitution: {@code replacedTeacher*} comes from the original (REPLACED) roster row. */
public record SupervisionAudit(Long auditId, Long rosterId, Long examId, String roomCode,
                               Long replacedTeacherId, String replacedTeacherName,
                               Long substituteTeacherId, String substituteTeacherName,
                               Long executedBy, String executedByUsername,
                               String reason, LocalDateTime auditTimestamp) {
}
