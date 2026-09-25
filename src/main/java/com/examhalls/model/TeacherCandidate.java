package com.examhalls.model;

import com.examhalls.util.Messages;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * A teacher evaluated for a specific exam/room by pkg_supervision.get_candidates.
 * {@code eligibility} is null when eligible, else "<CODE>: <detail>".
 */
public record TeacherCandidate(long teacherId, String teacherCode, String fullName, String deptName,
                               BigDecimal hoursBalance, String eligibility) {

    public boolean eligible() {
        return eligibility == null;
    }

    /** SUBJECT_CONFLICT, DAILY_LIMIT, ... or ELIGIBLE. */
    public String reasonCode() {
        if (eligibility == null) {
            return "ELIGIBLE";
        }
        int colon = eligibility.indexOf(':');
        return colon > 0 ? eligibility.substring(0, colon) : eligibility;
    }

    public String reasonText(Locale locale) {
        return Messages.get(locale, "eligibility." + reasonCode(), null);
    }
}
