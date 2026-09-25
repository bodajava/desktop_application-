package com.examhalls.dao;

import com.examhalls.model.SeatingAllocation;

import java.util.List;

/** Read-only: seats are written exclusively by pkg_seating. */
public interface SeatingAllocationDao {
    List<SeatingAllocation> findByExam(long examId);

    List<SeatingAllocation> findByExamAndRoom(long examId, long roomId);

    int countByExam(long examId);
}
