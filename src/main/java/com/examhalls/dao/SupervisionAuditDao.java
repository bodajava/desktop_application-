package com.examhalls.dao;

import com.examhalls.model.SupervisionAudit;

import java.util.List;

/** Read-only: audit rows are append-only and written by pkg_supervision.replace_proctor. */
public interface SupervisionAuditDao {
    List<SupervisionAudit> findRecent(int limit);

    List<SupervisionAudit> findByExam(long examId);
}
