package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.dao.ExamPeriodDao;
import com.examhalls.model.ExamPeriod;

import java.util.List;

public class ExamPeriodDaoImpl extends JdbcSupport implements ExamPeriodDao {

    public ExamPeriodDaoImpl(TransactionManager tx) {
        super(tx);
    }

    @Override
    public List<ExamPeriod> findAll() {
        return query("SELECT period_id, period_name, start_time, end_time FROM exam_periods ORDER BY start_time",
                NO_PARAMS, rs -> new ExamPeriod(rs.getLong("period_id"), rs.getString("period_name"),
                        getDateTime(rs, "start_time").toLocalTime(), getDateTime(rs, "end_time").toLocalTime()));
    }
}
