package com.examhalls.dao;

import com.examhalls.model.SupervisionRoster;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Read-only: the roster is written exclusively by pkg_supervision. */
public interface SupervisionRosterDao {
    Optional<SupervisionRoster> findById(long rosterId);

    /** CONFIRMED assignments of an exam, by room then role. */
    List<SupervisionRoster> findActiveByExam(long examId);

    /**
     * CONFIRMED staff of every room this exam uses, including staff attached to another exam that
     * shares the room in an overlapping slot (so shared rooms show their real staff).
     */
    List<SupervisionRoster> findActiveForExamRooms(long examId);

    /** A teacher's CONFIRMED duties on or after {@code from}. */
    List<SupervisionRoster> findUpcomingByTeacher(long teacherId, LocalDate from);
}
