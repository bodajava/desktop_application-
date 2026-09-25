package com.examhalls.service;

import com.examhalls.config.AppSettings;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.ReportDao;
import com.examhalls.dao.SeatingAllocationDao;
import com.examhalls.dao.TeacherDao;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.model.DutyRow;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.Teacher;
import com.examhalls.report.HoursWorkbook;
import com.examhalls.report.PdfReports;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Screen 9 — PDF and Excel exports. Every export is written to a temporary file next to the
 * target and moved into place only when complete, so a failure never leaves a half-written file.
 */
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    @FunctionalInterface
    private interface Writer {
        void write(OutputStream out) throws Exception;
    }

    private final UserSession session;
    private final ExamScheduleDao examDao;
    private final SeatingAllocationDao seatingDao;
    private final ReportDao reportDao;
    private final ExamManagementService exams;
    private final OccupancyService occupancy;
    private final TeacherDao teacherDao;

    public ReportService(UserSession session, ExamScheduleDao examDao, SeatingAllocationDao seatingDao,
                         ReportDao reportDao, ExamManagementService exams, OccupancyService occupancy,
                         TeacherDao teacherDao) {
        this.session = session;
        this.examDao = examDao;
        this.seatingDao = seatingDao;
        this.reportDao = reportDao;
        this.exams = exams;
        this.occupancy = occupancy;
        this.teacherDao = teacherDao;
    }

    /** Active teachers for the schedule report picker. */
    public List<Teacher> teachers() {
        session.require(Permission.VIEW_REPORTS);
        return teacherDao.findAll();
    }

    /** ملصقات المقاعد — desk stickers for every seated student of the exam. */
    public void exportSeatingStickers(long examId, Path target) {
        session.require(Permission.VIEW_REPORTS);
        ExamSchedule exam = exam(examId);
        write(target, out -> PdfReports.seatingStickers(exam, seatingDao.findByExam(examId), out));
    }

    /** كشوف المناداة والغياب — one call sheet per room. */
    public void exportAttendanceSheets(long examId, Path target) {
        session.require(Permission.VIEW_REPORTS);
        ExamSchedule exam = exam(examId);
        write(target, out -> PdfReports.attendanceSheets(exam, exams.getRoomSummaries(examId),
                seatingDao.findByExam(examId), out));
    }

    /**
     * جدول المراقبة الفردي — {@code teacherId} null = every teacher with duties (one page each).
     * Teachers may export only their own schedule.
     */
    public void exportTeacherSchedule(Long teacherId, LocalDate from, LocalDate to, Path target) {
        AuthenticatedUser me = session.requireUser();
        if (!me.can(Permission.VIEW_REPORTS)) {
            boolean own = me.can(Permission.VIEW_OWN_SCHEDULE) && me.teacherId() != null
                    && Objects.equals(me.teacherId(), teacherId);
            if (!own) {
                throw new AppException(ErrorCode.ACCESS_DENIED, me.username() + " may only export their own schedule");
            }
        }
        checkRange(from, to);
        List<DutyRow> duties = reportDao.findDuties(from, to, teacherId);
        write(target, out -> PdfReports.teacherSchedules(from, to, duties, out));
    }

    /** جدول مراقبة اللجان اليومي الشامل — every occupied room in every period of the day. */
    public void exportDailyControlSheet(LocalDate date, Path target) {
        session.require(Permission.VIEW_REPORTS);
        write(target, out -> PdfReports.dailyControlSheet(occupancy.getGrid(date), out));
    }

    /** تقرير إجمالي ساعات المراقبة — Excel workbook for compensation. */
    public void exportHoursWorkbook(LocalDate from, LocalDate to, Path target) {
        session.require(Permission.VIEW_REPORTS);
        checkRange(from, to);
        List<DutyRow> duties = reportDao.findDuties(from, to, null);
        BigDecimal rate = AppSettings.getDecimal("reports.compensation.hourlyRate", BigDecimal.valueOf(50));
        write(target, out -> HoursWorkbook.write(from, to, HoursWorkbook.aggregate(duties), duties, rate, out));
    }

    // ------------------------------------------------------------------ helpers

    private ExamSchedule exam(long examId) {
        return examDao.findById(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Exam " + examId + " does not exist"));
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.messageKey(),
                    "invalid date range", null, "from / to");
        }
    }

    private void write(Path target, Writer writer) {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".examhalls-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("{} exported {}", session.currentUser().map(AuthenticatedUser::username).orElse("?"), target);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.EXPORT_FAILED, target + ": " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // best effort cleanup of the temp file
                }
            }
        }
    }
}
