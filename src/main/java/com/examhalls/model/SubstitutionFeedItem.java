package com.examhalls.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One entry of the dashboard's recent emergency substitutions feed (from SUPERVISION_AUDIT). */
public record SubstitutionFeedItem(LocalDateTime at, LocalDate examDate, String periodName, String courseCode,
                                   String roomCode, String replacedName, String substituteName,
                                   String executedBy, String reason) {
}
