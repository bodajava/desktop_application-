package com.examhalls.dao;

import com.examhalls.model.ExamPeriod;

import java.util.List;

public interface ExamPeriodDao {
    List<ExamPeriod> findAll();
}
