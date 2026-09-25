package com.examhalls.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An exam (EXAM_SCHEDULE joined through V_EXAM_SLOTS). For inserts/updates only
 * examDate, courseId, periodId and notes are used; the rest are read-only.
 */
public record ExamSchedule(Long examId, LocalDate examDate, Long courseId, String courseCode, String courseName,
                           String gradeLevel, Long periodId, String periodName,
                           LocalDateTime slotStart, LocalDateTime slotEnd, BigDecimal durationHours,
                           String notes) {

    public static ExamSchedule forInsert(LocalDate examDate, long courseId, long periodId, String notes) {
        return new ExamSchedule(null, examDate, courseId, null, null, null, periodId, null, null, null, null, notes);
    }
}
