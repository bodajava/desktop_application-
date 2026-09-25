package com.examhalls.dao;

import com.examhalls.model.DeptWorkload;
import com.examhalls.model.DutyRow;
import com.examhalls.model.RoomSeatCount;
import com.examhalls.model.SubstitutionFeedItem;
import com.examhalls.model.SupervisionRoster;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Read-only queries behind reports, exports and the occupancy grid. */
public interface ReportDao {

    /** CONFIRMED duties in the date range; {@code teacherId} null = all teachers. Ordered by teacher, slot. */
    List<DutyRow> findDuties(LocalDate from, LocalDate to, Long teacherId);

    /** Seats per exam and room for every exam on the date. */
    default List<RoomSeatCount> seatCountsOn(LocalDate date) {
        return seatCountsBetween(date, date);
    }

    /** Seats per exam and room for every exam in the date range. */
    List<RoomSeatCount> seatCountsBetween(LocalDate from, LocalDate to);

    /** CONFIRMED roster rows of every exam on the date. */
    default List<SupervisionRoster> activeRosterOn(LocalDate date) {
        return activeRosterBetween(date, date);
    }

    /** CONFIRMED roster rows of every exam in the date range, ordered by day, room, role, name. */
    List<SupervisionRoster> activeRosterBetween(LocalDate from, LocalDate to);

    /** Active teachers and their accumulated supervision hours per department (departments with teachers). */
    List<DeptWorkload> departmentWorkload();

    /** The latest emergency substitutions, newest first. */
    List<SubstitutionFeedItem> recentSubstitutions(int limit);

    /** First date on or after {@code from} that has an exam. */
    Optional<LocalDate> nextExamDate(LocalDate from);
}
