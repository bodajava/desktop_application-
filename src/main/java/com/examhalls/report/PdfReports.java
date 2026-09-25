package com.examhalls.report;

import com.examhalls.model.CellStatus;
import com.examhalls.model.DutyRow;
import com.examhalls.model.ExamPeriod;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.OccupancyCell;
import com.examhalls.model.OccupancyGrid;
import com.examhalls.model.Room;
import com.examhalls.model.RoomAllocationSummary;
import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.util.Formats;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The four PDF reports. Text follows the current UI language (Arabic = right-to-left). */
public final class PdfReports {

    private PdfReports() {
    }

    // ================================================================== 1. seating stickers

    /**
     * Desk stickers on A4 label sheets, 3 x 7 per page (63.5 x 38.1 mm, the common "L7160"
     * layout), ordered by room then seat so they can be stuck down room by room.
     */
    public static void seatingStickers(ExamSchedule exam, List<SeatingAllocation> seats, OutputStream out)
            throws DocumentException {
        PdfKit k = new PdfKit();
        String title = k.t("report.stickers.title");
        Document doc = new Document(PageSize.A4, 7.2f * PdfKit.MM, 7.2f * PdfKit.MM, 15.1f * PdfKit.MM, 0);
        PdfWriter.getInstance(doc, out);
        doc.addTitle(title);
        doc.open();

        float w = 63.5f * PdfKit.MM, gap = 2.5f * PdfKit.MM, h = 38.1f * PdfKit.MM;
        PdfPTable sheet = new PdfPTable(new float[]{w, gap, w, gap, w});
        sheet.setTotalWidth(3 * w + 2 * gap);
        sheet.setLockedWidth(true);
        sheet.setRunDirection(k.runDirection);

        Font big = new Font(ReportFonts.bold(), 13);
        Font name = new Font(ReportFonts.bold(), 9);
        Font courseFont = new Font(ReportFonts.bold(), 8);
        String when = Formats.date(exam.examDate()) + " · " + k.ltr(Formats.timeRange(exam.slotStart(), exam.slotEnd()));

        int column = 0;
        for (SeatingAllocation s : seats) {
            PdfPTable label = k.table(new float[]{1, 1});
            PdfPCell school = k.plain(k.schoolName, k.smallMuted);
            school.setColspan(2);
            label.addCell(school);
            PdfPCell course = k.plain(k.ltr(exam.courseCode()) + " — " + exam.courseName(), courseFont);
            course.setColspan(2);
            label.addCell(course);
            PdfPCell date = k.plain(when, k.small);
            date.setColspan(2);
            label.addCell(date);
            label.addCell(labelled(k, k.t("report.room"), s.roomCode(), big));
            label.addCell(labelled(k, k.t("report.seat"), s.seatNumber(), big));
            PdfPCell student = k.plain(s.studentName(), name);
            student.setColspan(2);
            label.addCell(student);
            PdfPCell code = k.plain(k.ltr(s.studentCode())
                    + (s.hasSpecialNeeds() ? "   * " + k.t("ops.specialNeeds") : ""), s.hasSpecialNeeds() ? k.danger : k.small);
            code.setColspan(2);
            label.addCell(code);

            PdfPCell holder = new PdfPCell(label);
            holder.setFixedHeight(h);
            holder.setPadding(3 * PdfKit.MM);
            holder.setBorderColor(PdfKit.BORDER);
            holder.setBorderWidth(0.4f);
            holder.setVerticalAlignment(Element.ALIGN_MIDDLE);
            sheet.addCell(holder);
            if (column < 2) {
                sheet.addCell(spacer(h));
            }
            column = (column + 1) % 3;
        }
        while (column != 0) {                       // complete the last row
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            empty.setFixedHeight(h);
            sheet.addCell(empty);
            if (column < 2) {
                sheet.addCell(spacer(h));
            }
            column = (column + 1) % 3;
        }
        if (seats.isEmpty()) {
            PdfPTable none = k.table(new float[]{1});
            none.addCell(k.plain(k.t("report.noSeating"), k.body));
            doc.add(none);
        } else {
            doc.add(sheet);
        }
        doc.close();
    }

