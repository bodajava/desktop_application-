package com.examhalls.model;

/**
 * An exam on the dashboard's focus day. {@code filled}/{@code required} count supervision
 * positions (one head of committee plus the required proctors) in the rooms the exam uses.
 */
public record FocusExamRow(ExamSchedule exam, int enrolled, int seated, int rooms, int filled, int required,
                           AllocationState state) {
}
