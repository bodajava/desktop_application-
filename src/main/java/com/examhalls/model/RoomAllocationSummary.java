package com.examhalls.model;

import java.util.List;

/**
 * One row of the supervision matrix: a room used by an exam, its occupancy and staff.
 * {@code staff} includes staff attached to another exam that shares the room in the same slot.
 */
public record RoomAllocationSummary(long roomId, String roomCode, String building, int examCapacity,
                                    int seatedThisExam, int specialNeeds, List<SupervisionRoster> staff) {

    public List<SupervisionRoster> heads() {
        return staff.stream().filter(s -> s.roleType() == SupervisionRole.HEAD_OF_COMMITTEE).toList();
    }

    public List<SupervisionRoster> proctors() {
        return staff.stream().filter(s -> s.roleType() == SupervisionRole.PROCTOR).toList();
    }
}