    /** Small caption followed by a large value on the same line ("Room  B101"). */
    private static PdfPCell labelled(PdfKit k, String caption, String value, Font valueFont) {
        com.lowagie.text.Phrase p = new com.lowagie.text.Phrase();
        p.add(new com.lowagie.text.Chunk(caption + "  ", k.smallMuted));
        p.add(new com.lowagie.text.Chunk(value, valueFont));
        PdfPCell c = new PdfPCell(p);
        c.setRunDirection(k.runDirection);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(1);
        return c;
    }

    private static PdfPCell spacer(float height) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setFixedHeight(height);
        return c;
    }

    // ================================================================== 2. attendance call sheets

    /** One page (or more) per room: call list with presence columns and signature block. */
    public static void attendanceSheets(ExamSchedule exam, List<RoomAllocationSummary> rooms,
                                        List<SeatingAllocation> seats, OutputStream out) throws DocumentException {
        PdfKit k = new PdfKit();
        String title = k.t("report.attendance.title");
        Document doc = k.open(out, PageSize.A4, title);
        Map<Long, List<SeatingAllocation>> byRoom = seats.stream()
                .collect(Collectors.groupingBy(SeatingAllocation::roomId, LinkedHashMap::new, Collectors.toList()));

        boolean first = true;
        for (RoomAllocationSummary room : rooms) {
            if (!first) {
                doc.newPage();
            }
            first = false;
            k.header(doc, title, k.ltr(exam.courseCode()) + " — " + exam.courseName() + " · " + exam.gradeLevel());

            PdfPTable info = k.table(new float[]{1.2f, 2, 1.2f, 2});
            k.info(info, k.t("report.date"), Formats.longDate(exam.examDate()));
            k.info(info, k.t("report.time"), exam.periodName() + " · " + k.ltr(Formats.timeRange(exam.slotStart(), exam.slotEnd())));
            k.info(info, k.t("report.room"), k.ltr(room.roomCode()) + " · " + room.building());
            k.info(info, k.t("ops.col.head"), names(k, room.heads()));
            PdfPCell pl = k.cell(k.t("ops.col.proctors"), k.bold);
            pl.setBackgroundColor(PdfKit.ZEBRA);
            info.addCell(pl);
            PdfPCell pv = k.cell(names(k, room.proctors()), k.body);
            pv.setColspan(3);
            info.addCell(pv);
            info.setSpacingAfter(10);
            doc.add(info);

            PdfPTable list = k.table(new float[]{5, 8, 17, 35, 9, 9, 17});
            list.setHeaderRows(1);
            for (String h : new String[]{"#", k.t("ops.col.seat"), k.t("ops.col.studentCode"), k.t("ops.col.student"),
                    k.t("attendance.PRESENT"), k.t("attendance.ABSENT"), k.t("report.signature")}) {
                list.addCell(k.centered(k.headerCell(h)));
            }
            int n = 0;
            for (SeatingAllocation s : byRoom.getOrDefault(room.roomId(), List.of())) {
                n++;
                java.awt.Color bg = n % 2 == 0 ? PdfKit.ZEBRA : null;
                list.addCell(bg(k.centered(k.cell(String.valueOf(n), k.body)), bg));
                list.addCell(bg(k.centered(k.cell(s.seatNumber(), k.bold)), bg));
                list.addCell(bg(k.cell(k.ltr(s.studentCode()), k.body), bg));
                list.addCell(bg(k.cell(s.studentName() + (s.hasSpecialNeeds() ? "  *" : ""), k.body), bg));
                list.addCell(bg(k.cell("", k.body), bg));
                list.addCell(bg(k.cell("", k.body), bg));
                PdfPCell sig = bg(k.cell("", k.body), bg);
                sig.setFixedHeight(20);
                list.addCell(sig);
            }
            doc.add(list);

            PdfPTable totals = k.table(new float[]{1, 1, 1});
            totals.setSpacingBefore(10);
            totals.addCell(k.cell(k.t("report.totalStudents", n), k.bold));
            totals.addCell(k.cell(k.t("attendance.PRESENT") + ": ________", k.body));
            totals.addCell(k.cell(k.t("attendance.ABSENT") + ": ________", k.body));
            doc.add(totals);
            if (room.specialNeeds() > 0) {
                PdfPTable note = k.table(new float[]{1});
                note.addCell(k.plain("* " + k.t("report.specialNeedsNote", room.specialNeeds()), k.danger));
                doc.add(note);
            }
            doc.add(signatures(k, k.t("ops.col.head"), k.t("ops.col.proctors")));
        }
        if (rooms.isEmpty()) {
            k.header(doc, title, k.t("report.noSeating"));
        }
        doc.close();
    }

    // ================================================================== 3. teacher schedules

    /** One page per teacher (only teachers with duties) listing every duty in the range. */
    public static void teacherSchedules(LocalDate from, LocalDate to, List<DutyRow> duties, OutputStream out)
            throws DocumentException {
        PdfKit k = new PdfKit();
        String title = k.t("report.teacherSchedule.title");
        Document doc = k.open(out, PageSize.A4, title);
        String range = k.t("report.range", Formats.date(from), Formats.date(to));

        Map<Long, List<DutyRow>> byTeacher = duties.stream()
                .collect(Collectors.groupingBy(DutyRow::teacherId, LinkedHashMap::new, Collectors.toList()));
        boolean first = true;
        for (List<DutyRow> rows : byTeacher.values()) {
            if (!first) {
                doc.newPage();
            }
            first = false;
            DutyRow t = rows.get(0);
            k.header(doc, title, t.teacherName() + " · " + k.ltr(t.teacherCode()) + " · " + t.deptName(), range);

            PdfPTable table = k.table(new float[]{5, 22, 16, 15, 20, 10, 16});
            table.setHeaderRows(1);
            for (String h : new String[]{"#", k.t("schedule.col.date"), k.t("schedule.col.period"), k.t("report.time"),
                    k.t("schedule.col.course"), k.t("ops.col.room"), k.t("ops.col.role")}) {
                table.addCell(k.centered(k.headerCell(h)));
            }
            int n = 0;
            BigDecimal hours = BigDecimal.ZERO;
            int heads = 0;
            for (DutyRow d : rows) {
                n++;
                hours = hours.add(d.durationHours());
                heads += d.roleType() == SupervisionRole.HEAD_OF_COMMITTEE ? 1 : 0;
                java.awt.Color bg = n % 2 == 0 ? PdfKit.ZEBRA : null;
                table.addCell(bg(k.centered(k.cell(String.valueOf(n), k.body)), bg));
                table.addCell(bg(k.cell(Formats.date(d.examDate()), k.body), bg));
                table.addCell(bg(k.cell(d.periodName(), k.body), bg));
                table.addCell(bg(k.centered(k.cell(Formats.timeRange(d.slotStart(), d.slotEnd()), k.body)), bg));
                table.addCell(bg(k.cell(k.ltr(d.courseCode()), k.body), bg));
                table.addCell(bg(k.centered(k.cell(k.ltr(d.roomCode()), k.bold)), bg));
                table.addCell(bg(k.cell(Formats.enumLabel("supervisionRole", d.roleType()), k.body), bg));
            }
            doc.add(table);

            PdfPTable sum = k.table(new float[]{1, 1, 1});
            sum.setSpacingBefore(10);
            sum.addCell(k.cell(k.t("report.totalSessions", n), k.bold));
            sum.addCell(k.cell(k.t("report.headSessions", heads), k.body));
            sum.addCell(k.cell(k.t("report.totalHours", Formats.hours(hours)), k.bold));
            doc.add(sum);
            doc.add(signatures(k, k.t("report.teacherSignature"), k.t("report.controlSignature")));
        }
        if (byTeacher.isEmpty()) {
            k.header(doc, title, range, k.t("report.noDuties"));
        }
        doc.close();
    }

    // ================================================================== 4. daily control sheet

    /** Landscape overview of every occupied room in every period of one day, for committee heads. */
    public static void dailyControlSheet(OccupancyGrid grid, OutputStream out) throws DocumentException {
        PdfKit k = new PdfKit();
        String title = k.t("report.daily.title");
        // true landscape media box (a /Rotate flag is shown sideways by some viewers)
        Document doc = k.open(out, new Rectangle(PageSize.A4.getHeight(), PageSize.A4.getWidth()), title);
        k.header(doc, title, Formats.longDate(grid.date()));

        Map<Long, Room> rooms = grid.rooms().stream().collect(Collectors.toMap(Room::roomId, r -> r));
        int students = 0, staff = 0, under = 0, used = 0;
        for (ExamPeriod p : grid.periods()) {
            List<OccupancyCell> cells = grid.rooms().stream().map(r -> grid.cell(r.roomId(), p.periodId()))
                    .filter(c -> c != null && c.seated() > 0).toList();
            if (cells.isEmpty()) {
                continue;
            }
            PdfPTable heading = k.table(new float[]{1});
            heading.setSpacingBefore(6);
            heading.addCell(k.plain(p.periodName() + " · " + Formats.timeRange(p.startTime(), p.endTime()), k.h2));
            doc.add(heading);

            PdfPTable t = k.table(new float[]{7, 13, 17, 8, 16, 25, 9, 12});
            t.setHeaderRows(1);
            for (String h : new String[]{k.t("ops.col.room"), k.t("ops.col.building"), k.t("schedule.col.course"),
                    k.t("schedule.col.seated"), k.t("ops.col.head"), k.t("ops.col.proctors"),
                    k.t("schedule.col.status"), k.t("report.signature")}) {
                t.addCell(k.centered(k.headerCell(h)));
            }
            for (OccupancyCell c : cells) {
                Room r = rooms.get(c.roomId());
                boolean understaffed = c.status() == CellStatus.UNDERSTAFFED;
                used++;
                students += c.seated();
                staff += c.staff().size();
                under += understaffed ? 1 : 0;
                t.addCell(k.centered(k.cell(k.ltr(r.roomCode()), k.bold)));
                t.addCell(k.cell(r.building(), k.body));
                t.addCell(k.cell(c.exams().stream().map(e -> k.ltr(e.courseCode())).collect(Collectors.joining(", ")), k.body));
                t.addCell(k.centered(k.cell(Formats.ratio(c.seated(), c.capacity()), k.body)));
                t.addCell(k.cell(names(k, c.heads()), k.body));
                t.addCell(k.cell(names(k, c.proctors()), k.body));
                t.addCell(k.centered(k.cell(understaffed ? k.t("report.understaffed", c.missingPositions())
                        : k.t("grid.status.STAFFED"), understaffed ? k.danger : k.body)));
                PdfPCell sig = k.cell("", k.body);
                sig.setFixedHeight(24);
                t.addCell(sig);
            }
            doc.add(t);
        }
        PdfPTable sum = k.table(new float[]{1, 1, 1, 1});
        sum.setSpacingBefore(12);
        sum.addCell(k.cell(k.t("report.daily.rooms", used), k.bold));
        sum.addCell(k.cell(k.t("report.daily.students", students), k.bold));
        sum.addCell(k.cell(k.t("report.daily.staff", staff), k.bold));
        sum.addCell(k.cell(k.t("report.daily.understaffed", under), under > 0 ? k.danger : k.bold));
        doc.add(sum);
        if (used == 0) {
            PdfPTable none = k.table(new float[]{1});
            none.addCell(k.plain(k.t("report.daily.none"), k.body));
            doc.add(none);
        }
        doc.add(signatures(k, k.t("report.controlSignature"), k.t("report.principalSignature")));
        doc.close();
    }

    // ================================================================== helpers

    private static PdfPTable signatures(PdfKit k, String left, String right) {
        PdfPTable t = k.table(new float[]{1, 1});
        t.setSpacingBefore(24);
        t.addCell(k.plain(left + ": ______________________", k.body));
        t.addCell(k.plain(right + ": ______________________", k.body));
        return t;
    }

    private static PdfPCell bg(PdfPCell c, java.awt.Color color) {
        if (color != null) {
            c.setBackgroundColor(color);
        }
        return c;
    }

    private static String names(PdfKit k, List<SupervisionRoster> staff) {
        return staff.isEmpty() ? "—" : staff.stream().map(SupervisionRoster::teacherName)
                .collect(Collectors.joining(k.rtl ? "، " : ", "));
    }
}
