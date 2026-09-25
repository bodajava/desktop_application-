package com.examhalls.model;

import java.math.BigDecimal;

/** Aggregated supervision for one teacher over a period (compensation workbook). */
public record TeacherHours(long teacherId, String teacherCode, String teacherName, String deptName,
                           int headSessions, int proctorSessions, BigDecimal totalHours) {

    public int totalSessions() {
        return headSessions + proctorSessions;
    }
}
