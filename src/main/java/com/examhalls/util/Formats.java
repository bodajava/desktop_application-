package com.examhalls.util;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Locale;

/** Locale-aware display formatting (digits stay Western for readability of codes and times). */
public final class Formats {

    private static final Locale ARABIC_DIGITS = Locale.forLanguageTag("ar-EG");

    private Formats() {
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(dateFormatter("EEE d MMM yyyy"));
    }

    public static String longDate(LocalDate d) {
        return d == null ? "" : d.format(dateFormatter("EEEE d MMMM yyyy"));
    }

    /**
     * Arabic dates use Arabic-Indic digits (٢٠٢٧): mixing Western digits into Arabic words breaks
     * the bidi layout ("الأحد10"), while an all-Arabic date renders cleanly right-to-left.
     */
    private static DateTimeFormatter dateFormatter(String pattern) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Messages.currentLocale());
        return Messages.isRightToLeft() ? f.withDecimalStyle(DecimalStyle.of(ARABIC_DIGITS)) : f;
    }

    /** Keeps Latin data (times, codes, English names) in reading order inside Arabic text. */
    public static String ltr(String s) {
        return Messages.isRightToLeft() && s != null ? "\u202A" + s + "\u202C" : s;
    }

    public static String time(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /**
     * "09:00 – 11:00"; in Arabic "09:00-11:00": digits joined by ':' and '-' form a single
     * left-to-right number run under the Unicode bidi rules, so the order survives in RTL text
     * everywhere (JavaFX and PDF), whereas a spaced dash splits it and gets reversed.
     */
    public static String timeRange(LocalDateTime start, LocalDateTime end) {
        return Messages.isRightToLeft() ? time(start) + "-" + time(end) : time(start) + " – " + time(end);
    }

    /** "56 / 60"; in Arabic "56/60" so the fraction stays one left-to-right number run. */
    public static String ratio(int part, int whole) {
        return Messages.isRightToLeft() ? part + "/" + whole : part + " / " + whole;
    }

    public static String timeRange(java.time.LocalTime start, java.time.LocalTime end) {
        return Messages.isRightToLeft() ? start + "-" + end : start + " – " + end;
    }

    public static String hours(BigDecimal h) {
        return h == null ? "0" : h.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Looks up {@code prefix.NAME} for an enum constant, e.g. stage.SEATED. */
    public static String enumLabel(String prefix, Enum<?> value) {
        return value == null ? "" : Messages.get(prefix + "." + value.name());
    }
}
