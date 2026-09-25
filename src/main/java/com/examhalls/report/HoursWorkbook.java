package com.examhalls.report;

import com.examhalls.model.DutyRow;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.TeacherHours;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Compensation workbook (Apache POI, .xlsx):
 * <ul>
 *   <li>"Summary": one row per teacher (head / proctor sessions, total hours) and an editable
 *       hourly-rate cell; the amount column and totals are live formulas.</li>
 *   <li>"Details": every duty, so the totals can be audited.</li>
 * </ul>
 * Sheets are right-to-left when the UI language is Arabic.
 */
public final class HoursWorkbook {

    private HoursWorkbook() {
    }

    public static void write(LocalDate from, LocalDate to, List<TeacherHours> totals, List<DutyRow> duties,
                             BigDecimal hourlyRate, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            boolean rtl = Messages.isRightToLeft();

            // ---------------------------------------------------------- Summary
            Sheet sum = wb.createSheet(Messages.get("report.hours.sheetSummary"));
            sum.setRightToLeft(rtl);
            text(sum.createRow(0), 0, Messages.get("report.hours.title"), st.title);
            text(sum.createRow(1), 0, Messages.get("report.range", Formats.date(from), Formats.date(to)), st.plain);
            Row rateRow = sum.createRow(2);
            text(rateRow, 0, Messages.get("report.hours.rate"), st.header);
            Cell rate = rateRow.createCell(1);
            rate.setCellValue(hourlyRate.doubleValue());
            rate.setCellStyle(st.input);
            text(rateRow, 2, Messages.get("report.hours.rateHint"), st.hint);
            String rateRef = "$B$3";

            String[] headers = {"#", Messages.get("ops.col.code"), Messages.get("ops.col.teacher"),
                    Messages.get("sub.col.dept"), Messages.get("report.hours.headSessions"),
                    Messages.get("report.hours.proctorSessions"), Messages.get("report.hours.totalSessions"),
                    Messages.get("report.hours.totalHours"), Messages.get("report.hours.amount")};
            int headerRow = 4;
            Row h = sum.createRow(headerRow);
            for (int i = 0; i < headers.length; i++) {
                text(h, i, headers[i], st.header);
            }
            int r = headerRow + 1;
            int n = 0;
            for (TeacherHours t : totals) {
                Row row = sum.createRow(r);
                number(row, 0, ++n, st.integer);
                text(row, 1, t.teacherCode(), st.plain);
                text(row, 2, t.teacherName(), st.plain);
                text(row, 3, t.deptName(), st.plain);
                number(row, 4, t.headSessions(), st.integer);
                number(row, 5, t.proctorSessions(), st.integer);
                formula(row, 6, "E" + (r + 1) + "+F" + (r + 1), st.integer);
                number(row, 7, t.totalHours().doubleValue(), st.decimal);
                formula(row, 8, "H" + (r + 1) + "*" + rateRef, st.money);
                r++;
            }
            Row total = sum.createRow(r);
            text(total, 3, Messages.get("report.hours.total"), st.totalLabel);
            int firstData = headerRow + 2, lastData = r;
            for (int col = 4; col <= 8; col++) {
                String letter = CellReference.convertNumToColString(col);
                formula(total, col, totals.isEmpty() ? "0" : "SUM(" + letter + firstData + ":" + letter + lastData + ")",
                        col == 7 ? st.totalDecimal : col == 8 ? st.totalMoney : st.totalInteger);
            }
            sum.createFreezePane(0, headerRow + 1);
            if (!totals.isEmpty()) {
                sum.setAutoFilter(new CellRangeAddress(headerRow, r - 1, 0, headers.length - 1));
            }
            sum.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            sum.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));
            widths(sum, 6, 14, 30, 22, 14, 14, 14, 14, 16);

            // ---------------------------------------------------------- Details
            Sheet det = wb.createSheet(Messages.get("report.hours.sheetDetails"));
            det.setRightToLeft(rtl);
            String[] dh = {Messages.get("ops.col.code"), Messages.get("ops.col.teacher"), Messages.get("schedule.col.date"),
                    Messages.get("schedule.col.period"), Messages.get("report.time"), Messages.get("schedule.col.course"),
                    Messages.get("ops.col.room"), Messages.get("ops.col.role"), Messages.get("report.hours.hours")};
            Row dhr = det.createRow(0);
            for (int i = 0; i < dh.length; i++) {
                text(dhr, i, dh[i], st.header);
            }
            int dr = 1;
            for (DutyRow d : duties) {
                Row row = det.createRow(dr++);
                text(row, 0, d.teacherCode(), st.plain);
                text(row, 1, d.teacherName(), st.plain);
                Cell date = row.createCell(2);
                date.setCellValue(d.examDate());
                date.setCellStyle(st.date);
                text(row, 3, d.periodName(), st.plain);
                text(row, 4, Formats.time(d.slotStart()) + " – " + Formats.time(d.slotEnd()), st.plain);
                text(row, 5, d.courseCode() + " — " + d.courseName(), st.plain);
                text(row, 6, d.roomCode(), st.plain);
                text(row, 7, Messages.get("supervisionRole." + d.roleType().name()), st.plain);
                number(row, 8, d.durationHours().doubleValue(), st.decimal);
            }
            det.createFreezePane(0, 1);
            if (!duties.isEmpty()) {
                det.setAutoFilter(new CellRangeAddress(0, dr - 1, 0, dh.length - 1));
            }
            widths(det, 14, 30, 14, 16, 16, 34, 10, 20, 10);

            wb.write(out);
        }
    }

    /** Aggregates duties per teacher, keeping the duties' order (by teacher name). */
    public static List<TeacherHours> aggregate(List<DutyRow> duties) {
        java.util.Map<Long, TeacherHours> map = new java.util.LinkedHashMap<>();
        for (DutyRow d : duties) {
            TeacherHours prev = map.getOrDefault(d.teacherId(), new TeacherHours(d.teacherId(), d.teacherCode(),
                    d.teacherName(), d.deptName(), 0, 0, BigDecimal.ZERO));
            boolean head = d.roleType() == SupervisionRole.HEAD_OF_COMMITTEE;
            map.put(d.teacherId(), new TeacherHours(prev.teacherId(), prev.teacherCode(), prev.teacherName(),
                    prev.deptName(), prev.headSessions() + (head ? 1 : 0), prev.proctorSessions() + (head ? 0 : 1),
                    prev.totalHours().add(d.durationHours())));
        }
        return List.copyOf(map.values());
    }

    // ------------------------------------------------------------------ cell helpers

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void number(Row row, int col, double value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void formula(Row row, int col, String formula, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellFormula(formula);
        c.setCellStyle(style);
    }

    private static void widths(Sheet sheet, int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sheet.setColumnWidth(i, chars[i] * 256);
        }
    }

    private static final class Styles {
        final CellStyle title, plain, hint, header, input, integer, decimal, money, date;
        final CellStyle totalLabel, totalInteger, totalDecimal, totalMoney;

        Styles(XSSFWorkbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            Font hintFont = wb.createFont();
            hintFont.setItalic(true);
            hintFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            title = wb.createCellStyle();
            title.setFont(titleFont);
            plain = bordered(wb);
            hint = wb.createCellStyle();
            hint.setFont(hintFont);
            header = bordered(wb);
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setWrapText(true);
            input = bordered(wb);
            input.setFont(boldFont);
            input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            input.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            integer = numeric(wb, "0");
            decimal = numeric(wb, "0.00");
            money = numeric(wb, "#,##0.00");
            date = numeric(wb, "yyyy-mm-dd");
            totalLabel = bordered(wb);
            totalLabel.setFont(boldFont);
            totalInteger = total(wb, "0", boldFont);
            totalDecimal = total(wb, "0.00", boldFont);
            totalMoney = total(wb, "#,##0.00", boldFont);
        }

        private static CellStyle bordered(XSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }

        private static CellStyle numeric(XSSFWorkbook wb, String format) {
            CellStyle s = bordered(wb);
            s.setDataFormat(wb.createDataFormat().getFormat(format));
            return s;
        }

        private static CellStyle total(XSSFWorkbook wb, String format, Font bold) {
            CellStyle s = numeric(wb, format);
            s.setFont(bold);
            s.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setBorderTop(BorderStyle.DOUBLE);
            return s;
        }
    }
}
