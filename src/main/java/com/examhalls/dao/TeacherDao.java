package com.examhalls.dao;

import com.examhalls.model.Teacher;

import java.util.List;
import java.util.Optional;

public interface TeacherDao extends CrudDao<Teacher> {
    Optional<Teacher> findByCode(String teacherCode);

    List<Teacher> findByDepartment(long deptId);
}
