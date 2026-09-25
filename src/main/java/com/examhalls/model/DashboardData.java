package com.examhalls.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the executive dashboard shows, computed in one pass for all exams from
 * {@code today} onwards.
 *
 * <p>Staffing is measured per room session (room + exam day + period), the same unit the
 * allocation engine and the occupancy grid use: exams sharing a room also share its staff.
 *
 * @param focusDay            today if it has exams, otherwise the next exam day (null when none)
 * @param hallCapacity        sum of {@link DayCapacity#capacity()} over the exam days
 * @param requiredPositions   head of committee + required proctors, over every seated room session
 * @param unstaffedRooms      seated room sessions with nobody on duty (red alert)
 * @param partialRooms        seated room sessions with some, but not all, positions filled (amber)
 * @param recentSubstitutions empty when the role may not read the audit trail
 */
public record DashboardData(LocalDate today, LocalDate focusDay,
                            int activeExams, int examsOnFocusDay,
                            int enrolled, int seated, int hallCapacity,
                            int requiredPositions, int filledPositions,
                            int unstaffedRooms, int partialRooms,
                            int activeTeachers, int teachersOnDuty,
                            List<DayCapacity> days,
                            Map<AllocationState, Integer> allocation,
                            List<DeptWorkload> departments,
                            List<FocusExamRow> focusExams,
                            boolean auditVisible,
                            List<SubstitutionFeedItem> recentSubstitutions) {

    public enum AlertLevel { NONE, AMBER, RED }

    /** Seated students as a share of the hall capacity on the exam days (0..1). */
    public double utilisation() {
        return hallCapacity == 0 ? 0 : Math.min(1, seated / (double) hallCapacity);
    }

    /** Filled supervision positions as a share of the required ones (0..1); 0 when nothing is seated. */
    public double coverage() {
        return requiredPositions == 0 ? 0 : filledPositions / (double) requiredPositions;
    }

    public int understaffedRooms() {
        return unstaffedRooms + partialRooms;
    }

    public AlertLevel alertLevel() {
        return unstaffedRooms > 0 ? AlertLevel.RED : partialRooms > 0 ? AlertLevel.AMBER : AlertLevel.NONE;
    }

    public int unseatedStudents() {
        return Math.max(0, enrolled - seated);
    }
}
