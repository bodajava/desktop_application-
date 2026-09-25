package com.examhalls.dao;

import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamSchedule;

import java.time.LocalDate;
import java.util.List;

public interface ExamScheduleDao extends CrudDao<ExamSchedule> {
    /** Inclusive date range, ordered by slot start. */
    List<ExamSchedule> findBetween(LocalDate from, LocalDate to);

    /** Exams in the range with enrolment / seating / staffing counters (schedule grid). */
    List<ExamOverview> findOverview(LocalDate from, LocalDate to);
}
