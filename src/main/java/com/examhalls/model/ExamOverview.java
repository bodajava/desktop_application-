package com.examhalls.model;

/** One row of the exam schedule grid: the exam plus live seating/staffing counters. */
public record ExamOverview(ExamSchedule exam, int enrolled, int seated, int roomsUsed, int activeProctors) {

    public ExamStage stage() {
        if (seated == 0) {
            return ExamStage.NOT_SEATED;
        }
        return activeProctors == 0 ? ExamStage.SEATED : ExamStage.STAFFED;
    }
}
