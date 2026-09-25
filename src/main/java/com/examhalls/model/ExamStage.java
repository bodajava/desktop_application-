package com.examhalls.model;

/** Operational progress of an exam, derived from its seating and roster. */
public enum ExamStage {
    NOT_SEATED,     // no seating yet
    SEATED,         // seated, no proctors
    STAFFED         // seated and proctors allocated
}
