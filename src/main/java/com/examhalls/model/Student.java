package com.examhalls.model;

public record Student(Long studentId, String studentCode, String fullName, String gradeLevel, String section,
                      boolean hasSpecialNeeds, String email) {
}
