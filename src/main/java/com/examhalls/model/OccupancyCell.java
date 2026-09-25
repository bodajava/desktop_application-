package com.examhalls.model;

import java.util.List;

/**
 * One room in one period on one day. {@code exams} can hold several exams when a room is
 * shared; {@code requiredProctors} follows the allocation rule (1 per 20 students, min 1).
 */
public record OccupancyCell(long roomId, long periodId, List<ExamSchedule> exams, int seated, int capacity,
                            List<SupervisionRoster> staff, int requiredProctors, CellStatus status) {

    public List<SupervisionRoster> heads() {
        return staff.stream().filter(s -> s.roleType() == SupervisionRole.HEAD_OF_COMMITTEE).toList();
    }

    public List<SupervisionRoster> proctors() {
        return staff.stream().filter(s -> s.roleType() == SupervisionRole.PROCTOR).toList();
    }

    /** Missing positions: head (0/1) plus missing proctors. */
    public int missingPositions() {
        if (seated == 0) {
            return 0;
        }
        return (heads().isEmpty() ? 1 : 0) + Math.max(0, requiredProctors - proctors().size());
    }
}
