package com.examhalls.model;

import java.time.LocalDate;

/** One exam on a student's own schedule, with their seat and attendance for it. */
public record StudentExamRow(Long seatingId, Long examId, LocalDate examDate, String periodName, String courseCode,
                             String courseName, String roomCode, String building, String seatNumber,
                             AttendanceStatus attendanceStatus) {
}
