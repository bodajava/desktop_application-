package com.examhalls.dao;

import com.examhalls.model.Course;

import java.util.List;
import java.util.Optional;

public interface CourseDao extends CrudDao<Course> {
    Optional<Course> findByCode(String courseCode);

    List<Course> findByGradeLevel(String gradeLevel);
}
