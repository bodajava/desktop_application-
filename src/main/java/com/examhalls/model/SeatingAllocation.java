package com.examhalls.model;

public record SeatingAllocation(Long seatingId, Long examId, Long roomId, String roomCode,
                                Long studentId, String studentCode, String studentName, boolean hasSpecialNeeds,
                                String seatNumber, AttendanceStatus attendanceStatus) {
}
