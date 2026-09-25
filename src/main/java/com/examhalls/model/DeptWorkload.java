package com.examhalls.model;

import java.math.BigDecimal;

/** Accumulated supervision hours (HOURS_BALANCE) of a department's active teachers. */
public record DeptWorkload(String department, int teachers, BigDecimal totalHours, BigDecimal averageHours) {
}
