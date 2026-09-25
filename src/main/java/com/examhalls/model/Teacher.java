package com.examhalls.model;

import java.math.BigDecimal;

/** Active teacher (V_TEACHERS). {@code deptName} is filled on reads. */
public record Teacher(Long teacherId, String fullName, String teacherCode, Long deptId, String deptName,
                      int maxDailyLoad, int maxWeeklyLoad, BigDecimal hoursBalance) {
}
