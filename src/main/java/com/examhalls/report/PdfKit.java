package com.examhalls.report;

import com.examhalls.config.AppSettings;
import com.examhalls.util.Messages;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared building blocks for all PDF reports: embedded Arabic-capable fonts, RTL-aware tables
 * (OpenPDF only shapes Arabic inside table cells / ColumnText with RTL run direction), a
 * standard header and a page footer. Every report is built from tables so both languages work.
 */
public final class PdfKit {

    public static final float MM = 72f / 25.4f;

    static final Color PRIMARY = new Color(0x1D, 0x4E, 0xD8);
    static final Color HEADER_BG = new Color(0xE2, 0xE8, 0xF0);
    static final Color ZEBRA = new Color(0xF8, 0xFA, 0xFC);
    static final Color MUTED = new Color(0x64, 0x74, 0x8B);
    static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);
    static final Color BORDER = new Color(0xCB, 0xD5, 0xE1);

    final boolean rtl = Messages.isRightToLeft();
    final int runDirection = rtl ? PdfWriter.RUN_DIRECTION_RTL : PdfWriter.RUN_DIRECTION_LTR;
    final Font title = new Font(ReportFonts.bold(), 16, Font.NORMAL, PRIMARY);
    final Font h2 = new Font(ReportFonts.bold(), 12);
    final Font body = new Font(ReportFonts.regular(), 10);
    final Font bold = new Font(ReportFonts.bold(), 10);
    final Font small = new Font(ReportFonts.regular(), 8);
    final Font smallMuted = new Font(ReportFonts.regular(), 8, Font.NORMAL, MUTED);
    final Font headerCell = new Font(ReportFonts.bold(), 9);
    final Font danger = new Font(ReportFonts.bold(), 9, Font.NORMAL, DANGER);

    final String schoolName = AppSettings.get("app.school.name", "Exam Control Office");

    /** Opens a document with the standard footer. Caller adds content and closes it. */
    Document open(OutputStream out, Rectangle pageSize, String reportTitle) throws DocumentException {
        Document doc = new Document(pageSize, 14 * MM, 14 * MM, 14 * MM, 16 * MM);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer(reportTitle));
        doc.addTitle(reportTitle);
        doc.addCreator("ExamHalls");
        doc.open();
        return doc;
    }

    /** School name, report title and up to a few subtitle lines, full width. */
    void header(Document doc, String reportTitle, String... subtitles) throws DocumentException {
        PdfPTable t = table(new float[]{1});
        t.addCell(plain(schoolName, smallMuted));
        t.addCell(plain(reportTitle, title));
        for (String s : subtitles) {
            if (s != null && !s.isBlank()) {
                t.addCell(plain(s, body));
            }
        }
        t.setSpacingAfter(8);
        doc.add(t);
    }

    /**
     * Full-width table. OpenPDF mirrors the cell order for RTL but not the width array, so the
     * widths are reversed here and callers can always list columns in reading order.
     */
    PdfPTable table(float[] widths) {
        float[] w = widths.clone();
        if (rtl) {
            for (int i = 0, j = w.length - 1; i < j; i++, j--) {
                float tmp = w[i];
                w[i] = w[j];
                w[j] = tmp;
            }
        }
        PdfPTable t = new PdfPTable(w);
        t.setWidthPercentage(100);
        t.setRunDirection(runDirection);
        return t;
    }

    PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setRunDirection(runDirection);
        c.setPadding(4);
        c.setBorderColor(BORDER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    PdfPCell headerCell(String text) {
        PdfPCell c = cell(text, headerCell);
        c.setBackgroundColor(HEADER_BG);
        c.setPaddingTop(5);
        c.setPaddingBottom(5);
        return c;
    }

    PdfPCell plain(String text, Font font) {
        PdfPCell c = cell(text, font);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2);
        return c;
    }

    PdfPCell centered(PdfPCell c) {
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    /** Label + value pair laid out as two cells (used in report info blocks). */
    void info(PdfPTable t, String label, String value) {
        PdfPCell l = cell(label, bold);
        l.setBackgroundColor(ZEBRA);
        t.addCell(l);
        t.addCell(cell(value, body));
    }

    /** Keeps Latin runs (times, codes) in reading order inside RTL text. */
    String ltr(String s) {
        return rtl && s != null ? "‪" + s + "‬" : s;
    }

    String t(String key, Object... args) {
        return Messages.get(key, args);
    }

    /** "Page n · generated at ... · title" at the bottom of every page. */
    private final class Footer extends PdfPageEventHelper {
        private final String reportTitle;
        private final String generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Footer(String reportTitle) {
            this.reportTitle = reportTitle;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            String text = Messages.get("report.footer", writer.getPageNumber(), ltr(generated), reportTitle);
            ColumnText ct = new ColumnText(cb);
            ct.setRunDirection(runDirection);
            ct.setSimpleColumn(new Phrase(text, smallMuted), document.leftMargin(), 6 * MM,
                    page.getWidth() - document.rightMargin(), 12 * MM, 10, Element.ALIGN_CENTER);
            try {
                ct.go();
            } catch (DocumentException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
