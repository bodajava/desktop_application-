package com.examhalls.dao;

import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.StudentExamRow;

import java.time.LocalDate;
import java.util.List;

/** Read-only: seats are written exclusively by pkg_seating. */
public interface SeatingAllocationDao {
    List<SeatingAllocation> findByExam(long examId);

    List<SeatingAllocation> findByExamAndRoom(long examId, long roomId);

    int countByExam(long examId);

    /** A student's own upcoming exam schedule, earliest first. */
    List<StudentExamRow> findUpcomingByStudent(long studentId, LocalDate from);
}
