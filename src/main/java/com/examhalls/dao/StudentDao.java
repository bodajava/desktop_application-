package com.examhalls.dao;

import com.examhalls.model.Student;

import java.util.List;
import java.util.Optional;

public interface StudentDao extends CrudDao<Student> {
    Optional<Student> findByCode(String studentCode);

    List<Student> findByGradeLevel(String gradeLevel);
}
