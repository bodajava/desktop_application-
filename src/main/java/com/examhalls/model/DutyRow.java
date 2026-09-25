package com.examhalls.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One confirmed supervision duty with everything reports need (schedules, hours tally). */
public record DutyRow(long teacherId, String teacherCode, String teacherName, String deptName,
                      long examId, LocalDate examDate, String periodName, LocalDateTime slotStart,
                      LocalDateTime slotEnd, BigDecimal durationHours, String courseCode, String courseName,
                      String roomCode, SupervisionRole roleType) {
}
