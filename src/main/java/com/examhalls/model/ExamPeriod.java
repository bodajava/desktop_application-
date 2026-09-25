package com.examhalls.model;

import java.time.LocalTime;

public record ExamPeriod(Long periodId, String periodName, LocalTime startTime, LocalTime endTime) {
}
