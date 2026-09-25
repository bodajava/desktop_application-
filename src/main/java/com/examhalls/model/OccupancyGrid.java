package com.examhalls.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Rooms x periods for one exam day. */
public record OccupancyGrid(LocalDate date, List<ExamPeriod> periods, List<Room> rooms,
                            Map<String, OccupancyCell> cells) {

    public OccupancyCell cell(long roomId, long periodId) {
        return cells.get(key(roomId, periodId));
    }

    public static String key(long roomId, long periodId) {
        return roomId + ":" + periodId;
    }

    public long count(CellStatus status) {
        return cells.values().stream().filter(c -> c.status() == status).count();
    }

    public boolean hasExams() {
        return cells.values().stream().anyMatch(c -> c.seated() > 0 || !c.exams().isEmpty());
    }
}
