package com.examhalls.model;

/** How far an exam's proctoring has progressed, judged across every room it uses. */
public enum AllocationState {
    /** No students seated yet, so no positions exist. */
    NOT_SEATED,
    /** Seated, but nobody is on duty in any of its rooms. */
    UNASSIGNED,
    /** Some rooms or positions are still open. */
    PARTIAL,
    /** Every room has its head of committee and all required proctors. */
    FULL
}
