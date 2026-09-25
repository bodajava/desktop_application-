package com.examhalls.controller;

import com.examhalls.model.ExamOverview;
import com.examhalls.model.Teacher;
import com.examhalls.service.ReportService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.Exports;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Screen 9 — Reports & Exports Center. */
public class ReportsController {

    /** Picker entry; {@code teacher == null} means "all teachers". */
    private record TeacherChoice(Teacher teacher) {
    }

    private record Lists(List<ExamOverview> exams, List<Teacher> teachers, LocalDate nextExamDay) {
    }

    @FXML private ComboBox<ExamOverview> examCombo;
    @FXML private Button stickersButton;
    @FXML private Button attendanceButton;
    @FXML private DatePicker dailyDate;
    @FXML private ComboBox<TeacherChoice> teacherCombo;
    @FXML private DatePicker teacherFrom;
    @FXML private DatePicker teacherTo;
    @FXML private DatePicker hoursFrom;
    @FXML private DatePicker hoursTo;
    @FXML private BusyOverlay busy;

    private final ReportService reports = ServiceRegistry.get().reportService();

    @FXML
    private void initialize() {
        examCombo.setConverter(converter(o -> Formats.date(o.exam().examDate()) + "  ·  " + o.exam().periodName()
                + "  ·  " + o.exam().courseCode() + " — " + o.exam().courseName()));
        teacherCombo.setConverter(converter(c -> c.teacher() == null ? Messages.get("reports.allTeachers")
                : c.teacher().fullName() + " (" + c.teacher().teacherCode() + ")"));
        examCombo.valueProperty().addListener((o, a, b) -> {
            stickersButton.setDisable(b == null || b.seated() == 0);
            attendanceButton.setDisable(b == null || b.seated() == 0);
        });
        stickersButton.setDisable(true);
        attendanceButton.setDisable(true);

        busy.show(Messages.get("busy.loading"));
        LocalDate today = LocalDate.now();
        FxAsync.run(() -> new Lists(
                        ServiceRegistry.get().examManagementService().getExamOverview(today.minusYears(1), today.plusYears(1)),
                        reports.teachers(),
                        ServiceRegistry.get().occupancyService().nextExamDate(today).orElse(today)),
                this::fill, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    private void fill(Lists l) {
        examCombo.setItems(FXCollections.observableArrayList(l.exams()));
        l.exams().stream().filter(o -> o.seated() > 0).findFirst().ifPresent(examCombo::setValue);

        List<TeacherChoice> choices = new ArrayList<>();
        choices.add(new TeacherChoice(null));
        l.teachers().forEach(t -> choices.add(new TeacherChoice(t)));
        teacherCombo.setItems(FXCollections.observableArrayList(choices));
        teacherCombo.getSelectionModel().selectFirst();

        LocalDate start = l.nextExamDay().withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        dailyDate.setValue(l.nextExamDay());
        teacherFrom.setValue(start);
        teacherTo.setValue(end);
        hoursFrom.setValue(start);
        hoursTo.setValue(end);
    }

    @FXML
    private void onStickers() {
        ExamOverview e = examCombo.getValue();
        if (e != null) {
            Exports.run(Exports.Kind.PDF, "stickers_" + Exports.safe(e.exam().courseCode()) + "_" + e.exam().examDate() + ".pdf",
                    busy, path -> reports.exportSeatingStickers(e.exam().examId(), path));
        }
    }

    @FXML
    private void onAttendance() {
        ExamOverview e = examCombo.getValue();
        if (e != null) {
            Exports.run(Exports.Kind.PDF, "attendance_" + Exports.safe(e.exam().courseCode()) + "_" + e.exam().examDate() + ".pdf",
                    busy, path -> reports.exportAttendanceSheets(e.exam().examId(), path));
        }
    }

    @FXML
    private void onDaily() {
        LocalDate d = dailyDate.getValue();
        if (d != null) {
            Exports.run(Exports.Kind.PDF, "daily_control_" + d + ".pdf", busy, path -> reports.exportDailyControlSheet(d, path));
        }
    }

    @FXML
    private void onTeacher() {
        TeacherChoice c = teacherCombo.getValue();
        LocalDate from = teacherFrom.getValue(), to = teacherTo.getValue();
        if (c == null) {
            return;
        }
        Long id = c.teacher() == null ? null : c.teacher().teacherId();
        String who = c.teacher() == null ? "all" : Exports.safe(c.teacher().teacherCode());
        Exports.run(Exports.Kind.PDF, "proctoring_schedule_" + who + "_" + from + "_" + to + ".pdf", busy,
                path -> reports.exportTeacherSchedule(id, from, to, path));
    }

    @FXML
    private void onHours() {
        LocalDate from = hoursFrom.getValue(), to = hoursTo.getValue();
        Exports.run(Exports.Kind.EXCEL, "proctoring_hours_" + from + "_" + to + ".xlsx", busy,
                path -> reports.exportHoursWorkbook(from, to, path));
    }

    private static <T> StringConverter<T> converter(Function<T, String> f) {
        return new StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : f.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
