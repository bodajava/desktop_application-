package com.examhalls.model;

public record Course(Long courseId, String courseName, String courseCode, Long deptId, String deptName,
                     String gradeLevel) {
}
